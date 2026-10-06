package com.glucoselog.job;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobWorker {

    private final JobService jobService;
    private final Map<String, JobHandler> handlersByType;

    public JobWorker(JobService jobService, List<JobHandler> handlers) {
        this.jobService = jobService;
        this.handlersByType = handlers.stream().collect(Collectors.toMap(JobHandler::jobType, h -> h));
    }

    @Scheduled(fixedDelayString = "${job.poll-interval-ms}")
    public void pollAndProcess() {
        for (Job job : jobService.claimDueJobs()) {
            jobService.process(job, handlersByType.get(job.getType()));
        }
    }
}
