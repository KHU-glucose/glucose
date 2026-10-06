package com.glucoselog.photo;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glucoselog.job.Job;
import com.glucoselog.job.JobHandler;
import com.glucoselog.job.RetryableJobException;
import com.glucoselog.ml.MlServiceClient;

/**
 * 사진 업로드 완료 시 큐에 들어가는 job. R2에서 사진을 내려받아 ml-service 음식 인식을 호출하고,
 * 응답 원문을 job.result에 저장한다(수치 계산·확정은 B4에서 intake 기록을 만들 때 한다).
 */
@Component
public class FoodRecognizeJobHandler implements JobHandler {

    public static final String JOB_TYPE = "FOOD_RECOGNIZE";

    private final IntakePhotoRepository photoRepository;
    private final PhotoStorageService photoStorageService;
    private final MlServiceClient mlServiceClient;
    private final ObjectMapper objectMapper;

    public FoodRecognizeJobHandler(
            IntakePhotoRepository photoRepository,
            PhotoStorageService photoStorageService,
            MlServiceClient mlServiceClient,
            ObjectMapper objectMapper) {
        this.photoRepository = photoRepository;
        this.photoStorageService = photoStorageService;
        this.mlServiceClient = mlServiceClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public String handle(Job job) throws Exception {
        UUID photoId = readPhotoId(job.getPayload());
        IntakePhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalStateException("사진을 찾을 수 없습니다: " + photoId));

        byte[] image;
        try {
            image = photoStorageService.download(photo.getObjectKey());
        } catch (Exception e) {
            throw new RetryableJobException("R2에서 사진을 내려받지 못했습니다", e);
        }

        String context = photo.getContext() != null ? photo.getContext().name() : null;
        return mlServiceClient.recognizeFood(image, photo.getContentType(), context);
    }

    private UUID readPhotoId(String payload) throws Exception {
        Map<?, ?> map = objectMapper.readValue(payload, Map.class);
        return UUID.fromString((String) map.get("photo_id"));
    }
}
