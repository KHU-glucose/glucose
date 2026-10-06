CREATE TABLE intake_photo (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    object_key   VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    context      VARCHAR(32),
    status       VARCHAR(32) NOT NULL DEFAULT 'PENDING_UPLOAD',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_at  TIMESTAMPTZ
);

CREATE INDEX idx_intake_photo_user_id ON intake_photo (user_id);
