package com.glucoselog.account;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobHandler;
import com.glucoselog.job.RetryableJobException;
import com.glucoselog.photo.PhotoStorageService;

/** 계정 삭제 후 R2에 남은 그 사용자의 사진·그래프 파일을 경로(prefix) 단위로 모두 지운다.
 * 업로드만 하고 완료 통보를 안 한 파일까지 포함된다. */
@Component
public class UserStoragePurgeJobHandler implements JobHandler {

    public static final String JOB_TYPE = "USER_STORAGE_PURGE";

    private final PhotoStorageService storageService;
    private final ObjectMapper objectMapper;

    public UserStoragePurgeJobHandler(PhotoStorageService storageService, ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public String handle(Job job) throws Exception {
        Map<?, ?> payload = objectMapper.readValue(job.getPayload(), Map.class);
        UUID userId = UUID.fromString((String) payload.get("user_id"));

        int deleted;
        try {
            // 키 형식: PhotoUploadService "photos/{userId}/...", GlucoseGraphUploadService "graphs/{userId}/..."
            deleted = storageService.deleteByPrefix("photos/" + userId + "/")
                    + storageService.deleteByPrefix("graphs/" + userId + "/");
        } catch (RuntimeException e) {
            throw new RetryableJobException("R2 파일 정리 실패", e);
        }
        return objectMapper.writeValueAsString(Map.of("deleted_objects", deleted));
    }
}
