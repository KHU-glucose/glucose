package com.glucoselog.glucose;

import java.net.URL;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.glucoselog.common.ApiException;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobService;
import com.glucoselog.photo.PhotoStorageService;
import com.glucoselog.photo.R2Properties;

@Service
public class GlucoseGraphUploadService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final GlucoseGraphUploadRepository repository;
    private final PhotoStorageService storageService;
    private final R2Properties properties;
    private final JobService jobService;

    public GlucoseGraphUploadService(
            GlucoseGraphUploadRepository repository,
            PhotoStorageService storageService,
            R2Properties properties,
            JobService jobService) {
        this.repository = repository;
        this.storageService = storageService;
        this.properties = properties;
        this.jobService = jobService;
    }

    @Transactional
    public GlucoseGraphUploadResponse startUpload(UUID userId, String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "지원하지 않는 이미지 형식입니다 (JPEG/PNG만 가능)");
        }

        String objectKey = "graphs/%s/%s.%s".formatted(userId, UUID.randomUUID(), extensionOf(contentType));
        GlucoseGraphUpload upload = repository.save(new GlucoseGraphUpload(userId, objectKey, contentType));
        URL uploadUrl = storageService.presignPut(objectKey, contentType);

        return new GlucoseGraphUploadResponse(
                upload.getId(), uploadUrl.toString(), objectKey, properties.presignTtlSeconds());
    }

    @Transactional
    public void completeUpload(UUID userId, UUID uploadId) {
        GlucoseGraphUpload upload = repository.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GLUCOSE_GRAPH_NOT_FOUND", "그래프를 찾을 수 없습니다"));

        if (upload.getStatus() == GlucoseGraphUploadStatus.UPLOADED) {
            return; // 이미 완료 처리됨 (멱등)
        }
        if (!storageService.exists(upload.getObjectKey())) {
            throw new ApiException(HttpStatus.CONFLICT, "GLUCOSE_GRAPH_NOT_UPLOADED", "업로드가 아직 완료되지 않았습니다");
        }

        upload.markUploaded(Instant.now());

        Job job = jobService.enqueue(GraphParseJobHandler.JOB_TYPE, Map.of("upload_id", upload.getId().toString()));
        upload.setParseJobId(job.getId());
        repository.save(upload);
    }

    private static String extensionOf(String contentType) {
        return "image/png".equals(contentType) ? "png" : "jpg";
    }
}
