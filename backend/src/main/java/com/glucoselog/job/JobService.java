package com.glucoselog.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final JobRepository jobRepository;
    private final JobProperties properties;
    private final ObjectMapper objectMapper;

    public JobService(JobRepository jobRepository, JobProperties properties, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 앱 요청 스레드에서 호출. ml-service를 기다리지 않고 즉시 리턴한다. */
    @Transactional
    public Job enqueue(String type, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("job payload를 직렬화할 수 없습니다", e);
        }
        return jobRepository.save(new Job(type, json, DEFAULT_MAX_ATTEMPTS));
    }

    /** FOR UPDATE SKIP LOCKED로 처리할 job을 가져오면서 동시에 PROCESSING으로 바꾼다(같은 트랜잭션). */
    @Transactional
    public List<Job> claimDueJobs() {
        List<Job> jobs = jobRepository.findDueForProcessing(properties.batchSize());
        Instant now = Instant.now();
        jobs.forEach(job -> job.markProcessing(now));
        return jobs;
    }

    /** 클레임된 job 하나를 처리한다. 이 job의 실패가 다른 job에 영향 주지 않도록 독립 트랜잭션으로 커밋한다. */
    @Transactional
    public void process(Job job, JobHandler handler) {
        Instant now = Instant.now();
        if (handler == null) {
            job.failPermanently("등록된 핸들러가 없는 job type: " + job.getType(), now);
            jobRepository.save(job);
            return;
        }
        try {
            String result = handler.handle(job);
            job.markDone(result, now);
        } catch (RetryableJobException e) {
            log.warn("job 처리 실패(재시도 대상) id={} type={} attempts={} message={}",
                    job.getId(), job.getType(), job.getAttempts() + 1, e.getMessage());
            job.recordFailure(e.getMessage(), now, Duration.ofSeconds(properties.retryBackoffSeconds()));
        } catch (Exception e) {
            log.error("job 처리 실패(재시도 없음) id={} type={}", job.getId(), job.getType(), e);
            job.failPermanently(e.getMessage(), now);
        }
        jobRepository.save(job);
    }
}
