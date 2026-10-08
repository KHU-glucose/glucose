package com.glucoselog.glucose;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucoselog.common.ApiException;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobRepository;

@Service
public class GlucoseGraphStatusService {

    private final GlucoseGraphUploadRepository uploadRepository;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public GlucoseGraphStatusService(
            GlucoseGraphUploadRepository uploadRepository, JobRepository jobRepository, ObjectMapper objectMapper) {
        this.uploadRepository = uploadRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    public GlucoseGraphStatusResponse getStatus(UUID userId, UUID uploadId) {
        GlucoseGraphUpload upload = uploadRepository.findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GLUCOSE_GRAPH_NOT_FOUND", "그래프를 찾을 수 없습니다"));

        UUID jobId = upload.getParseJobId();
        Job job = jobId != null ? jobRepository.findById(jobId).orElse(null) : null;
        if (job == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "아직 분석이 시작되지 않았습니다");
        }

        return switch (job.getStatus()) {
            case PENDING, PROCESSING ->
                throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "아직 분석 중입니다");
            case FAILED ->
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GRAPH_PARSE_FAILED", "그래프를 읽을 수 없습니다");
            case DONE -> parse(job.getResult());
        };
    }

    private GlucoseGraphStatusResponse parse(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, GlucoseGraphStatusResponse.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GRAPH_PARSE_FAILED", "분석 결과를 해석할 수 없습니다");
        }
    }
}
