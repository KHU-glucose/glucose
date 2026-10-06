package com.glucoselog.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.glucoselog.auth.AuthTestConfig;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
@TestPropertySource(properties = "job.scheduling-enabled=false")
class IntakeFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    TestRestTemplate rest;

    @Test
    void 카탈로그에_있는_음식은_override_없이_분류와_당류가_채워진다() {
        String token = login();

        Map<String, Object> body = Map.of(
                "context", "HYPO_TREATMENT",
                "occurred_at", "2026-10-01T08:00:00Z",
                "items", List.of(Map.of("name", "초콜릿", "count", 3, "unit", "조각")));

        ResponseEntity<Map> response = rest.exchange(
                "/v1/intakes", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> item = firstItem(response);
        assertThat(item.get("category_hint")).isEqualTo("FAST_SUGAR");
        assertThat((List<String>) item.get("tags")).containsExactly("HIGH_FAT");
        assertThat(((Number) item.get("sugar_grams")).doubleValue()).isEqualTo(12.0);
    }

    @Test
    void 사용자가_분류를_직접_주면_카탈로그보다_우선한다() {
        String token = login();

        Map<String, Object> body = Map.of(
                "context", "MEAL",
                "occurred_at", "2026-10-01T08:00:00Z",
                "items", List.of(Map.of(
                        "name", "초콜릿", "count", 1, "unit", "조각",
                        "category_hint", "MEAL", "tags", List.of())));

        ResponseEntity<Map> response = rest.exchange(
                "/v1/intakes", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);

        Map<String, Object> item = firstItem(response);
        assertThat(item.get("category_hint")).isEqualTo("MEAL"); // 카탈로그는 FAST_SUGAR인데 사용자 값이 이겼다
        assertThat((List<?>) item.get("tags")).isEmpty();
        // 당류는 항상 카탈로그 기준(코드 계산)이라 override와 무관하게 그대로 채워진다
        assertThat(((Number) item.get("sugar_grams")).doubleValue()).isEqualTo(12.0);
    }

    @Test
    void 카탈로그에_없는_음식은_분류와_당류가_비어있다() {
        String token = login();

        Map<String, Object> body = Map.of(
                "context", "MEAL",
                "occurred_at", "2026-10-01T08:00:00Z",
                "items", List.of(Map.of("name", "알수없는음식_" + UUID.randomUUID(), "count", 1)));

        ResponseEntity<Map> response = rest.exchange(
                "/v1/intakes", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);

        Map<String, Object> item = firstItem(response);
        assertThat(item.get("category_hint")).isNull();
        assertThat(item.get("sugar_grams")).isNull();
    }

    @Test
    void 수정하면_항목이_교체된다() {
        String token = login();
        String intakeId = createIntake(token, "초콜릿");

        Map<String, Object> patchBody = Map.of(
                "context", "MEAL",
                "occurred_at", "2026-10-01T09:00:00Z",
                "items", List.of(Map.of("name", "짜장면", "count", 1, "unit", "그릇")));
        ResponseEntity<Map> patched = rest.exchange(
                "/v1/intakes/" + intakeId, HttpMethod.PATCH, new HttpEntity<>(patchBody, authHeaders(token)), Map.class);

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) patched.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("name")).isEqualTo("짜장면");
    }

    @Test
    void 삭제하면_다시_조회되지_않는다() {
        String token = login();
        String intakeId = createIntake(token, "초콜릿");

        ResponseEntity<Void> deleted = rest.exchange(
                "/v1/intakes/" + intakeId, HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getAfterDelete = rest.exchange(
                "/v1/intakes/" + intakeId, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 다른_사용자의_기록은_404() {
        String ownerToken = login();
        String otherToken = login();
        String intakeId = createIntake(ownerToken, "초콜릿");

        ResponseEntity<Map> response = rest.exchange(
                "/v1/intakes/" + intakeId, HttpMethod.GET, new HttpEntity<>(authHeaders(otherToken)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 목록은_cursor로_최신순_페이지네이션된다() {
        String token = login();
        createIntakeAt(token, "2026-10-01T06:00:00Z");
        createIntakeAt(token, "2026-10-01T07:00:00Z");
        createIntakeAt(token, "2026-10-01T08:00:00Z");

        ResponseEntity<Map> firstPage = rest.exchange(
                "/v1/intakes?limit=2", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) firstPage.getBody().get("items");
        assertThat(firstItems).hasSize(2);
        assertThat(firstItems.get(0).get("occurred_at").toString()).contains("08:00:00");
        String nextCursor = (String) firstPage.getBody().get("next_cursor");
        assertThat(nextCursor).isNotNull();

        ResponseEntity<Map> secondPage = rest.exchange(
                "/v1/intakes?limit=2&cursor=" + nextCursor,
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
        List<Map<String, Object>> secondItems = (List<Map<String, Object>>) secondPage.getBody().get("items");
        assertThat(secondItems).hasSize(1);
        assertThat(secondItems.get(0).get("occurred_at").toString()).contains("06:00:00");
        assertThat(secondPage.getBody().get("next_cursor")).isNull();
    }

    @Test
    void 인증_없이_생성은_401() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/intakes",
                Map.of("context", "MEAL", "occurred_at", "2026-10-01T08:00:00Z",
                        "items", List.of(Map.of("name", "초콜릿"))),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String createIntake(String token, String foodName) {
        Map<String, Object> body = Map.of(
                "context", "MEAL",
                "occurred_at", "2026-10-01T08:00:00Z",
                "items", List.of(Map.of("name", foodName, "count", 1)));
        ResponseEntity<Map> response = rest.exchange(
                "/v1/intakes", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);
        return (String) response.getBody().get("id");
    }

    private void createIntakeAt(String token, String occurredAt) {
        Map<String, Object> body = Map.of(
                "context", "MEAL",
                "occurred_at", occurredAt,
                "items", List.of(Map.of("name", "초콜릿", "count", 1)));
        rest.exchange("/v1/intakes", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    private Map<String, Object> firstItem(ResponseEntity<Map> response) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        return items.get(0);
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
