package com.glucoselog.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.glucoselog.episode.EpisodeProperties;

@Configuration
@EnableConfigurationProperties(EpisodeProperties.class)
public class EpisodeConfig {
}
