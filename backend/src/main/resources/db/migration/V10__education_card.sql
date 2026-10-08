-- 리포트에 조건부로 붙는 고정 교육 카드. AI가 생성하지 않는다(B7: 문장은 지금 단계에서 숫자만, 카드는 미리 쓴 고정 텍스트).
CREATE TABLE education_card (
    id         UUID PRIMARY KEY,
    trigger    VARCHAR(32) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_education_card_trigger ON education_card (trigger);

INSERT INTO education_card (id, trigger, title, body) VALUES
    (gen_random_uuid(), 'REBOUND', '저혈당 처치 후 혈당이 다시 올라갔어요',
     '저혈당을 처치한 뒤 분석 창 안에서 혈당이 목표범위보다 높게 올라간 날이 있어요. 처치량이 많으면 반동이 생길 수 있어요. 처치량 조절은 담당 의료진과 상담하세요.'),
    (gen_random_uuid(), 'LOW_COVERAGE', '오늘은 혈당 데이터가 부족해요',
     '그래프에서 읽은 혈당 구간이 적어서 오늘 리포트는 판단하기 어려워요. 그래프 이미지를 다시 업로드하면 더 정확한 리포트를 볼 수 있어요.');
