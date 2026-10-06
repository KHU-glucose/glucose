-- 입력만 저장한다. 용량 조언/판단 필드는 없다.
CREATE TABLE insulin_event (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    units       NUMERIC(5,1) NOT NULL,
    kind        VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_insulin_event_user_id_occurred_at ON insulin_event (user_id, occurred_at DESC, id DESC);
