package com.glucoselog.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apple")
public record AppleAuthProperties(String bundleId, String issuer, String keysUrl) {
}
