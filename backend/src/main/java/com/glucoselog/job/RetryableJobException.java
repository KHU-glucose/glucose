package com.glucoselog.job;

/** 핸들러가 이 예외를 던지면 재시도 대상(백오프 후 PENDING)이 된다. 그 외 예외는 재시도 없이 바로 FAILED. */
public class RetryableJobException extends RuntimeException {

    public RetryableJobException(String message) {
        super(message);
    }

    public RetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
