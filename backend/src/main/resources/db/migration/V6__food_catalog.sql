-- food_catalog: AI가 인식한 음식 이름을 당류(g)·분류로 매핑하기 위한 시드 테이블.
-- sugar_grams는 1회 제공량 기준 대략치다(참고용 시드 데이터, 정확한 영양 데이터로 나중에 교체 필요).
CREATE TABLE food_catalog (
    id            UUID PRIMARY KEY,
    name          VARCHAR(255) NOT NULL UNIQUE,
    category_hint VARCHAR(32) NOT NULL,
    tags          VARCHAR(255),
    sugar_grams   NUMERIC(6,1),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO food_catalog (id, name, category_hint, tags, sugar_grams) VALUES
    (gen_random_uuid(), '포도당', 'FAST_SUGAR', NULL, 15.0),
    (gen_random_uuid(), '포도당 캔디', 'FAST_SUGAR', NULL, 15.0),
    (gen_random_uuid(), '사탕', 'FAST_SUGAR', NULL, 10.0),
    (gen_random_uuid(), '초콜릿', 'FAST_SUGAR', 'HIGH_FAT', 12.0),
    (gen_random_uuid(), '젤리', 'FAST_SUGAR', NULL, 10.0),
    (gen_random_uuid(), '오렌지 주스', 'DRINK', NULL, 21.0),
    (gen_random_uuid(), '사과 주스', 'DRINK', NULL, 24.0),
    (gen_random_uuid(), '콜라', 'DRINK', NULL, 35.0),
    (gen_random_uuid(), '바나나', 'SNACK', NULL, 14.0),
    (gen_random_uuid(), '사과', 'SNACK', NULL, 19.0),
    (gen_random_uuid(), '현미밥', 'MEAL', 'HIGH_CARB', 0.5),
    (gen_random_uuid(), '백미밥', 'MEAL', 'HIGH_CARB', 0.1),
    (gen_random_uuid(), '짜장면', 'MEAL', 'HIGH_CARB,HIGH_FAT', 8.0),
    (gen_random_uuid(), '계란말이', 'MEAL', NULL, 1.0),
    (gen_random_uuid(), '맥주', 'ALCOHOL', NULL, 0.0),
    (gen_random_uuid(), '소주', 'ALCOHOL', NULL, 0.0),
    (gen_random_uuid(), '와인', 'ALCOHOL', NULL, 1.0);
