package com.glucoselog.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class AuthTestConfig {

    @Bean
    @Primary
    AppleIdentityTokenVerifier appleIdentityTokenVerifier() {
        return new StubAppleIdentityTokenVerifier();
    }
}
