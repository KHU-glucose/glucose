package com.glucoselog.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.glucoselog.auth.AuthTestConfig;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobHandler;
import com.glucoselog.job.JobRepository;
import com.glucoselog.job.JobService;
import com.glucoselog.job.JobStatus;
import com.sun.net.httpserver.HttpServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/** 사진 업로드 완료 → FOOD_RECOGNIZE job 적재 → 워커가 ml-service를 호출하는 전체 흐름. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
class PhotoRecognitionJobTest {

    private static final String BUCKET = "glucose-photos";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest"))
            .withExposedPorts(9090);

    private static HttpServer mlServiceStub;
    private static volatile int mlServiceStatus = 200;
    private static volatile String mlServiceBody = "{\"is_food_photo\":true,\"items\":[]}";

    private static String s3Endpoint() {
        return "http://%s:%d".formatted(s3mock.getHost(), s3mock.getMappedPort(9090));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("r2.endpoint", PhotoRecognitionJobTest::s3Endpoint);
        registry.add("r2.access-key-id", () -> "test");
        registry.add("r2.secret-access-key", () -> "test");
        registry.add("r2.photo-bucket", () -> BUCKET);

        mlServiceStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mlServiceStub.createContext("/v1/food/recognize", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = mlServiceBody.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(mlServiceStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        mlServiceStub.start();
        registry.add("ml.service-url", () -> "http://127.0.0.1:" + mlServiceStub.getAddress().getPort());
        registry.add("ml.internal-token", () -> "test-token");
        registry.add("job.retry-backoff-seconds", () -> 0);
        registry.add("job.scheduling-enabled", () -> false);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(s3Endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @AfterEach
    void resetStub() {
        mlServiceStatus = 200;
        mlServiceBody = "{\"is_food_photo\":true,\"items\":[]}";
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JobService jobService;

    @Autowired
    IntakePhotoRepository intakePhotoRepository;

    @Autowired
    FoodRecognizeJobHandler foodRecognizeJobHandler;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 업로드_완료_직후엔_job이_PENDING으로만_쌓이고_바로_응답한다() throws Exception {
        String accessToken = login();
        UUID photoId = uploadAndComplete(accessToken);

        Job job = findJobForPhoto(photoId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getType()).isEqualTo(FoodRecognizeJobHandler.JOB_TYPE);
    }

    @Test
    void 워커가_처리하면_ml_service_응답이_job_result에_저장된다() throws Exception {
        mlServiceBody = "{\"is_food_photo\":true,\"items\":[{\"name\":\"초콜릿\"}]}";
        String accessToken = login();
        UUID photoId = uploadAndComplete(accessToken);

        runWorkerOnce();

        Job job = findJobForPhoto(photoId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getResult()).contains("초콜릿");
    }

    @Test
    void ml_service가_500을_내려도_앱_요청엔_영향이_없고_job만_재시도되다가_FAILED가_된다() throws Exception {
        mlServiceStatus = 502;
        String accessToken = login();
        UUID photoId = uploadAndComplete(accessToken); // 이 호출은 ml-service를 전혀 기다리지 않음

        Job job = findJobForPhoto(photoId);
        for (int i = 0; i < job.getMaxAttempts(); i++) {
            runWorkerOnce();
        }

        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);

        // intake_photo 자체는 업로드 완료 상태로 멀쩟히 남아 있다 (job 실패가 업로드 완료를 되돌리지 않음)
        assertThat(intakePhotoRepository.findById(photoId).orElseThrow().getStatus()).isEqualTo(PhotoStatus.UPLOADED);
    }

    private void runWorkerOnce() {
        for (Job job : jobService.claimDueJobs()) {
            // 테스트에서는 핸들러를 실제 스프링 빈에서 찾아서 쓴다 (JobWorker와 동일한 책임)
            jobService.process(job, jobTypeToHandler(job.getType()));
        }
    }

    private JobHandler jobTypeToHandler(String type) {
        return FoodRecognizeJobHandler.JOB_TYPE.equals(type) ? foodRecognizeJobHandler : null;
    }

    private Job findJobForPhoto(UUID photoId) {
        // enqueue는 completeUpload와 같은 트랜잭션 안에서 일어나므로, 응답을 받은 시점엔 이미 커밋돼 있다.
        return jobRepository.findAll().stream()
                .filter(j -> j.getPayload().contains(photoId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("photo에 대한 job을 찾지 못함: " + photoId));
    }

    private String login() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/auth/apple", Map.of("identity_token", "valid:" + UUID.randomUUID()), Map.class);
        return (String) response.getBody().get("access_token");
    }

    private UUID uploadAndComplete(String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<Map> started = rest.exchange(
                "/v1/photos",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content_type", "image/jpeg", "context", "MEAL"), headers),
                Map.class);
        String uploadUrl = (String) started.getBody().get("upload_url");
        String photoId = (String) started.getBody().get("photo_id");

        httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                        .header("Content-Type", "image/jpeg")
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        ResponseEntity<Void> completed = rest.exchange(
                "/v1/photos/" + photoId + "/complete", HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        return UUID.fromString(photoId);
    }
}
