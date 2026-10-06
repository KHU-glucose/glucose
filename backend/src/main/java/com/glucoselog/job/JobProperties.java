package com.glucoselog.job;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job")
public record JobProperties(long pollIntervalMs, int batchSize, long retryBackoffSeconds) {
}
