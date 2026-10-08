package com.glucoselog.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.glucoselog.common.ApiException;

@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AppleIdentityTokenVerifier appleIdentityTokenVerifier;
    private final JwtService jwtService;

    public AuthService(
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            AppleIdentityTokenVerifier appleIdentityTokenVerifier,
            JwtService jwtService) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.appleIdentityTokenVerifier = appleIdentityTokenVerifier;
        this.jwtService = jwtService;
    }

    @Transactional
    public TokenPair loginWithApple(String identityToken, String nonce) {
        AppleIdentity identity = appleIdentityTokenVerifier.verify(identityToken, nonce);
        AppUser user = appUserRepository.findByAppleSub(identity.sub())
                .orElseGet(() -> appUserRepository.save(new AppUser(identity.sub())));
        return issueTokenPair(user.getId());
    }

    // ApiException은 재사용 감지 같은 정상 업무 흐름의 일부라, noRollbackFor로 앞서 실행한
    // revokeAllForUser 폐기 처리가 함께 롤백되지 않도록 한다.
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair refresh(String rawRefreshToken) {
        String hash = jwtService.hashRefreshToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "refresh 토큰을 찾을 수 없습니다"));

        Instant now = Instant.now();
        if (stored.isRevoked()) {
            // 이미 회전되어 폐기된 토큰이 다시 쓰였다 = 탈취 의심 → 이 사용자의 모든 세션을 끝낸다
            refreshTokenRepository.revokeAllForUser(stored.getUserId(), now);
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "REFRESH_TOKEN_REUSED",
                    "이미 사용된 refresh 토큰입니다. 모든 세션이 종료되었습니다");
        }
        if (stored.isExpired(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "EXPIRED_REFRESH_TOKEN", "refresh 토큰이 만료되었습니다");
        }

        stored.revoke(now);
        refreshTokenRepository.save(stored);
        return issueTokenPair(stored.getUserId());
    }

    private TokenPair issueTokenPair(UUID userId) {
        String accessToken = jwtService.issueAccessToken(userId);
        String rawRefreshToken = jwtService.generateRefreshToken();
        String hash = jwtService.hashRefreshToken(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(Duration.ofDays(jwtService.refreshTokenTtlDays()));
        refreshTokenRepository.save(new RefreshToken(userId, hash, expiresAt));
        return TokenPair.of(accessToken, rawRefreshToken, jwtService.accessTokenTtlSeconds());
    }
}
