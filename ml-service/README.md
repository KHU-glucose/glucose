# ml-service

음식 사진을 OpenAI Vision으로 구조화하고, 리브레 일일 그래프 이미지를 OpenCV와
Tesseract OCR로 15분 간격 혈당 시계열로 변환하는 내부 FastAPI 서비스입니다.

## 환경 변수

```env
OPENAI_API_KEY=...
FOOD_MODEL=gpt-6-luna
INTERNAL_TOKEN=dev-token
LOG_LEVEL=INFO
ML_MAX_CONCURRENCY=4
```

`FOOD_MODEL`이 없으면 `OPENAI_MODEL`, 그마저 없으면 `gpt-6-luna`를 사용합니다.
그래프 OCR 실행 파일을 자동으로 찾지 못하는 로컬 환경에서는 `TESSERACT_CMD`를
설정할 수 있습니다. Docker 이미지에는 영문·한글 Tesseract 데이터가 포함됩니다.

## 실행

저장소 루트에서 로컬 DB와 함께 실행합니다.

```powershell
docker compose -f backend/docker-compose.local.yml up --build
```

직접 실행하려면 다음 명령을 사용합니다.

```powershell
python -m pip install -r ml-service/requirements-dev.txt
python -m uvicorn main:app --app-dir ml-service --reload
```

## API

- `GET /health`: 인증 없는 상태 확인
- `POST /v1/food/recognize`: `image`, 선택 `context`를 받아 실제 음식 인식
- `POST /v1/graph/parse`: 리브레 일일 그래프에서 날짜와 96개 혈당값 추출

두 POST 요청은 `X-Internal-Token`을 요구하며, `X-Request-Id`가 없으면 서비스가
생성합니다. JPEG와 PNG만 허용하고 최대 크기는 8MB입니다.

## 그래프 파서 전제

파서는 리브레 일일 그래프처럼 50 mg/dL 간격의 가로 격자선이 7개 있고, 세로축이 위쪽
350mg/dL에서 아래쪽 50mg/dL로 배치된 이미지를 대상으로 합니다. 날짜는 화면 상단
35%에서 `YYYY년 M월 D일` 또는 `YYYY-MM-DD` 형식으로 읽습니다. 실제 앱 버전,
다크 모드, 화면 배율별 정확도는 팀의 비공개 리브레 이미지와 CSV 데이터셋으로
추가 평가해야 합니다.

그래프 파서 `1.1.0`은 눈금 숫자·아래 시간축을 그래프 영역에서 제외하고,
24시간 축에서 00:00~23:45의 96개 시각을 읽습니다. 평평한 곡선과 빨간 곡선도
보존하며, 곡선이 없는 칸은 결측으로 유지합니다. 충분한 격자선을 확인할 수 없는
잘린 이미지나 다른 축 형식에는 `GRAPH_NOT_RECOGNIZED`를 반환합니다.
CSV는 로컬 정확도 평가의 정답으로만 사용하며 실제 API 입력에 포함하지 않습니다.

`1.1.1`은 공백 경계의 작은 선 끝·잡음을 곡선 두께 기준으로 걸러냅니다.
그래프 API는 최대 변 2048픽셀 이하의 이미지를 원본 바이트로 읽고, 더 큰 이미지만
축소해 PNG로 전달합니다. 음식 API의 768픽셀 전처리는 그대로 유지합니다.

`1.1.2`는 곡선을 연결 상태를 유지하면서 가늘게 만들어 중심 경로를 읽습니다.
굵은 선이 급하게 꺾이는 곳에서 세로 픽셀 열의 중앙값이 치우치는 현상을 줄입니다.
곡선 유무와 작은 잡음 판정은 원본 선을 기준으로 유지해 공백을 채우지 않습니다.

`1.1.3`은 봉우리·바닥 주변의 꺾임을 다시 그려 원본 선과 비교합니다.
픽셀 불일치가 20% 이상 줄고 후보 중심 경로가 원본 선 안에 있는 경우에만 복원합니다.
시각은 소수 픽셀 좌표로 읽되 인접한 열에 곡선이 없으면 보간하지 않습니다.
날짜별 보정값이나 CSV 기반 보정은 사용하지 않습니다.

## 테스트

```powershell
python -m pytest ml-service/tests
```

현재 테스트는 음식 recognizer의 구조화 출력 연결, API 인증·요청 ID, 날짜 파싱,
합성 그래프의 96개 리샘플링과 결측 구간 생성을 검증합니다. 실제 OpenAI 호출과
실제 리브레 이미지 정확도 평가는 별도의 비공개 평가 데이터가 필요합니다.

## 음식 정확도 평가

사람이 작성한 사진별 정답을 넣고 `python ml-service/eval_food.py --limit 10`으로
음식명·개수·비음식 판별 점수를 측정할 수 있습니다. 사전 검사는 `--check`입니다.
준비 방법과 지표 정의는 [음식 평가 안내](../eval/food/README.md)를 참고하세요.

프롬프트 버전은 `food_prompts.py`에서 관리하며, `--prompt-version`으로 같은 데이터에
기존안과 개선안을 비교할 수 있습니다. 식사류는 음식명만 분석하고 count=null로 반환하며,
간식류는 보이는 낱개·조각·포장 수만 기록합니다. 식사류의 unit은 기존 스키마 호환용입니다.
`likely_consumed_all`은 사진만으로 실제 섭취 여부를 알 수 없어 null을 반환하도록 지시합니다.
이는 프롬프트의 지침이며 Pydantic이 사실 여부나 정책 준수를 보증하는 것은 아닙니다.

운영 기본값 `chocolate-v9`은 자유시간 외에 가나·크런키·킷캣·스니커즈 등의 알려진
초콜릿 제품과, 음식명·제품명으로 확인한 초코쿠키·초코파이·초콜릿 코팅 과자도
`name=초콜릿`, `category_hint=FAST_SUGAR`로 통일합니다. 포장 제품명·개수·단위·
confidence는 보존합니다. 음료·케이크·빵·아이스크림·단백질바는 기존 음식명을 유지하고,
명확한 무설탕·제로 초콜릿은 `무설탕 초콜릿`, `SNACK`으로 반환합니다.
이 분류는 당 함량이나 혈당 반응 측정이 아닌 앱의 기록 정책입니다.

제품 별칭 참고: [롯데 공식몰](https://www.lottesweetmall.com/),
[네슬레 킷캣 제품](https://www.nestle.co.kr/브랜드/컨펙셔너리/킷캣/제품),
[스니커즈 코리아](https://www.snickers.co.kr/).

`packaged-v8`을 선택하면 이전 자유시간 전용 보정을, 더 이전 버전은 보정 없이 실행해
과거 평가를 재현할 수 있습니다. 실제 사진 인식 성공률은 아직 측정하지 않았습니다.
