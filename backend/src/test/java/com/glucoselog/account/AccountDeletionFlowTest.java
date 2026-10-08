package com.glucoselog.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
import com.glucoselog.job.Job;
import com.glucoselog.job.JobRepository;
import com.glucoselog.job.JobService;
import com.glucoselog.job.JobStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/** 계정 삭제 시 DB뿐 아니라 job 기록과 R2 사진·그래프 파일까지 지워지는지, 다른 사용자 것은 남는지 확인한다. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
class AccountDeletionFlowTest {

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("r2.endpoint", AccountDeletionFlowTest::s3Endpoint);
        registry.add("r2.access-key-id", () -> "test");
        registry.add("r2.secret-access-key", () -> "test");
        registry.add("r2.photo-bucket", () -> BUCKET);
        registry.add("job.scheduling-enabled", () -> false);
    }

    private static S3Client s3;

    @BeforeAll
    static void createBucket() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create(s3Endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JobService jobService;

    @Autowired
    UserStoragePurgeJobHandler purgeJobHandler;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 계정을_삭제하면_job_기록과_R2_파일까지_지워지고_다른_사용자_파일은_남는다() throws Exception {
        String token = login();
        String userId = userIdOf(token);
        String photoId = upload(token, "/v1/photos", Map.of("content_type", "image/jpeg", "context", "SNACK"), "photo_id", true);
        String graphId = upload(token, "/v1/glucose-graphs", Map.of("content_type", "image/png"), "upload_id", true);
        upload(token, "/v1/photos", Map.of("content_type", "image/jpeg"), "photo_id", false); // 완료 통보 안 한 파일

        String otherToken = login();
        String otherUserId = userIdOf(otherToken);
        upload(otherToken, "/v1/photos", Map.of("content_type", "image/jpeg"), "photo_id", false);

        assertThat(jobsMentioning(photoId)).hasSize(1);
        assertThat(jobsMentioning(graphId)).hasSize(1);
        assertThat(objectCount("photos/" + userId + "/")).isEqualTo(2);
        assertThat(objectCount("graphs/" + userId + "/")).isEqualTo(1);

        ResponseEntity<Void> deleted = rest.exchange(
                "/v1/me", HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 사진·그래프에 연결된 job은 같은 트랜잭션에서 지워지고, 파일 정리 job이 대기 중
        assertThat(jobsMentioning(photoId)).isEmpty();
        assertThat(jobsMentioning(graphId)).isEmpty();
        Job purge = jobsMentioning(userId).stream()
                .filter(job -> job.getType().equals(UserStoragePurgeJobHandler.JOB_TYPE))
                .findFirst().orElseThrow();
        assertThat(purge.getStatus()).isEqualTo(JobStatus.PENDING);

        for (Job job : jobService.claimDueJobs()) {
            jobService.process(job, UserStoragePurgeJobHandler.JOB_TYPE.equals(job.getType()) ? purgeJobHandler : null);
        }

        Job done = jobRepository.findById(purge.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getResult()).contains("\"deleted_objects\":3");
        assertThat(objectCount("photos/" + userId + "/")).isZero();
        assertThat(objectCount("graphs/" + userId + "/")).isZero();
        assertThat(objectCount("photos/" + otherUserId + "/")).isEqualTo(1);
    }

    private String upload(String token, String path, Map<String, String> body, String idField, boolean complete)
            throws Exception {
        ResponseEntity<Map> started = rest.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);
        String id = (String) started.getBody().get(idField);
        httpClient.send(
                HttpRequest.newBuilder(URI.create((String) started.getBody().get("upload_url")))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                        .header("Content-Type", body.get("content_type"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        if (complete) {
            ResponseEntity<Void> completed = rest.exchange(
                    path + "/" + id + "/complete", HttpMethod.POST, new HttpEntity<>(authHeaders(token)), Void.class);
            assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
        return id;
    }

    private String userIdOf(String token) throws Exception {
        // 사진 키 "photos/{userId}/..."에서 사용자 id를 꺼낸다 (테스트용 - API에 사용자 id를 돌려주는 곳이 없다)
        ResponseEntity<Map> started = rest.exchange(
                "/v1/photos", HttpMethod.POST,
                new HttpEntity<>(Map.of("content_type", "image/jpeg"), authHeaders(token)), Map.class);
        return ((String) started.getBody().get("object_key")).split("/")[1];
    }

    private List<Job> jobsMentioning(String id) {
        return jobRepository.findAll().stream().filter(job -> job.getPayload().contains(id)).toList();
    }

    private int objectCount(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build()).keyCount();
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
