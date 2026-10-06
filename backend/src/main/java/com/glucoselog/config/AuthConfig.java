package com.glucoselog.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.glucoselog.auth.AppleAuthProperties;
import com.glucoselog.auth.JwtProperties;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, AppleAuthProperties.class})
public class AuthConfig {
}
