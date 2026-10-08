package com.glucoselog.auth;

import static org.assertj.core.api.Assertions.assertThat;

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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthTestConfig.class)
@TestPropertySource(properties = "job.scheduling-enabled=false")
class AuthFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AppUserRepository appUserRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Test
    void 애플_로그인은_사용자를_만들고_토큰을_발급한다() {
        String sub = "apple-sub-" + UUID.randomUUID();

        ResponseEntity<Map> response = loginAs(sub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("access_token", "refresh_token", "token_type", "expires_in");
        assertThat(appUserRepository.findByAppleSub(sub)).isPresent();
    }

    @Test
    void 같은_apple_sub로_다시_로그인하면_사용자가_늘지_않는다() {
        String sub = "apple-sub-" + UUID.randomUUID();

        loginAs(sub);
        loginAs(sub);

        long count = appUserRepository.findAll().stream().filter(u -> u.getAppleSub().equals(sub)).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 유효하지_않은_애플_토큰은_401() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/auth/apple", Map.of("identity_token", "invalid-token"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_APPLE_TOKEN");
    }

    @Test
    void refresh_토큰으로_새_토큰을_발급받는다() {
        Map<String, Object> tokens = loginAs("apple-sub-" + UUID.randomUUID()).getBody();

        ResponseEntity<Map> refreshed = rest.postForEntity(
                "/v1/auth/refresh", Map.of("refresh_token", tokens.get("refresh_token")), Map.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().get("refresh_token")).isNotEqualTo(tokens.get("refresh_token"));
    }

    @Test
    void 이미_회전된_refresh_토큰을_재사용하면_모든_세션이_폐기된다() {
        Map<String, Object> first = loginAs("apple-sub-" + UUID.randomUUID()).getBody();
        String oldRefresh = (String) first.get("refresh_token");

        // 정상 회전: old → new1
        ResponseEntity<Map> rotated = rest.postForEntity(
                "/v1/auth/refresh", Map.of("refresh_token", oldRefresh), Map.class);
        String newRefresh = (String) rotated.getBody().get("refresh_token");

        // 폐기된 old를 다시 사용 → 재사용 감지, 전체 세션 폐기
        ResponseEntity<Map> reused = rest.postForEntity(
                "/v1/auth/refresh", Map.of("refresh_token", oldRefresh), Map.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody().get("code")).isEqualTo("REFRESH_TOKEN_REUSED");

        // new1도 이제 쓸 수 없다
        ResponseEntity<Map> usingNewAfterReuse = rest.postForEntity(
                "/v1/auth/refresh", Map.of("refresh_token", newRefresh), Map.class);
        assertThat(usingNewAfterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 존재하지_않는_refresh_토큰은_401() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/v1/auth/refresh", Map.of("refresh_token", "no-such-token"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void 토큰_없이_me_삭제는_401() {
        ResponseEntity<Map> response = rest.exchange("/v1/me", HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 위조된_토큰으로_me_삭제는_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-real-jwt");

        ResponseEntity<Map> response = rest.exchange(
                "/v1/me", HttpMethod.DELETE, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void me_삭제하면_사용자와_refresh_토큰이_모두_삭제된다() {
        String sub = "apple-sub-" + UUID.randomUUID();
        Map<String, Object> tokens = loginAs(sub).getBody();
        String accessToken = (String) tokens.get("access_token");
        UUID userId = appUserRepository.findByAppleSub(sub).orElseThrow().getId();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Void> response = rest.exchange(
                "/v1/me", HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(appUserRepository.findByAppleSub(sub)).isEmpty();
        assertThat(refreshTokenRepository.findAll()).noneMatch(rt -> rt.getUserId().equals(userId));
    }

    private ResponseEntity<Map> loginAs(String sub) {
        return rest.postForEntity("/v1/auth/apple", Map.of("identity_token", "valid:" + sub), Map.class);
    }
}
