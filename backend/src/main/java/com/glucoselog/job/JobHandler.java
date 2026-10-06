package com.glucoselog.job;

/** job.type 하나를 처리하는 핸들러. 실패 시 {@link RetryableJobException}을 던지면 백오프 후 재시도되고,
 * 그 외 예외는 재시도 없이 바로 FAILED 처리된다. */
public interface JobHandler {

    String jobType();

    String handle(Job job) throws Exception;
}
