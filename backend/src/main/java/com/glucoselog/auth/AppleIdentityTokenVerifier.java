package com.glucoselog.auth;

/**
 * Sign in with Apple identity token 검증. 운영 구현은 Apple의 공개키(JWKS)로 서명을 검증한다.
 * 인터페이스로 분리해 테스트에서는 mock 구현으로 교체한다.
 */
public interface AppleIdentityTokenVerifier {

    AppleIdentity verify(String identityToken, String expectedNonce);
}
