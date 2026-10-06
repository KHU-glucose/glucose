package com.glucoselog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.glucoselog.common.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/** Apple의 JWKS 엔드포인트를 로컬에 흉내 내어 서명 검증 로직을 테스트한다. */
class AppleIdentityTokenVerifierImplTest {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String BUNDLE_ID = "com.example.glucose";
    private static final String KEY_ID = "test-key-1";

    private HttpServer server;
    private RSAPrivateKey privateKey;
    private AppleIdentityTokenVerifierImpl verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        RSAKey jwk = new RSAKey.Builder(publicKey).keyID(KEY_ID).build();
        String jwksBody = "{\"keys\":[" + jwk.toJSONString() + "]}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> {
            byte[] bytes = jwksBody.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String keysUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/keys";
        verifier = new AppleIdentityTokenVerifierImpl(new AppleAuthProperties(BUNDLE_ID, ISSUER, keysUrl));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 올바른_토큰은_sub를_반환한다() throws Exception {
        String token = sign(claims("user-sub-1", ISSUER, BUNDLE_ID, Instant.now().plusSeconds(600), null));

        AppleIdentity identity = verifier.verify(token, null);

        assertThat(identity.sub()).isEqualTo("user-sub-1");
    }

    @Test
    void nonce가_일치하면_통과한다() throws Exception {
        String token = sign(claims("user-sub-2", ISSUER, BUNDLE_ID, Instant.now().plusSeconds(600), "nonce-abc"));

        AppleIdentity identity = verifier.verify(token, "nonce-abc");

        assertThat(identity.sub()).isEqualTo("user-sub-2");
    }

    @Test
    void nonce가_다르면_거부한다() throws Exception {
        String token = sign(claims("user-sub-3", ISSUER, BUNDLE_ID, Instant.now().plusSeconds(600), "nonce-abc"));

        assertThatThrownBy(() -> verifier.verify(token, "nonce-xyz"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 만료된_토큰은_거부한다() throws Exception {
        String token = sign(claims("user-sub-4", ISSUER, BUNDLE_ID, Instant.now().minusSeconds(60), null));

        assertThatThrownBy(() -> verifier.verify(token, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aud가_다르면_거부한다() throws Exception {
        String token = sign(claims("user-sub-5", ISSUER, "other.bundle.id", Instant.now().plusSeconds(600), null));

        assertThatThrownBy(() -> verifier.verify(token, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void issuer가_다르면_거부한다() throws Exception {
        String token = sign(claims("user-sub-6", "https://evil.example.com", BUNDLE_ID, Instant.now().plusSeconds(600), null));

        assertThatThrownBy(() -> verifier.verify(token, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 다른_키로_서명된_토큰은_거부한다() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey otherPrivateKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();

        JWTClaimsSet claims = claims("user-sub-7", ISSUER, BUNDLE_ID, Instant.now().plusSeconds(600), null);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
        jwt.sign(new RSASSASigner(otherPrivateKey));

        assertThatThrownBy(() -> verifier.verify(jwt.serialize(), null))
                .isInstanceOf(ApiException.class);
    }

    private JWTClaimsSet claims(String sub, String issuer, String audience, Instant expiry, String nonce) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(issuer)
                .audience(List.of(audience))
                .expirationTime(Date.from(expiry));
        if (nonce != null) {
            builder.claim("nonce", nonce);
        }
        return builder.build();
    }

    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }
}
