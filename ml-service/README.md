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

파서는 리브레 일일 그래프처럼 긴 가로 격자선이 3개 이상 있고, 세로축이 위쪽
350mg/dL에서 아래쪽 50mg/dL로 배치된 이미지를 대상으로 합니다. 날짜는 화면 상단
35%에서 `YYYY년 M월 D일` 또는 `YYYY-MM-DD` 형식으로 읽습니다. 실제 앱 버전,
다크 모드, 화면 배율별 정확도는 팀의 비공개 리브레 이미지와 CSV 데이터셋으로
추가 평가해야 합니다.

## 테스트

```powershell
python -m pytest ml-service/tests
```

현재 테스트는 음식 recognizer의 구조화 출력 연결, API 인증·요청 ID, 날짜 파싱,
합성 그래프의 96개 리샘플링과 결측 구간 생성을 검증합니다. 실제 OpenAI 호출과
실제 리브레 이미지 정확도 평가는 별도의 비공개 평가 데이터가 필요합니다.
