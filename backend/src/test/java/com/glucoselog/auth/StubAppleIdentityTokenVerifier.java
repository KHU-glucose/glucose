package com.glucoselog.auth;

import org.springframework.http.HttpStatus;

import com.glucoselog.common.ApiException;

/** 테스트용 Apple 검증기. "valid:<sub>" 형태의 토큰만 통과시킨다. */
public class StubAppleIdentityTokenVerifier implements AppleIdentityTokenVerifier {

    @Override
    public AppleIdentity verify(String identityToken, String expectedNonce) {
        if (identityToken == null || !identityToken.startsWith("valid:")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_APPLE_TOKEN", "유효하지 않은 Apple 토큰입니다");
        }
        String sub = identityToken.substring("valid:".length());
        return new AppleIdentity(sub, null);
    }
}
