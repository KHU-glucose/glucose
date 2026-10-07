package com.glucoselog.report;

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
import com.glucoselog.glucose.GraphParseJobHandler;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobHandler;
import com.glucoselog.job.JobService;
import com.sun.net.httpserver.HttpServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/** intake + insulin_event + 혈당 그래프를 실제로 만든 뒤 일일·주간 리포트가 맞게 계산되는지 확인한다. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
class ReportFlowTest {

    private static final String BUCKET = "glucose-photos";

    private static final String GRAPH_BODY = """
            {
              "date": "2026-10-01",
              "source": "LIBRE_DAILY_GRAPH",
              "unit": "mg/dL",
              "interval_minutes": 15,
              "readings": [
                {"time": "06:00", "value": 120, "flag": "NORMAL"},
                {"time": "17:45", "value": 65, "flag": "BELOW_RANGE"},
                {"time": "19:00", "value": 210, "flag": "ABOVE_RANGE"}
              ],
              "gaps": [],
              "coverage_ratio": 0.5,
              "meta": {"parser_version": "1.0.0"}
            }
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static GenericContainer<?> s3mock = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:latest"))
            .withExposedPorts(9090);

    private static HttpServer mlServiceStub;

    private static String s3Endpoint() {
        return "http://%s:%d".formatted(s3mock.getHost(), s3mock.getMappedPort(9090));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        registry.add("r2.endpoint", ReportFlowTest::s3Endpoint);
        registry.add("r2.access-key-id", () -> "test");
        registry.add("r2.secret-access-key", () -> "test");
        registry.add("r2.photo-bucket", () -> BUCKET);

        mlServiceStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mlServiceStub.createContext("/v1/graph/parse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = GRAPH_BODY.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        mlServiceStub.start();
        registry.add("ml.service-url", () -> "http://127.0.0.1:" + mlServiceStub.getAddress().getPort());
        registry.add("ml.internal-token", () -> "test-token");
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

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JobService jobService;

    @Autowired
    GraphParseJobHandler graphParseJobHandler;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 저혈당_처치로_자동_재분류되고_반동까지_감지되면_리포트와_교육카드에_반영된다() throws Exception {
        String token = login();
        uploadGraphAndWaitForParse(token);

        // 18:00 KST(= 09:00Z) 간식 기록. 직전(17:45 KST) 혈당이 65라서 처치로 자동 재분류돼야 한다.
        createIntake(token, "SNACK", "2026-10-01T09:00:00Z", "포도당 캔디");
        createInsulinEvent(token, "2026-10-01T09:05:00Z", 3, "처치");

        ResponseEntity<Map> response = rest.exchange(
                "/v1/reports/daily/2026-10-01", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();

        Map<String, Object> glucose = (Map<String, Object>) body.get("glucose");
        assertThat(glucose.get("readings_count")).isEqualTo(3);
        assertThat(((Number) glucose.get("average")).doubleValue()).isEqualTo(131.7);
        assertThat(glucose.get("min")).isEqualTo(65);
        assertThat(glucose.get("max")).isEqualTo(210);

        List<Map<String, Object>> episodes = (List<Map<String, Object>>) body.get("episodes");
        assertThat(episodes).hasSize(1);
        Map<String, Object> episode = episodes.get(0);
        assertThat(episode.get("original_context")).isEqualTo("SNACK");
        assertThat(episode.get("effective_context")).isEqualTo("HYPO_TREATMENT");
        assertThat(episode.get("auto_reclassified")).isEqualTo(true);
        assertThat(episode.get("rebound_detected")).isEqualTo(true);
        assertThat(episode.get("intake_count")).isEqualTo(1);

        assertThat(body.get("insulin_events_count")).isEqualTo(1);

        List<Map<String, Object>> cards = (List<Map<String, Object>>) body.get("education_cards");
        assertThat(cards).extracting(c -> c.get("trigger")).containsExactly("REBOUND");
    }

    @Test
    void 그래프가_없는_날은_glucose가_null이고_LOW_COVERAGE_카드가_붙는다() {
        String token = login();

        createIntake(token, "MEAL", "2026-10-02T03:00:00Z", "현미밥");

        ResponseEntity<Map> response = rest.exchange(
                "/v1/reports/daily/2026-10-02", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);

        Map<String, Object> body = response.getBody();
        assertThat(body.get("glucose")).isNull();

        List<Map<String, Object>> episodes = (List<Map<String, Object>>) body.get("episodes");
        assertThat(episodes).hasSize(1);
        assertThat(episodes.get(0).get("auto_reclassified")).isNull(); // 판단 불가 - false가 아니라 null
        assertThat(episodes.get(0).get("rebound_detected")).isEqualTo(false); // MEAL은 처치가 아니므로 확정 false

        List<Map<String, Object>> cards = (List<Map<String, Object>>) body.get("education_cards");
        assertThat(cards).extracting(c -> c.get("trigger")).containsExactly("LOW_COVERAGE");
    }

    @Test
    void 주간_리포트는_그_주_안의_일일_리포트들을_모은다() throws Exception {
        String token = login();
        uploadGraphAndWaitForParse(token);
        createIntake(token, "SNACK", "2026-10-01T09:00:00Z", "포도당 캔디");
        createInsulinEvent(token, "2026-10-01T09:05:00Z", 3, "처치");

        ResponseEntity<Map> response = rest.exchange(
                "/v1/reports/weekly/2026-10-01", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("days_with_data")).isEqualTo(1);
        assertThat(body.get("days_insufficient")).isEqualTo(6);
        assertThat(((Number) body.get("average_glucose")).doubleValue()).isEqualTo(131.7);
        assertThat(body.get("episodes_count")).isEqualTo(1);
        assertThat(body.get("rebound_count")).isEqualTo(1);
        assertThat(body.get("insulin_events_count")).isEqualTo(1);
    }

    private void uploadGraphAndWaitForParse(String token) throws Exception {
        ResponseEntity<Map> started = rest.exchange(
                "/v1/glucose-graphs",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content_type", "image/jpeg"), authHeaders(token)),
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
                "/v1/glucose-graphs/" + uploadId + "/complete",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(token)),
                Void.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        for (Job job : jobService.claimDueJobs()) {
            JobHandler handler = GraphParseJobHandler.JOB_TYPE.equals(job.getType()) ? graphParseJobHandler : null;
            jobService.process(job, handler);
        }
    }

    private void createIntake(String token, String context, String occurredAt, String foodName) {
        Map<String, Object> body = Map.of(
                "context", context,
                "occurred_at", occurredAt,
                "items", List.of(Map.of("name", foodName, "count", 1, "unit", "개")));
        ResponseEntity<Void> response = rest.exchange(
                "/v1/intakes", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void createInsulinEvent(String token, String occurredAt, int units, String kind) {
        Map<String, Object> body = Map.of("occurred_at", occurredAt, "units", units, "kind", kind);
        ResponseEntity<Void> response = rest.exchange(
                "/v1/insulin-events", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String login() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/auth/apple", Map.of("identity_token", "valid:" + UUID.randomUUID()), Map.class);
        return (String) response.getBody().get("access_token");
    }

    private HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
