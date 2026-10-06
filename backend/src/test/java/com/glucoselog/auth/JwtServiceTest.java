package com.glucoselog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.glucoselog.common.ApiException;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            new JwtProperties("test-secret-at-least-32-bytes-long!!", 900, 30));

    @Test
    void 발급한_액세스_토큰에서_사용자_id를_되돌려_받는다() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.issueAccessToken(userId);

        assertThat(jwtService.parseAccessToken(token)).isEqualTo(userId);
    }

    @Test
    void 만료된_토큰은_거부한다() {
        JwtService shortLived = new JwtService(
                new JwtProperties("test-secret-at-least-32-bytes-long!!", -1, 30));
        String token = shortLived.issueAccessToken(UUID.randomUUID());

        assertThatThrownBy(() -> shortLived.parseAccessToken(token))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 변조된_토큰은_거부한다() {
        String token = jwtService.issueAccessToken(UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwtService.parseAccessToken(tampered))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 다른_비밀값으로_서명된_토큰은_거부한다() {
        JwtService otherSecret = new JwtService(
                new JwtProperties("completely-different-secret-32-bytes!!", 900, 30));
        String token = otherSecret.issueAccessToken(UUID.randomUUID());

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void refresh_토큰_해시는_같은_입력에_대해_항상_같다() {
        String raw = jwtService.generateRefreshToken();

        assertThat(jwtService.hashRefreshToken(raw)).isEqualTo(jwtService.hashRefreshToken(raw));
    }

    @Test
    void refresh_토큰은_매번_다르게_생성된다() {
        assertThat(jwtService.generateRefreshToken()).isNotEqualTo(jwtService.generateRefreshToken());
    }
}
