package com.glucoselog.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * job.scheduling-enabled=false로 끌 수 있다. 통합 테스트가 job을 수동으로 클레임·처리할 때
 * 실제 백그라운드 워커가 동시에 돌면서 레이스 컨디션을 만드는 것을 막기 위함
 * (테스트 job 타입은 핸들러가 없어 워커가 바로 FAILED 처리해버린다).
 */
@Configuration
@ConditionalOnProperty(name = "job.scheduling-enabled", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
