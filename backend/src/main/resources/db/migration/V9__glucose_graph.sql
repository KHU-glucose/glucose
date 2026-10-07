CREATE TABLE glucose_graph_upload (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    object_key     VARCHAR(512) NOT NULL UNIQUE,
    content_type   VARCHAR(100) NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING_UPLOAD',
    result_date    DATE,
    coverage_ratio NUMERIC(5,4),
    parser_version VARCHAR(50),
    parse_job_id   UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_at    TIMESTAMPTZ
);

CREATE INDEX idx_glucose_graph_upload_user_id ON glucose_graph_upload (user_id);
CREATE INDEX idx_glucose_graph_upload_user_id_result_date ON glucose_graph_upload (user_id, result_date);

-- 같은 (user_id, result_date)에 대해 재업로드 시 기존 glucose_graph_upload 행을 지우고 다시 만든다(덮어쓰기 정책).
CREATE TABLE glucose_reading (
    id         UUID PRIMARY KEY,
    upload_id  UUID NOT NULL REFERENCES glucose_graph_upload(id) ON DELETE CASCADE,
    time_slot  INT NOT NULL,      -- 00:00부터 15분 간격, 분 단위(0~1425)
    value      INT,               -- mg/dL, 끊긴 구간은 NULL(보간하지 않음)
    flag       VARCHAR(20) NOT NULL,
    UNIQUE (upload_id, time_slot)
);

CREATE INDEX idx_glucose_reading_upload_id ON glucose_reading (upload_id);
