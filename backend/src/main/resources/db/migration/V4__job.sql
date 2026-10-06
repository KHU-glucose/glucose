-- payload/result는 JSON 문자열을 TEXT로 저장한다 (조회 쿼리가 필요해지면 JSONB로 바꾼다)
CREATE TABLE job (
    id            UUID PRIMARY KEY,
    type          VARCHAR(64) NOT NULL,
    payload       TEXT NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts      INT NOT NULL DEFAULT 0,
    max_attempts  INT NOT NULL DEFAULT 3,
    available_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error    TEXT,
    result        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_status_available_at ON job (status, available_at);
