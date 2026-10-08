package com.glucoselog.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.glucoselog.auth.AppUserRepository;
import com.glucoselog.glucose.GlucoseGraphUploadRepository;
import com.glucoselog.job.JobRepository;
import com.glucoselog.job.JobService;
import com.glucoselog.photo.IntakePhotoRepository;

/**
 * DELETE /v1/me. DB 데이터는 app_user ON DELETE CASCADE로 지우고, 그 외에 남는 것도 정리한다.
 * - job: 사용자와 FK로 연결돼 있지 않아 cascade로 안 지워진다 → 사진·그래프에 연결된 job id를 모아 같이 지운다.
 * - R2 사진·그래프 파일: 같은 트랜잭션에서 정리 job을 넣는다. DB 삭제가 커밋되면 정리 job도 반드시 남고,
 *   R2가 잠깐 실패해도 job 워커가 재시도한다(요청 안에서 바로 지우면 실패 시 파일이 영영 남는다).
 */
@Service
public class AccountDeletionService {

    private final AppUserRepository appUserRepository;
    private final IntakePhotoRepository intakePhotoRepository;
    private final GlucoseGraphUploadRepository glucoseGraphUploadRepository;
    private final JobRepository jobRepository;
    private final JobService jobService;

    public AccountDeletionService(
            AppUserRepository appUserRepository,
            IntakePhotoRepository intakePhotoRepository,
            GlucoseGraphUploadRepository glucoseGraphUploadRepository,
            JobRepository jobRepository,
            JobService jobService) {
        this.appUserRepository = appUserRepository;
        this.intakePhotoRepository = intakePhotoRepository;
        this.glucoseGraphUploadRepository = glucoseGraphUploadRepository;
        this.jobRepository = jobRepository;
        this.jobService = jobService;
    }

    @Transactional
    public void deleteAccount(UUID userId) {
        List<UUID> jobIds = new ArrayList<>(intakePhotoRepository.findRecognitionJobIdsByUserId(userId));
        jobIds.addAll(glucoseGraphUploadRepository.findParseJobIdsByUserId(userId));

        appUserRepository.deleteById(userId);
        // intake_photo.recognition_job_id가 job을 FK로 참조하므로, 사진 행이 먼저 지워진 뒤에 job을 지운다.
        appUserRepository.flush();
        jobRepository.deleteAllByIdInBatch(jobIds);

        jobService.enqueue(UserStoragePurgeJobHandler.JOB_TYPE, Map.of("user_id", userId.toString()));
    }
}
