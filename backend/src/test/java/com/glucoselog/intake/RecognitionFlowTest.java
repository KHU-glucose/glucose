package com.glucoselog.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

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
import com.glucoselog.job.JobService;
import com.glucoselog.photo.FoodRecognizeJobHandler;
import com.sun.net.httpserver.HttpServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/** B3의 job.result가 B4의 GET /v1/photos/{id}/recognition으로 어떻게 보이는지 확인한다. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
class RecognitionFlowTest {

    private static final String BUCKET = "glucose-photos";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest"))
            .withExposedPorts(9090);

    private static String s3Endpoint() {
        return "http://%s:%d".formatted(s3mock.getHost(), s3mock.getMappedPort(9090));
    }

    private static HttpServer mlServiceStub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("r2.endpoint", RecognitionFlowTest::s3Endpoint);
        registry.add("r2.access-key-id", () -> "test");
        registry.add("r2.secret-access-key", () -> "test");
        registry.add("r2.photo-bucket", () -> BUCKET);
        registry.add("job.scheduling-enabled", () -> false);
        registry.add("job.retry-backoff-seconds", () -> 0);

        mlServiceStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mlServiceStub.createContext("/v1/food/recognize", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"is_food_photo\":true,\"items\":[]}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        mlServiceStub.start();
        registry.add("ml.service-url", () -> "http://127.0.0.1:" + mlServiceStub.getAddress().getPort());
        registry.add("ml.internal-token", () -> "test-token");
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

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JobService jobService;

    @Autowired
    FoodRecognizeJobHandler foodRecognizeJobHandler;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 업로드만_하고_완료처리_전이면_409() {
        String token = login();
        String photoId = startUpload(token);

        ResponseEntity<Map> response = getRecognition(token, photoId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RECOGNITION_NOT_READY");
    }

    @Test
    void 존재하지_않는_사진은_404() {
        String token = login();

        ResponseEntity<Map> response = getRecognition(token, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 완료처리했지만_워커가_아직_처리안했으면_409() throws Exception {
        String token = login();
        String photoId = uploadAndComplete(token);

        ResponseEntity<Map> response = getRecognition(token, photoId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RECOGNITION_NOT_READY");
    }

    @Test
    void 워커가_처리완료하면_200과_인식결과를_돌려준다() throws Exception {
        String token = login();
        String photoId = uploadAndComplete(token);
        runWorkerOnce();

        ResponseEntity<Map> response = getRecognition(token, photoId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("is_food_photo")).isEqualTo(true);
    }

    @Test
    void 다른_사용자의_사진은_404() throws Exception {
        String ownerToken = login();
        String otherToken = login();
        String photoId = uploadAndComplete(ownerToken);

        ResponseEntity<Map> response = getRecognition(otherToken, photoId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void runWorkerOnce() {
        for (var job : jobService.claimDueJobs()) {
            jobService.process(job, FoodRecognizeJobHandler.JOB_TYPE.equals(job.getType()) ? foodRecognizeJobHandler : null);
        }
    }

    private ResponseEntity<Map> getRecognition(String token, String photoId) {
        return rest.exchange(
                "/v1/photos/" + photoId + "/recognition",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
    }

    private String startUpload(String token) {
        ResponseEntity<Map> started = rest.exchange(
                "/v1/photos", HttpMethod.POST,
                new HttpEntity<>(Map.of("content_type", "image/jpeg"), authHeaders(token)), Map.class);
        return (String) started.getBody().get("photo_id");
    }

    private String uploadAndComplete(String token) throws Exception {
        ResponseEntity<Map> started = rest.exchange(
                "/v1/photos", HttpMethod.POST,
                new HttpEntity<>(Map.of("content_type", "image/jpeg"), authHeaders(token)), Map.class);
        String uploadUrl = (String) started.getBody().get("upload_url");
        String photoId = (String) started.getBody().get("photo_id");

        httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                        .header("Content-Type", "image/jpeg")
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        rest.exchange("/v1/photos/" + photoId + "/complete", HttpMethod.POST, new HttpEntity<>(authHeaders(token)), Void.class);
        return photoId;
    }

    private String login() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/auth/apple", Map.of("identity_token", "valid:" + UUID.randomUUID()), Map.class);
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
