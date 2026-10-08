package com.glucoselog.glucose;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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

/** 그래프 업로드 완료 → GRAPH_PARSE job 적재 → 워커가 ml-service를 호출해 glucose_reading에 반영하는 전체 흐름. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
class GlucoseGraphFlowTest {

    private static final String BUCKET = "glucose-photos";

    private static final String SUCCESS_BODY = """
            {
              "date": "2026-10-01",
              "source": "LIBRE_DAILY_GRAPH",
              "unit": "mg/dL",
              "interval_minutes": 15,
              "readings": [
                {"time": "00:00", "value": 120, "flag": "NORMAL"},
                {"time": "00:15", "value": 125, "flag": "NORMAL"},
                {"time": "00:30", "value": null, "flag": "MISSING"}
              ],
              "gaps": [{"from": "00:30", "to": "00:45"}],
              "coverage_ratio": 0.94,
              "meta": {"parser_version": "1.0.0", "image_width": 1179, "image_height": 2029, "latency_ms": 320}
            }
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest"))
            .withExposedPorts(9090);

    private static HttpServer mlServiceStub;
    private static volatile int mlServiceStatus = 200;
    private static volatile String mlServiceBody = SUCCESS_BODY;

    private static String s3Endpoint() {
        return "http://%s:%d".formatted(s3mock.getHost(), s3mock.getMappedPort(9090));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("r2.endpoint", GlucoseGraphFlowTest::s3Endpoint);
        registry.add("r2.access-key-id", () -> "test");
        registry.add("r2.secret-access-key", () -> "test");
        registry.add("r2.photo-bucket", () -> BUCKET);

        mlServiceStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mlServiceStub.createContext("/v1/graph/parse", exchange -> {
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
        mlServiceBody = SUCCESS_BODY;
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JobService jobService;

    @Autowired
    GlucoseGraphUploadRepository uploadRepository;

    @Autowired
    GlucoseReadingRepository readingRepository;

    @Autowired
    GraphParseJobHandler graphParseJobHandler;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 업로드_완료_후_워커가_처리하면_readings가_저장되고_상태조회로_날짜와_coverage를_볼_수_있다() throws Exception {
        String accessToken = login();
        UUID uploadId = uploadAndComplete(accessToken);

        runWorkerOnce();

        Job job = findJobForUpload(uploadId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);

        ResponseEntity<Map> status = getStatus(accessToken, uploadId);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody().get("date")).isEqualTo("2026-10-01");

        List<GlucoseReading> readings = readingRepository.findByUploadIdOrderByTimeSlot(uploadId);
        assertThat(readings).hasSize(3);
        assertThat(readings.get(0).getValue()).isEqualTo(120);
        assertThat(readings.get(2).getValue()).isNull();
        assertThat(readings.get(2).getFlag()).isEqualTo(ReadingFlag.MISSING);

        ResponseEntity<Map> byDate = rest.exchange(
                "/v1/glucose-readings/2026-10-01",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                Map.class);
        assertThat(byDate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) byDate.getBody().get("readings")).hasSize(3);
    }

    @Test
    void 같은_날짜로_재업로드하면_기존_업로드는_지워지고_새_업로드로_덮어써진다() throws Exception {
        String accessToken = login();

        UUID firstUploadId = uploadAndComplete(accessToken);
        runWorkerOnce();
        assertThat(uploadRepository.findById(firstUploadId)).isPresent();

        UUID secondUploadId = uploadAndComplete(accessToken);
        runWorkerOnce();

        assertThat(uploadRepository.findById(firstUploadId)).isEmpty();
        assertThat(readingRepository.findByUploadIdOrderByTimeSlot(firstUploadId)).isEmpty();

        assertThat(uploadRepository.findById(secondUploadId)).isPresent();
        assertThat(readingRepository.findByUploadIdOrderByTimeSlot(secondUploadId)).hasSize(3);
    }

    @Test
    void 처리가_끝나기_전에_상태를_물으면_409() throws Exception {
        String accessToken = login();
        UUID uploadId = uploadAndComplete(accessToken);

        ResponseEntity<Map> status = getStatus(accessToken, uploadId);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status.getBody().get("code")).isEqualTo("GRAPH_NOT_READY");
    }

    @Test
    void ml_service가_422를_내리면_재시도_없이_바로_FAILED가_되고_상태조회는_422() throws Exception {
        mlServiceStatus = 422;
        mlServiceBody = "{\"code\":\"GRAPH_NOT_RECOGNIZED\",\"message\":\"격자선을 찾지 못했습니다\"}";
        String accessToken = login();
        UUID uploadId = uploadAndComplete(accessToken);

        runWorkerOnce();

        Job job = findJobForUpload(uploadId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(1); // 재시도 없이 바로 FAILED

        ResponseEntity<Map> status = getStatus(accessToken, uploadId);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(status.getBody().get("code")).isEqualTo("GRAPH_PARSE_FAILED");
    }

    @Test
    void 다른_사용자의_날짜_데이터는_404() {
        String accessToken = login();

        ResponseEntity<Map> response = rest.exchange(
                "/v1/glucose-readings/2026-01-01",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("GLUCOSE_DAY_NOT_FOUND");
    }

    private void runWorkerOnce() {
        for (Job job : jobService.claimDueJobs()) {
            jobService.process(job, jobTypeToHandler(job.getType()));
        }
    }

    private JobHandler jobTypeToHandler(String type) {
        return GraphParseJobHandler.JOB_TYPE.equals(type) ? graphParseJobHandler : null;
    }

    private Job findJobForUpload(UUID uploadId) {
        return jobRepository.findAll().stream()
                .filter(j -> j.getType().equals(GraphParseJobHandler.JOB_TYPE) && j.getPayload().contains(uploadId.toString()))
                .reduce((first, second) -> second) // 가장 최근 것
                .orElseThrow(() -> new AssertionError("업로드에 대한 job을 찾지 못함: " + uploadId));
    }

    private String login() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/auth/apple", Map.of("identity_token", "valid:" + UUID.randomUUID()), Map.class);
        return (String) response.getBody().get("access_token");
    }

    private ResponseEntity<Map> getStatus(String accessToken, UUID uploadId) {
        return rest.exchange(
                "/v1/glucose-graphs/" + uploadId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(accessToken)),
                Map.class);
    }

    private UUID uploadAndComplete(String accessToken) throws Exception {
        HttpHeaders headers = authHeaders(accessToken);

        ResponseEntity<Map> started = rest.exchange(
                "/v1/glucose-graphs",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content_type", "image/jpeg"), headers),
                Map.class);
        String uploadUrl = (String) started.getBody().get("upload_url");
        String uploadId = (String) started.getBody().get("upload_id");

        httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                        .header("Content-Type", "image/jpeg")
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        ResponseEntity<Void> completed = rest.exchange(
                "/v1/glucose-graphs/" + uploadId + "/complete", HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        return UUID.fromString(uploadId);
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
