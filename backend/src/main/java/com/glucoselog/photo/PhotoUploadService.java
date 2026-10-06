package com.glucoselog.photo;

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

@Service
public class PhotoUploadService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final IntakePhotoRepository repository;
    private final PhotoStorageService storageService;
    private final R2Properties properties;
    private final JobService jobService;

    public PhotoUploadService(
            IntakePhotoRepository repository,
            PhotoStorageService storageService,
            R2Properties properties,
            JobService jobService) {
        this.repository = repository;
        this.storageService = storageService;
        this.properties = properties;
        this.jobService = jobService;
    }

    @Transactional
    public PhotoUploadResponse startUpload(UUID userId, String contentType, PhotoContext context) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "지원하지 않는 이미지 형식입니다 (JPEG/PNG만 가능)");
        }

        String objectKey = "photos/%s/%s.%s".formatted(userId, UUID.randomUUID(), extensionOf(contentType));
        IntakePhoto photo = repository.save(new IntakePhoto(userId, objectKey, contentType, context));
        URL uploadUrl = storageService.presignPut(objectKey, contentType);

        return new PhotoUploadResponse(photo.getId(), uploadUrl.toString(), objectKey, properties.presignTtlSeconds());
    }

    @Transactional
    public void completeUpload(UUID userId, UUID photoId) {
        IntakePhoto photo = repository.findByIdAndUserId(photoId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "사진을 찾을 수 없습니다"));

        if (photo.getStatus() == PhotoStatus.UPLOADED) {
            return; // 이미 완료 처리됨 (멱등)
        }
        if (!storageService.exists(photo.getObjectKey())) {
            throw new ApiException(HttpStatus.CONFLICT, "PHOTO_NOT_UPLOADED", "업로드가 아직 완료되지 않았습니다");
        }

        photo.markUploaded(Instant.now());

        // ml-service 호출은 워커가 비동기로 처리한다 — 이 요청 스레드는 기다리지 않는다.
        Job job = jobService.enqueue(FoodRecognizeJobHandler.JOB_TYPE, Map.of("photo_id", photo.getId().toString()));
        photo.setRecognitionJobId(job.getId());
        repository.save(photo);
    }

    private static String extensionOf(String contentType) {
        return "image/png".equals(contentType) ? "png" : "jpg";
    }
}
