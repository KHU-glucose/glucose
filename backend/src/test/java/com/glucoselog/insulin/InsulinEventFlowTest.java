package com.glucoselog.insulin;

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
class InsulinEventFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    TestRestTemplate rest;

    @Test
    void 생성하고_조회할_수_있다() {
        String token = login();

        ResponseEntity<Map> created = create(token, "2026-10-01T08:00:00Z", 6, "식사");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) created.getBody().get("units")).doubleValue()).isEqualTo(6.0);
        assertThat(created.getBody().get("kind")).isEqualTo("식사");
    }

    @Test
    void 수정할_수_있다() {
        String token = login();
        String id = (String) create(token, "2026-10-01T08:00:00Z", 6, "식사").getBody().get("id");

        ResponseEntity<Map> updated = rest.exchange(
                "/v1/insulin-events/" + id, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("occurred_at", "2026-10-01T09:00:00Z", "units", 8, "kind", "기저"), authHeaders(token)),
                Map.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) updated.getBody().get("units")).doubleValue()).isEqualTo(8.0);
        assertThat(updated.getBody().get("kind")).isEqualTo("기저");
    }

    @Test
    void 삭제할_수_있다() {
        String token = login();
        String id = (String) create(token, "2026-10-01T08:00:00Z", 6, "식사").getBody().get("id");

        ResponseEntity<Void> deleted = rest.exchange(
                "/v1/insulin-events/" + id, HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> getAfterDelete = rest.exchange(
                "/v1/insulin-events/" + id, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 단위는_0보다_커야_한다() {
        String token = login();

        ResponseEntity<Map> response = create(token, "2026-10-01T08:00:00Z", 0, "식사");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 다른_사용자의_기록은_404() {
        String ownerToken = login();
        String otherToken = login();
        String id = (String) create(ownerToken, "2026-10-01T08:00:00Z", 6, "식사").getBody().get("id");

        ResponseEntity<Map> response = rest.exchange(
                "/v1/insulin-events/" + id, HttpMethod.GET, new HttpEntity<>(authHeaders(otherToken)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 목록은_최신순_cursor_페이지네이션된다() {
        String token = login();
        create(token, "2026-10-01T06:00:00Z", 1, "기저");
        create(token, "2026-10-01T07:00:00Z", 2, "기저");
        create(token, "2026-10-01T08:00:00Z", 3, "기저");

        ResponseEntity<Map> firstPage = rest.exchange(
                "/v1/insulin-events?limit=2", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) firstPage.getBody().get("items");
        assertThat(firstItems).hasSize(2);
        assertThat(((Number) firstItems.get(0).get("units")).doubleValue()).isEqualTo(3.0);

        String nextCursor = (String) firstPage.getBody().get("next_cursor");
        ResponseEntity<Map> secondPage = rest.exchange(
                "/v1/insulin-events?limit=2&cursor=" + nextCursor,
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), Map.class);
        List<Map<String, Object>> secondItems = (List<Map<String, Object>>) secondPage.getBody().get("items");
        assertThat(secondItems).hasSize(1);
        assertThat(((Number) secondItems.get(0).get("units")).doubleValue()).isEqualTo(1.0);
    }

    private ResponseEntity<Map> create(String token, String occurredAt, int units, String kind) {
        return rest.exchange(
                "/v1/insulin-events", HttpMethod.POST,
                new HttpEntity<>(Map.of("occurred_at", occurredAt, "units", units, "kind", kind), authHeaders(token)),
                Map.class);
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
