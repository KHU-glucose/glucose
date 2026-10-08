package com.glucoselog.glucose;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobHandler;
import com.glucoselog.job.RetryableJobException;
import com.glucoselog.ml.MlServiceClient;
import com.glucoselog.photo.PhotoStorageService;

/**
 * 그래프 업로드 완료 시 큐에 들어가는 job. R2에서 이미지를 내려받아 ml-service graph/parse를 호출하고,
 * 성공하면 바로 glucose_reading에 반영한다(GlucoseGraphParseService, 덮어쓰기 정책 적용).
 */
@Component
public class GraphParseJobHandler implements JobHandler {

    public static final String JOB_TYPE = "GRAPH_PARSE";

    private final GlucoseGraphUploadRepository uploadRepository;
    private final PhotoStorageService photoStorageService;
    private final MlServiceClient mlServiceClient;
    private final GlucoseGraphParseService parseService;
    private final ObjectMapper objectMapper;

    public GraphParseJobHandler(
            GlucoseGraphUploadRepository uploadRepository,
            PhotoStorageService photoStorageService,
            MlServiceClient mlServiceClient,
            GlucoseGraphParseService parseService,
            ObjectMapper objectMapper) {
        this.uploadRepository = uploadRepository;
        this.photoStorageService = photoStorageService;
        this.mlServiceClient = mlServiceClient;
        this.parseService = parseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public String handle(Job job) throws Exception {
        UUID uploadId = readUploadId(job.getPayload());
        GlucoseGraphUpload upload = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new IllegalStateException("그래프 업로드를 찾을 수 없습니다: " + uploadId));

        byte[] image;
        try {
            image = photoStorageService.download(upload.getObjectKey());
        } catch (Exception e) {
            throw new RetryableJobException("R2에서 그래프 이미지를 내려받지 못했습니다", e);
        }

        String rawJson = mlServiceClient.parseGraph(image, upload.getContentType());
        return parseService.apply(uploadId, rawJson);
    }

    private UUID readUploadId(String payload) throws Exception {
        Map<?, ?> map = objectMapper.readValue(payload, Map.class);
        return UUID.fromString((String) map.get("upload_id"));
    }
}
