package com.glucoselog.photo;

import static org.assertj.core.api.Assertions.assertThat;

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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
class PhotoFlowTest {

    private static final String BUCKET = "glucose-photos";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    // adobe/s3mock: 서명 검증 없이 S3 API를 흉내내는 테스트용 서버. MinIO는 최신 이미지가
    // Docker Hub에서 비공개로 전환되어(수동 확인: minio/minio, bitnami/minio 모두 pull 실패) 대신 사용한다.
    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest"))
            .withExposedPorts(9090);

    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";

    private static String endpoint() {
        return "http://%s:%d".formatted(s3mock.getHost(), s3mock.getMappedPort(9090));
    }

    @DynamicPropertySource
    static void r2Properties(DynamicPropertyRegistry registry) {
        registry.add("r2.endpoint", PhotoFlowTest::endpoint);
        registry.add("r2.access-key-id", () -> ACCESS_KEY);
        registry.add("r2.secret-access-key", () -> SECRET_KEY);
        registry.add("r2.photo-bucket", () -> BUCKET);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    IntakePhotoRepository intakePhotoRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 사진_업로드를_시작하고_실제로_올린_뒤_완료처리하면_UPLOADED가_된다() throws Exception {
        String accessToken = login("apple-sub-" + UUID.randomUUID());

        ResponseEntity<Map> started = startUpload(accessToken, "image/jpeg", "MEAL");
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String uploadUrl = (String) started.getBody().get("upload_url");
        String photoId = (String) started.getBody().get("photo_id");

        HttpResponse<Void> putResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                        .header("Content-Type", "image/jpeg")
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(putResponse.statusCode()).isEqualTo(200);

        ResponseEntity<Void> completed = complete(accessToken, photoId);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        IntakePhoto photo = intakePhotoRepository.findById(UUID.fromString(photoId)).orElseThrow();
        assertThat(photo.getStatus()).isEqualTo(PhotoStatus.UPLOADED);
        assertThat(photo.getUploadedAt()).isNotNull();
    }

    @Test
    void 실제로_올리지_않고_완료처리하면_409() {
        String accessToken = login("apple-sub-" + UUID.randomUUID());
        ResponseEntity<Map> started = startUpload(accessToken, "image/jpeg", null);
        String photoId = (String) started.getBody().get("photo_id");

        ResponseEntity<Map> completed = rest.exchange(
                "/v1/photos/" + photoId + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(accessToken)),
                Map.class);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(completed.getBody().get("code")).isEqualTo("PHOTO_NOT_UPLOADED");
    }

    @Test
    void 지원하지_않는_이미지_형식은_400() {
        String accessToken = login("apple-sub-" + UUID.randomUUID());

        ResponseEntity<Map> response = startUpload(accessToken, "image/gif", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 잘못된_context_값은_400() {
        String accessToken = login("apple-sub-" + UUID.randomUUID());

        ResponseEntity<Map> response = rest.exchange(
                "/v1/photos",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content_type", "image/jpeg", "context", "NOT_A_CONTEXT"), authHeaders(accessToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 다른_사용자의_사진을_완료처리하면_404() {
        String ownerToken = login("apple-sub-" + UUID.randomUUID());
        String otherToken = login("apple-sub-" + UUID.randomUUID());
        ResponseEntity<Map> started = startUpload(ownerToken, "image/jpeg", null);
        String photoId = (String) started.getBody().get("photo_id");

        ResponseEntity<Map> response = rest.exchange(
                "/v1/photos/" + photoId + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(otherToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("PHOTO_NOT_FOUND");
    }

    @Test
    void 인증_없이_업로드_시작은_401() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/photos", Map.of("content_type", "image/jpeg"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String login(String sub) {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/auth/apple", Map.of("identity_token", "valid:" + sub), Map.class);
        return (String) response.getBody().get("access_token");
    }

    private ResponseEntity<Map> startUpload(String accessToken, String contentType, String context) {
        var body = new java.util.HashMap<String, Object>();
        body.put("content_type", contentType);
        if (context != null) {
            body.put("context", context);
        }
        return rest.exchange(
                "/v1/photos", HttpMethod.POST, new HttpEntity<>(body, authHeaders(accessToken)), Map.class);
    }

    private ResponseEntity<Void> complete(String accessToken, String photoId) {
        return rest.exchange(
                "/v1/photos/" + photoId + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(accessToken)),
                Void.class);
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
