package com.glucoselog.auth;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.glucoselog.common.ApiException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Apple의 공개키(JWKS)를 내려받아 identity token의 서명·발급자·대상·만료·nonce를 검증한다. */
@Component
public class AppleIdentityTokenVerifierImpl implements AppleIdentityTokenVerifier {

    private static final Duration KEY_CACHE_TTL = Duration.ofHours(1);
    private static final String INVALID_CODE = "INVALID_APPLE_TOKEN";

    private final AppleAuthProperties properties;

    private volatile JWKSet cachedKeys;
    private volatile Instant cachedAt = Instant.EPOCH;

    public AppleIdentityTokenVerifierImpl(AppleAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public AppleIdentity verify(String identityToken, String expectedNonce) {
        try {
            SignedJWT jwt = SignedJWT.parse(identityToken);
            String keyId = jwt.getHeader().getKeyID();
            JWK key = findKey(keyId);
            if (key == null) {
                throw invalid("Apple 공개키를 찾을 수 없습니다");
            }

            RSAKey rsaKey = (RSAKey) key;
            JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
            if (!jwt.verify(verifier)) {
                throw invalid("서명이 올바르지 않습니다");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.issuer().equals(claims.getIssuer())) {
                throw invalid("발급자가 올바르지 않습니다");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(properties.bundleId())) {
                throw invalid("대상(aud)이 올바르지 않습니다");
            }
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                throw invalid("토큰이 만료되었습니다");
            }
            if (expectedNonce != null && !expectedNonce.equals(claims.getStringClaim("nonce"))) {
                throw invalid("nonce가 일치하지 않습니다");
            }

            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw invalid("sub 클레임이 없습니다");
            }
            String email = claims.getStringClaim("email");
            return new AppleIdentity(sub, email);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("Apple 토큰을 해석할 수 없습니다");
        }
    }

    private JWK findKey(String keyId) throws ParseException {
        JWKSet keys = currentKeys();
        JWK key = keyId != null ? keys.getKeyByKeyId(keyId) : null;
        if (key == null) {
            keys = refreshKeys();
            key = keyId != null ? keys.getKeyByKeyId(keyId) : null;
        }
        return key;
    }

    private synchronized JWKSet currentKeys() {
        if (cachedKeys == null || Instant.now().isAfter(cachedAt.plus(KEY_CACHE_TTL))) {
            return refreshKeys();
        }
        return cachedKeys;
    }

    private synchronized JWKSet refreshKeys() {
        try {
            cachedKeys = JWKSet.load(URI.create(properties.keysUrl()).toURL());
            cachedAt = Instant.now();
            return cachedKeys;
        } catch (Exception e) {
            throw invalid("Apple 공개키를 가져오지 못했습니다");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CODE, message);
    }
}
