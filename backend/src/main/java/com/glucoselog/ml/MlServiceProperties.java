package com.glucoselog.ml;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ml")
public record MlServiceProperties(String serviceUrl, String internalToken, long connectTimeoutMs, long readTimeoutMs) {
}
