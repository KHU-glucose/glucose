-- 사진에 대해 가장 최근에 큐에 올라간 음식 인식 job을 가리킨다 (GET /v1/photos/{id}/recognition에서 사용)
ALTER TABLE intake_photo ADD COLUMN recognition_job_id UUID REFERENCES job(id);
