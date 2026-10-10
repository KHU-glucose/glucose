# ml-service API 계약서

이미지를 데이터로 바꾸는 FastAPI 서비스(음식 사진 인식, 일일 그래프 파싱)의 입출력 형식과 완료 기준입니다.
이 문서의 형식을 바꾸려면 백엔드 담당자 승인이 필요합니다.

## 1. 개요와 역할 분담

ml-service는 **이미지 두 종류를 받아 구조화된 JSON으로 돌려주는 내부 서비스**입니다. 저장·분석·리포트 계산은 하지 않습니다.
(5-2의 주간 리포트 문장화는 **백엔드가 계산한 사실을 문장으로 옮기기만** 합니다. 숫자·판단은 여전히 백엔드 몫입니다.)

| 구분 | ml-service (AI 담당) | Spring 백엔드 (백엔드 담당) |
|---|---|---|
| 음식 사진 | 음식 이름, 개수, 포장 제품, 분류 힌트 인식 | 사진 저장, 당류 계산, 저혈당 처치 판정 |
| 그래프 이미지 | 날짜, 15분 간격 혈당, 끊긴 구간 추출 | 혈당 저장(upsert), 에피소드 분석, 리포트 |
| 주간 리포트 문장 (5-2, 제안) | 백엔드가 준 사실 목록을 문장으로 옮김 | 사실 계산, 출력 검증, 실패 시 템플릿 문장으로 대체 |
| AI 모델 | 모델 선택, 프롬프트, API 키 관리, 비용 기록 | 관여하지 않음 |
| 계약 변경 | 제안 | 승인 |

**원칙: ml-service는 "무엇이 몇 개"까지만 답합니다.** 당류 g, 처치 적합성, 반동 판정 같은 숫자와 판단은 백엔드가 `food_catalog`로 계산합니다.

## 2. 시스템 구성

같은 서버의 Docker Compose 안에서 Spring이 내부망으로 ml-service를 호출합니다. ml-service는 외부에 포트를 열지 않습니다.

```
iOS 앱
  │  HTTPS
  ▼
Spring 백엔드 (app:8080)
  │  내부망 HTTP + X-Internal-Token
  ▼
ml-service (ml-service:8000, FastAPI)
  ├─ POST /v1/food/recognize   ──▶ AI API (기본: Claude Haiku 4.5)
  ├─ POST /v1/graph/parse      ──▶ 자체 이미지 처리 (OpenCV + OCR)
  ├─ POST /v1/report/narrate   ──▶ AI API (5-2, 제안: 주간 리포트 문장화)
  └─ GET  /health
```

- 호출은 Spring의 **비동기 job 워커**가 합니다. 앱 요청 스레드가 ml-service 응답을 기다리지 않습니다.
- 이미지는 Spring이 S3/R2에서 읽어 바이트로 전달합니다. ml-service는 저장소에 접근하지 않습니다.

## 3. 공통 규칙

| 항목 | 규칙 |
|---|---|
| Base URL | `http://ml-service:8000` (Compose 내부망), 로컬은 `http://localhost:8000` |
| 버전 | 경로에 `/v1` 포함. 호환이 깨지는 변경은 `/v2`로 |
| 인증 | 모든 요청에 `X-Internal-Token: <공유 비밀값>` 헤더. 틀리면 401 (`/health` 제외) |
| 요청 추적 | Spring이 `X-Request-Id` 헤더를 보내고, ml-service는 로그와 응답에 그대로 사용 |
| 요청 형식 | `multipart/form-data`, 필드명 `image` (이미지 API). 5-2는 `application/json` |
| 이미지 | JPEG 또는 PNG, 최대 8MB |
| 응답 형식 | `application/json`, 필드명은 snake_case |
| 스키마 기준 | ml-service의 **Pydantic 모델과 자동 생성 OpenAPI(`/openapi.json`)가 정답**. 이 문서와 다르면 문서를 고칩니다 |

### 오류 응답

```json
{
  "code": "GRAPH_NOT_RECOGNIZED",
  "message": "격자선을 찾지 못했습니다",
  "request_id": "7f3c2a1e-..."
}
```

| HTTP | code | 의미 | Spring 동작 |
|---|---|---|---|
| 400 | `INVALID_IMAGE` | 파일이 없거나 이미지가 아님, 크기 초과 | 재시도 없이 실패 처리 |
| 401 | `UNAUTHORIZED` | 내부 토큰 불일치 | 설정 오류로 알림 |
| 422 | `GRAPH_NOT_RECOGNIZED` | 격자·눈금·날짜를 못 찾음 (지원하지 않는 이미지) | 사용자에게 "지원하지 않는 이미지" 안내 |
| 422 | `TOO_LITTLE_DATA` | 곡선이 하루의 10% 미만 | "데이터가 너무 적어요" 안내 |
| 502 | `UPSTREAM_AI_ERROR` | AI API 오류, 스키마 검증 실패 | 1회 재시도 후 `NEEDS_REVIEW` |
| 504 | `TIMEOUT` | 내부 처리 시간 초과 | 1회 재시도 |

음식이 아닌 사진은 **오류가 아닙니다.** 200과 함께 `is_food_photo: false`를 돌려줍니다.

## 4. API: 음식 사진 인식

`POST /v1/food/recognize` — 사진 한 장을 받아 음식 목록과 개수를 돌려줍니다. 제한 시간 10초.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `image` | file | 예 | 촬영한 사진. ml-service가 긴 변 768px로 줄여서 AI에 전달 |
| `context` | string | 아니오 | 사용자가 촬영 시 고른 상황: `MEAL` `SNACK` `HYPO_TREATMENT` `ALCOHOL`. 인식 정확도를 높이는 힌트로만 사용 |

### 응답 200

```json
{
  "request_id": "7f3c2a1e-...",
  "is_food_photo": true,
  "items": [
    {
      "name": "초콜릿",
      "food_group": "간식",
      "count": 3,
      "unit": "조각",
      "category_hint": "FAST_SUGAR",
      "tags": ["HIGH_FAT"],
      "packaged_product": null,
      "confidence": "high"
    },
    {
      "name": "오렌지 주스",
      "food_group": null,
      "count": 1,
      "unit": "팩",
      "category_hint": "FAST_SUGAR",
      "tags": [],
      "packaged_product": {"brand": "델몬트", "product_name": "오렌지 100%", "volume_ml": 190},
      "confidence": "medium"
    }
  ],
  "likely_consumed_all": true,
  "meta": {"model": "claude-haiku-4-5-20251001", "latency_ms": 1840, "input_tokens": 1120, "output_tokens": 160}
}
```

### 필드 정의

| 필드 | 타입 | 설명 |
|---|---|---|
| `is_food_photo` | boolean | 음식·음료가 없는 사진이면 `false`, 이때 `items`는 빈 배열 |
| `items[].name` | string | 한국어 일반 명칭 (예: 짜장면, 집밥, 사탕). `food_catalog` 매칭에 사용 |
| `items[].food_group` | enum \| null | 표시용 대분류: `한식` `일식` `중식` `간식`. 그 외 음식·음료 또는 구분 근거가 부족하면 `null`. 기존 응답에 없던 필드는 읽을 때 `null`로 취급 |
| `items[].count` | integer \| null | 식사류(`MEAL`)는 항상 `null`. 간식 낱개·음료 용기를 확실히 셀 수 있을 때만 정수, 불확실하면 `null` |
| `items[].unit` | string | 개, 조각, 팩, 컵, 그릇, 공기, 병, 잔 중 하나 |
| `items[].category_hint` | enum | `MEAL` `SNACK` `FAST_SUGAR` `DRINK` `ALCOHOL` |
| `items[].tags` | enum[] | `HIGH_FAT` `HIGH_CARB` `FAST_SUGAR` (분석 창 길이 결정에 사용) |
| `items[].packaged_product` | object \| null | 포장 제품일 때만. 알 수 없는 필드는 `null` |
| `items[].confidence` | enum | `high` `medium` `low`. `low`면 앱에서 사용자 확인 강제 |
| `likely_consumed_all` | boolean \| null | 먹기 전 사진인지 판단이 어려우면 `null` |
| `meta` | object | 모델명, 지연 시간, 토큰 수. 비용 추적용 |

### 규칙

- 대분류는 음식별로 기록합니다. `name`에 접두사를 붙이지 않습니다. 화면에서는 `food_group`이 있으면 `한식 - 김치찌개`처럼 조합하고, `null`이면 음식명만 표시합니다.
- 예: 김치찌개·김밥=`한식`, 초밥·라멘=`일식`, 짜장면·탕수육=`중식`. 과일·과자·빵·약과·낱개 사탕·초콜릿 등 간식은 나라보다 `간식` 분류를 우선합니다. 피자·파스타 등 네 범주 밖 음식과 음료·술은 `null`입니다. 모호한 볶음밥·생선구이 등을 배경이나 식기만으로 한식/일식/중식으로 확정하지 않습니다.
- `food_group`은 표시용이며 기존 `category_hint` 및 개수 정책을 바꾸지 않습니다. 김밥·만두·튀김은 계속 `MEAL`, 식사류 `count=null`, 확실히 셀 수 있는 낱개 간식만 개수를 기록합니다. 사탕은 `food_group="간식"`이면서 `category_hint="FAST_SUGAR"`일 수 있습니다. 혈당 관리 그룹·영양 유사도와도 별개입니다.
- 2026-10-08 변경: 이미지 `detail=low`, 대분류 프롬프트 `cuisine-v7`(v3 규칙 보존 + 대분류 추가) 추가. **운영 기본 프롬프트는 평가 전까지 `examples-v3` 유지**(Dave 결정, 비교 방법: `eval/food/snack-recognition-plan-2026-10-08.md` 4-1절). `food_group`은 선택 필드라 v3에서도 응답 형식은 같다. 기존 v1~v6와 과거 평가 결과는 보존하며, v7의 실제 사진 정확도·대분류 정확도는 아직 평가하지 않았습니다.
- 저혈당 처치 여부는 **판단하지 않습니다.** 같은 사탕도 직전 혈당에 따라 달라서 백엔드가 판정합니다.
- 칼로리·당류·탄수화물 숫자는 **돌려주지 않습니다.**
- AI 출력은 구조화 출력(tool use / JSON schema)으로 강제하고, Pydantic 검증에 실패하면 내부에서 1회 재시도 후 502를 돌려줍니다.

## 5. API: 일일 그래프 이미지 파싱

`POST /v1/graph/parse` — 리브레 앱에서 공유한 일일 그래프 이미지 한 장을 받아 그날의 혈당 시계열을 돌려줍니다. AI를 쓰지 않고 이미지 처리로 추출합니다. 제한 시간 5초.

### 요청

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `image` | file | 예 | 리브레 앱 일일 그래프 이미지 (공유 원본) |

### 응답 200

```json
{
  "request_id": "7f3c2a1e-...",
  "date": "2026-10-01",
  "source": "LIBRE_DAILY_GRAPH",
  "unit": "mg/dL",
  "interval_minutes": 15,
  "readings": [
    {"time": "00:00", "value": 187, "flag": "NORMAL"},
    {"time": "00:15", "value": 194, "flag": "NORMAL"},
    {"time": "14:30", "value": null, "flag": "MISSING"}
  ],
  "gaps": [{"from": "14:30", "to": "16:00"}],
  "coverage_ratio": 0.94,
  "meta": {"parser_version": "1.0.0", "image_width": 1179, "image_height": 2029, "latency_ms": 320}
}
```

### 필드 정의

| 필드 | 타입 | 설명 |
|---|---|---|
| `date` | string | 상단 "2026년 10월 1일"을 OCR로 읽은 값 (YYYY-MM-DD). 못 읽으면 422 |
| `readings` | array | 00:00부터 23:45까지 **항상 96개**, 15분 간격. 시간은 한국 시간 기준 |
| `readings[].value` | integer \| null | 곡선이 없는 구간은 `null`. **사이를 이어 채우지 않음** |
| `readings[].flag` | enum | `NORMAL` / `ABOVE_RANGE`(그래프 상단에 붙음, 실제는 더 높음) / `BELOW_RANGE`(하단에 붙음) / `MISSING` |
| `gaps` | array | 연속된 `MISSING` 구간 요약 |
| `coverage_ratio` | number | 0~1, 값이 있는 비율. 0.1 미만이면 응답 대신 422 `TOO_LITTLE_DATA` |
| `meta.parser_version` | string | 파서 로직이 바뀔 때마다 올림. 백엔드가 재처리 대상 판단에 사용 |

### 처리 규칙

1. 가로 격자선 위치로 세로축(50~350 mg/dL)을 보정
2. 하단 00:00 / 24:00 눈금으로 가로축을 보정
3. 검은 곡선 픽셀을 열마다 찾아 혈당값으로 변환 후 15분 간격으로 리샘플링
4. 목표 범위 배경(연두색)과 격자선은 곡선으로 인식하지 않음
5. 격자·눈금·날짜 중 하나라도 못 찾으면 422 `GRAPH_NOT_RECOGNIZED`
6. 이미지 해상도가 달라도 동작해야 함 (절대 좌표 금지, 격자 기준 상대 좌표)

참고: 10월 1일 이미지 1장으로 프로토타입을 돌려 CSV 대비 평균 오차 1.4 mg/dL를 확인했습니다.

## 5-2. API: 주간 리포트 문장화 (제안 — 팀원·백엔드 합의 전, 합의될 때까지 구현하지 않음)

`POST /v1/report/narrate` — 백엔드가 계산한 **사실 목록**을 받아 주간 리포트 서술 문단으로 옮깁니다. 제한 시간 15초.
**일일 리포트와 의료인 공유용 리포트에는 쓰지 않습니다**(일일은 백엔드 템플릿, 공유용은 사실 그대로).

핵심 원칙: **ml-service는 새 숫자를 만들지 않고, 의학적 설명·평가·조언을 하지 않습니다.** 사실을 읽기 좋은 문장으로 바꾸는 일만 합니다.
가정: 이 서비스가 문장을 만들어도 백엔드가 검증하고, 통과하지 못하면 백엔드의 템플릿 문장을 대신 씁니다.
따라서 문장화는 실패해도 리포트 화면이 막히지 않는 **선택 기능**입니다.

### 요청

`application/json`. 사용자 ID, 날짜, 사진, 자유 메모는 받지 않습니다(필요한 사실만).

```json
{
  "kind": "WEEKLY",
  "facts": [
    { "id": "sufficient_days", "value": 4, "unit": "일", "meaning": "그래프를 충분히 읽은 날 수" },
    { "id": "low_coverage_days", "value": 1, "unit": "일", "meaning": "그래프를 일부만 읽은 날 수" },
    { "id": "pending_days", "value": 2, "unit": "일", "meaning": "아직 끝나지 않아 집계 중인 날 수" },
    { "id": "average_glucose", "value": 138.2, "unit": "mg/dL", "meaning": "읽힌 값만으로 계산한 평균" },
    { "id": "insulin_events_count", "value": 10, "unit": "건", "meaning": "인슐린 기록 건수" },
    { "id": "above_range_after_treatment_count", "value": 2, "unit": "건", "meaning": "처치 기록 뒤 그래프 상단에 닿는 값이 읽힌 건수" }
  ]
}
```

- `facts[].value`가 `null`이면 "알 수 없음"입니다. 추정하거나 0으로 바꾸지 않습니다.
- `meaning`은 백엔드가 고정 문구로 채웁니다. ml-service가 값의 의미를 추측하지 않게 하려는 장치입니다.

### 응답 200

```json
{
  "paragraphs": [
    { "text": "이번 주는 4일 충분히 읽혔고, 1일은 일부만 읽혔어요. 2일은 아직 집계 중이에요.",
      "fact_ids": ["sufficient_days", "low_coverage_days", "pending_days"] }
  ],
  "model": "(사용한 모델 ID)",
  "usage": { "input_tokens": 0, "output_tokens": 0 }
}
```

### 필드 정의

| 필드 | 타입 | 설명 |
|---|---|---|
| `paragraphs` | array | 1~3개 |
| `paragraphs[].text` | string | 한국어 해요체, 문단당 200자 이내 |
| `paragraphs[].fact_ids` | string[] | 이 문단이 근거로 쓴 `facts[].id` (요청에 없는 id 금지) |
| `model`, `usage` | | 비용 기록용. 사용자에게 보이지 않음 |

### 출력 규칙 (프롬프트와 ml-service 쪽 후처리에서 모두 지킴)

1. 문장에 나오는 숫자는 `facts`의 값과 **그대로 같아야** 합니다. 합계·차이·비율·반올림·추세 같은 파생 숫자 금지.
2. 금지: 원인 설명("~때문에"), 평가("잘했어요/아쉬워요"), 조언, 안심 문구, 진단, 인슐린 용량, "정상/비정상/위험", 일반 목표와의 비교.
3. `value`가 `null`이거나 `facts`에 없는 것은 말하지 않습니다.
4. 처치 기록 뒤 높은 값은 "관찰됐다"까지만 씁니다. 반동이라고 부르거나 원인을 붙이지 않습니다.
5. 한계 문구(추정값, 보간 안 함 등)는 **백엔드가 고정해서 붙입니다.** ml-service는 만들지 않습니다.

### 백엔드의 검증 (참고 — 계약 일부)

백엔드는 응답을 받아 아래를 통과해야만 사용합니다. 하나라도 실패하면 그 주의 템플릿 문장을 쓰고, 어떤 문단이 왜 거부됐는지(원문 제외)만 기록합니다.

- `fact_ids`가 모두 요청의 id에 있음
- 문장 속 숫자가 모두 참조한 사실의 값과 일치
- 금지 표현 목록에 걸리지 않음, 길이 제한 이내
- 문단 수 1~3

### 오류

기존 3장 표를 재사용합니다: 400 `INVALID_REQUEST`(새로 추가: 스키마 오류, `facts` 비었음), 401, 502 `UPSTREAM_AI_ERROR`, 504 `TIMEOUT`.
Spring은 재시도 1회 후 실패하면 템플릿 문장을 사용합니다.

### 개인정보와 로그

- 요청·응답 본문과 문장 원문을 로그에 남기지 않습니다(`request_id`, 경로, 상태, `latency_ms`, 모델, 토큰 수만).
- 무상태: 저장하지 않고 응답 후 버립니다.
- 외부 AI 제공자로 건강 수치가 나가므로, 제공자의 데이터 보관·학습 사용 조건을 확인한 뒤 사용합니다(백엔드·팀 확인 필요).
- 비용 추정은 합의 후 측정해 공유합니다.

## 6. 비기능 요구사항

| 항목 | 요구사항 |
|---|---|
| 언어·프레임워크 | Python 3.12, FastAPI, Pydantic v2, uvicorn |
| 상태 | 무상태. DB·파일 저장 없음, 이미지는 메모리에서만 처리하고 버림 |
| 개인정보 | 이미지·AI 응답 원문을 로그에 남기지 않음. 사용자 ID는 받지 않음 |
| 로그 | JSON 한 줄 로그: `request_id`, 경로, 상태 코드, `latency_ms`, 모델, 토큰 수 |
| 자원 | 메모리 300MB 이내 (서버 2GB를 Spring·PostgreSQL과 공유) |
| 동시성 | uvicorn worker 1개, AI 호출은 async. 동시 요청 4개까지 처리 |
| 설정값 | 환경변수: `INTERNAL_TOKEN`, `FOOD_MODEL`, `LOG_LEVEL`, AI 제공자 API 키(기본 `ANTHROPIC_API_KEY`). API 키가 없어도 서버는 기동하고, 요청 시점에 오류로 처리 |
| 모델 교체 | `FoodRecognizer` 인터페이스 뒤에 구현체를 두어 모델을 바꾸어도 API 형식은 그대로 |
| 배포 | Dockerfile 제공, **linux/amd64** 이미지 (서버가 x86). Compose 서비스명 `ml-service`, 폴더 `ml-service/` |
| 문서 | FastAPI 자동 문서 `/docs`를 유지하고, 응답 예시를 Pydantic 모델에 포함 |
| 헬스체크 | `GET /health` → `{"status":"ok","version":"1.0.0"}` (인증 없음, AI 호출 없음) |

## 7. 완료 기준과 평가 방법

완료는 **채점 스크립트의 숫자**로 판단합니다. 프롬프트나 파서를 바꿀 때마다 `make eval`을 돌려 리포트를 남깁니다.

### 음식 인식

| 항목 | 기준 |
|---|---|
| 음식 이름 일치 | 85% 이상 |
| 개수 일치 (낱개 음식) | 80% 이상 |
| 저혈당 처치 음식 구분 (사탕·주스·초콜릿·젤리·포도당) | 95% 이상 |
| 음식 아닌 사진 거르기 | 95% 이상 |
| 스키마 검증 통과율 | 99% 이상 |
| 응답 시간 | 중앙값 3초 이내 |
| 사진 1장당 비용 | 측정해서 보고 (목표 $0.003 이하) |

**테스트셋**: 60~100장. 한식 식사 25, 간식 15, 저혈당 처치 음식 20, 포장 제품 10, 음식 아닌 사진 10 이상. 얼굴·영수증 등 개인정보가 찍히지 않은 사진만.

```
eval/food/
  images/001.jpg ...
  labels.json   [{"file":"001.jpg","is_food_photo":true,
                  "items":[{"name":"초콜릿","count":3}]}]
```

### 그래프 파싱

| 항목 | 기준 |
|---|---|
| CSV 대비 평균 절대 오차 | 3 mg/dL 이하 |
| ±10 mg/dL 이내 비율 | 95% 이상 |
| 날짜 OCR | 100% |
| 끊긴 구간 판정 | CSV에 값이 없는 구간을 모두 `MISSING`으로 |
| 지원 안 하는 이미지 | 일반 사진·다른 앱 화면은 모두 422 |

**테스트셋**: 리브레 일일 그래프 이미지 10장 이상 + 같은 날의 LibreView CSV. 평범한 날, 크게 출렁인 날, 중간에 끊긴 날, 다크 모드를 각각 포함.

### 주간 리포트 문장화 (5-2, 제안)

| 항목 | 기준 |
|---|---|
| 검증 통과율 (숫자 일치, `fact_ids` 유효) | 99% 이상 — 못 미치면 템플릿 사용이 더 많아지는 것이라 가치가 낮음 |
| 금지 표현 | 0건 |
| `null`·누락 사실을 말한 경우 | 0건 |
| 응답 시간 | 중앙값 5초 이내 |
| 호출 1건당 비용 | 측정해서 보고 |

**테스트셋**: 합성 사실 목록 30개 이상(전부 충분한 주, 일부 결측, 값이 `null`, 처치 후 높은 값이 있는 주, 집계 중인 주 포함). 실제 사용자 데이터는 쓰지 않는다.

## 8. 일정과 협업 규칙

### 일정 (4주)

| 주차 | ml-service 담당 | 백엔드 담당 |
|---|---|---|
| 1주 | FastAPI 골격, `/health`, 두 API가 **고정된 가짜 응답** 반환, 음식 테스트 사진 수집·라벨링 | Compose에 ml-service 추가, 가짜 응답으로 Spring job 연동 |
| 2주 | 음식 인식 실제 구현, 채점 스크립트, 모델 비교표 | `food_catalog` 매칭, 처치 판정 로직 |
| 3주 | 그래프 파서 구현, CSV 대조 채점 | 혈당 upsert, 끊긴 구간 처리, 일일 리포트 |
| 4주 | 오류 처리·재시도, amd64 이미지, 완료 기준 리포트 | 서버 배포, 전체 흐름 테스트 |

1주차에 **가짜 응답 API를 먼저** 내는 게 핵심입니다. 그래야 백엔드와 iOS가 AI 완성을 기다리지 않고 같이 진행합니다.

### 협업 규칙

- **저장소**: 모노레포의 `ml-service/` 폴더. 기능별 브랜치 → PR → 백엔드 담당 리뷰 후 머지
- **계약 변경**: 응답 필드 추가는 PR에 명시, 필드 삭제·이름 변경·타입 변경은 사전 협의 후 이 문서부터 수정
- **주간 공유**: 매주 `make eval` 결과(정확도, 응답 시간, 장당 비용)를 팀 채널에 공유
- **비밀값**: API 키는 저장소에 커밋하지 않음. 로컬은 `.env`, 서버는 백엔드 담당이 설정
- **테스트 데이터**: 혈당 그래프·CSV는 건강정보라 저장소에 올리지 않고 팀 비공개 드라이브로 공유
