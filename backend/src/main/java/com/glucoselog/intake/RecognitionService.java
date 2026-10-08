package com.glucoselog.intake;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucoselog.common.ApiException;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobRepository;
import com.glucoselog.photo.IntakePhoto;
import com.glucoselog.photo.IntakePhotoRepository;

@Service
public class RecognitionService {

    private final IntakePhotoRepository photoRepository;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public RecognitionService(
            IntakePhotoRepository photoRepository, JobRepository jobRepository, ObjectMapper objectMapper) {
        this.photoRepository = photoRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    public RecognitionResponse getRecognition(UUID userId, UUID photoId) {
        IntakePhoto photo = photoRepository.findByIdAndUserId(photoId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "사진을 찾을 수 없습니다"));

        UUID jobId = photo.getRecognitionJobId();
        Job job = jobId != null ? jobRepository.findById(jobId).orElse(null) : null;
        if (job == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RECOGNITION_NOT_READY", "아직 인식이 시작되지 않았습니다");
        }

        return switch (job.getStatus()) {
            case PENDING, PROCESSING ->
                throw new ApiException(HttpStatus.CONFLICT, "RECOGNITION_NOT_READY", "아직 인식 중입니다");
            case FAILED ->
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RECOGNITION_FAILED", "인식에 실패했습니다");
            case DONE -> parse(job.getResult());
        };
    }

    private RecognitionResponse parse(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, RecognitionResponse.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RECOGNITION_FAILED", "인식 결과를 해석할 수 없습니다");
        }
    }
}
