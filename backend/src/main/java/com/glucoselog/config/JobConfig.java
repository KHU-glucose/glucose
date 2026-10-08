package com.glucoselog.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.glucoselog.job.JobProperties;
import com.glucoselog.ml.MlServiceProperties;

@Configuration
@EnableConfigurationProperties({JobProperties.class, MlServiceProperties.class})
public class JobConfig {
}
