package com.glucoselog.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {"job.retry-backoff-seconds=0", "job.scheduling-enabled=false"})
class JobServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    JobService jobService;

    @Autowired
    JobRepository jobRepository;

    @Test
    void 성공하면_DONE이_되고_결과가_저장된다() {
        Job job = jobService.enqueue("TEST_OK", Map.of("x", 1));

        Job claimed = claim(job.getId());
        jobService.process(claimed, handlerReturning("ok-result"));

        Job reloaded = reload(job.getId());
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(reloaded.getResult()).isEqualTo("ok-result");
    }

    @Test
    void 재시도_가능한_실패는_PENDING으로_돌아갔다가_시도를_다_쓰면_FAILED가_된다() {
        Job job = jobService.enqueue("TEST_RETRY", Map.of());
        JobHandler failing = handlerThrowing(new RetryableJobException("일시적 오류"));

        for (int i = 0; i < job.getMaxAttempts() - 1; i++) {
            Job claimed = claim(job.getId());
            assertThat(claimed.getStatus()).isEqualTo(JobStatus.PROCESSING);
            jobService.process(claimed, failing);
            assertThat(reload(job.getId()).getStatus()).isEqualTo(JobStatus.PENDING);
        }

        // 마지막 시도: 더 이상 재시도하지 않고 FAILED
        Job lastClaim = claim(job.getId());
        jobService.process(lastClaim, failing);

        Job reloaded = reload(job.getId());
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getAttempts()).isEqualTo(job.getMaxAttempts());
    }

    @Test
    void 재시도해도_의미없는_실패는_한_번에_FAILED가_된다() {
        Job job = jobService.enqueue("TEST_PERMANENT", Map.of());

        Job claimed = claim(job.getId());
        jobService.process(claimed, handlerThrowing(new IllegalStateException("잘못된 요청")));

        Job reloaded = reload(job.getId());
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }

    @Test
    void 등록된_핸들러가_없으면_즉시_FAILED가_된다() {
        Job job = jobService.enqueue("NO_SUCH_HANDLER", Map.of());

        Job claimed = claim(job.getId());
        jobService.process(claimed, null);

        assertThat(reload(job.getId()).getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void DONE된_job은_다시_클레임되지_않는다() {
        Job job = jobService.enqueue("TEST_ONCE", Map.of());
        jobService.process(claim(job.getId()), handlerReturning("done"));

        List<Job> claimedAgain = jobService.claimDueJobs();

        assertThat(claimedAgain).extracting(Job::getId).doesNotContain(job.getId());
    }

    private Job claim(UUID jobId) {
        return jobService.claimDueJobs().stream()
                .filter(j -> j.getId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("job이 클레임되지 않음: " + jobId));
    }

    private Job reload(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow();
    }

    private static JobHandler handlerReturning(String result) {
        return new JobHandler() {
            @Override
            public String jobType() {
                return "test";
            }

            @Override
            public String handle(Job job) {
                return result;
            }
        };
    }

    private static JobHandler handlerThrowing(RuntimeException exception) {
        return new JobHandler() {
            @Override
            public String jobType() {
                return "test";
            }

            @Override
            public String handle(Job job) {
                throw exception;
            }
        };
    }
}
