CREATE TABLE intake (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    photo_id     UUID REFERENCES intake_photo(id) ON DELETE SET NULL,
    context      VARCHAR(32) NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_intake_user_id_occurred_at ON intake (user_id, occurred_at DESC, id DESC);

CREATE TABLE intake_item (
    id              UUID PRIMARY KEY,
    intake_id       UUID NOT NULL REFERENCES intake(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    count           INT,
    unit            VARCHAR(32),
    category_hint   VARCHAR(32),
    tags            VARCHAR(255),
    sugar_grams     NUMERIC(6,1),
    food_catalog_id UUID REFERENCES food_catalog(id),
    sort_order      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_intake_item_intake_id ON intake_item (intake_id);
