# 백엔드 API 규약 (iOS ↔ Spring)

이 문서는 iOS 앱이 맞춰야 할 백엔드 API의 공통 규칙과, 지금까지 구현된 엔드포인트를 정리한다.
ml-service와의 계약은 `docs/ml-service-contract.md`를 본다. 이 문서는 iOS ↔ 백엔드 계약이다.

## 1. 공통 규칙

| 항목 | 규칙 |
|---|---|
| Base URL | 운영: `https://<도메인>`. 로컬: `http://localhost:8080` |
| 버전 | 경로에 `/v1` 포함 |
| 인증 | `Authorization: Bearer <access_token>`. `/actuator/health`, `/v1/auth/**` 제외 전부 필요 |
| 필드명 | 요청·응답 모두 snake_case |
| 시간 | DB 저장은 UTC `timestamptz`. 응답 JSON은 ISO-8601 (`"2026-10-06T08:00:00Z"`) |
| 요청 추적 | `X-Request-Id` 헤더(선택). 보내지 않으면 서버가 생성해 응답 헤더에 그대로 돌려준다 |
| 오류 응답 | `{code, message, request_id}` (아래 3번) |
| 페이지네이션 | cursor 방식 (아래 4번) |

## 2. 인증 헤더

- `/v1/auth/apple`, `/v1/auth/refresh`로 access/refresh 토큰을 받은 뒤, 그 외 모든 요청에 `Authorization: Bearer <access_token>`을 보낸다.
- access 토큰이 없거나 만료·위조됐으면 401 `{code: "UNAUTHORIZED", ...}`.
- access 토큰 만료 시 `/v1/auth/refresh`로 재발급받는다. refresh 토큰도 만료·무효면 다시 로그인한다.

## 3. 오류 응답

```json
{
  "code": "INVALID_REFRESH_TOKEN",
  "message": "refresh 토큰을 찾을 수 없습니다",
  "request_id": "7f3c2a1e-..."
}
```

| HTTP | 의미 |
|---|---|
| 400 | 요청 값 검증 실패 (`VALIDATION_ERROR`) |
| 401 | 인증 실패/토큰 없음·만료·위조 |
| 403 | 본인 소유가 아닌 리소스 접근 |
| 404 | 리소스 없음 |
| 422 | 요청은 올바르지만 처리할 수 없음 (예: ml-service 쪽 `TOO_LITTLE_DATA`, `GRAPH_NOT_RECOGNIZED`) |
| 500 | 서버 오류 (`INTERNAL_ERROR`) |

현재 정의된 `code` 목록은 13번 표를 본다.

## 4. 페이지네이션 (cursor)

목록을 반환하는 모든 엔드포인트는 동일한 모양을 쓴다.

요청: `GET /v1/xxx?cursor=<opaque>&limit=20` (`limit` 기본 20, 최대 100)

응답:
```json
{
  "items": [ ... ],
  "next_cursor": "opaque-string-or-null"
}
```

- `cursor`는 서버가 내려준 값을 그대로 다음 요청에 돌려주는 불투명 문자열이다. 클라이언트가 내용을 해석하지 않는다.
- `next_cursor`가 `null`이면 마지막 페이지다.

## 5. `POST /v1/auth/apple` — Sign in with Apple 로그인

Apple이 발급한 identity token을 검증해 `app_user`를 upsert하고 자체 토큰을 발급한다.

요청:
```json
{ "identity_token": "<Apple identity token>", "nonce": "선택, Apple 요청 시 쓴 값과 동일해야 함" }
```

응답 200:
```json
{
  "access_token": "<JWT>",
  "refresh_token": "<opaque string>",
  "token_type": "Bearer",
  "expires_in": 900
}
```

| 오류 | code | 원인 |
|---|---|---|
| 401 | `INVALID_APPLE_TOKEN` | 서명/발급자/대상/만료/nonce 중 하나라도 불일치 |

## 6. `POST /v1/auth/refresh` — 토큰 재발급

요청:
```json
{ "refresh_token": "<opaque string>" }
```

응답 200: `/v1/auth/apple`과 동일한 모양. **refresh 토큰은 매번 새 값으로 회전**하며, 응답으로 받은 `refresh_token`으로 이전 값을 교체해야 한다.

| 오류 | code | 원인 |
|---|---|---|
| 401 | `INVALID_REFRESH_TOKEN` | 존재하지 않는 토큰 |
| 401 | `EXPIRED_REFRESH_TOKEN` | 만료된 토큰 |
| 401 | `REFRESH_TOKEN_REUSED` | 이미 회전되어 폐기된 토큰이 재사용됨 → **이 사용자의 모든 세션이 즉시 종료됨**. 클라이언트는 재로그인 화면으로 보낸다 |

토큰 수명: access 15분, refresh 30일.

## 7. `DELETE /v1/me` — 계정 삭제

인증된 사용자 본인의 계정과 모든 연관 데이터를 삭제한다. App Store 심사 요건(앱 내 계정 삭제)을 만족한다.

요청: 본문 없음. `Authorization` 헤더만 필요.

응답: `204 No Content`. 이후 해당 access/refresh 토큰은 모두 무효가 된다.

## 8. `POST /v1/photos` — 사진 업로드 시작 (B2)

R2(S3 호환) presigned PUT URL을 발급한다. **앱이 이 URL로 R2에 직접 업로드하며, 서버는 바이트를 중계하지 않는다.**

요청:
```json
{ "content_type": "image/jpeg", "context": "MEAL" }
```
- `content_type`: `image/jpeg` 또는 `image/png`만 허용
- `context`: 선택, `MEAL` `SNACK` `HYPO_TREATMENT` `ALCOHOL` 중 하나 (ml-service 계약과 동일)

응답 201:
```json
{ "photo_id": "<uuid>", "upload_url": "<presigned PUT URL>", "object_key": "photos/...", "expires_in": 600 }
```

앱은 `upload_url`로 이미지 바이트를 `PUT`(헤더 `Content-Type`을 요청 시 보낸 값과 동일하게)한 뒤, 아래 완료 통보를 호출한다.

## 9. `POST /v1/photos/{photoId}/complete` — 사진 업로드 완료 통보 (B2)

요청 본문 없음. R2에 실제로 객체가 올라왔는지 서버가 확인한 뒤 완료 처리하고, 음식 인식을 백그라운드 job으로 큐에 넣는다(응답은 ml-service를 기다리지 않고 바로 간다).

응답: `204 No Content`

| 오류 | code | 원인 |
|---|---|---|
| 404 | `PHOTO_NOT_FOUND` | 본인 소유가 아니거나 없는 사진 (존재 여부를 구분해 알려주지 않음) |
| 409 | `PHOTO_NOT_UPLOADED` | R2에 실제 업로드가 안 된 상태에서 완료만 호출함 |

## 10. `GET /v1/photos/{photoId}/recognition` — 음식 인식 결과 조회 (B4)

업로드 완료 후 큐에 들어간 음식 인식 job의 결과를 조회한다. ml-service 응답(`docs/ml-service-contract.md`)에서 백엔드가 쓰는 필드만 추린 모양이다.

응답 200:
```json
{
  "is_food_photo": true,
  "items": [
    { "name": "초콜릿", "food_group": "간식", "count": 3, "unit": "조각", "category_hint": "FAST_SUGAR", "tags": ["HIGH_FAT"], "confidence": "high" }
  ],
  "likely_consumed_all": true
}
```

`food_group`은 `한식` / `일식` / `중식` / `간식` 또는 `null`인 표시용 대분류다. 과거 인식 결과에 필드가 없으면 `null`로 반환한다. `name`은 음식명 그대로 유지하며 화면에서만 `한식 - 김치찌개`처럼 조합한다. 기존 `category_hint`·개수·당류 계산과 별개이며, intake 저장 필드는 이번 변경에 포함하지 않는다.

이 결과를 사용자가 확인·수정한 뒤 `POST /v1/intakes`로 최종 확정한다 (AI 값은 제안일 뿐, 수정값이 우선).

| 오류 | code | HTTP | 원인 |
|---|---|---|---|
| `PHOTO_NOT_FOUND` | 404 | 본인 소유가 아니거나 없는 사진 |
| `RECOGNITION_NOT_READY` | 409 | 아직 완료 통보 전이거나, job이 아직 대기/처리 중 |
| `RECOGNITION_FAILED` | 422 | ml-service 호출이 재시도를 다 썼는데도 실패함 |

## 11. 기록(intake) CRUD (B4)

식사·간식·저혈당 처치·음주 기록. 사진 없이도(수동 입력) 만들 수 있다.

### `POST /v1/intakes`
```json
{
  "context": "MEAL",
  "occurred_at": "2026-10-01T08:00:00Z",
  "photo_id": "선택, 사진에서 만든 기록이면 전달",
  "items": [
    { "name": "초콜릿", "count": 3, "unit": "조각" },
    { "name": "콜라", "count": 1, "unit": "캔", "category_hint": "DRINK", "tags": [] }
  ]
}
```
- 항목별로 `category_hint`/`tags`를 **보내면 사용자가 직접 정한 값으로 우선 적용**된다 (AI 제안이나 `food_catalog` 매칭보다 우선).
- `category_hint`를 보내지 않으면 `food_catalog`에서 이름으로 찾아 채운다. 둘 다 없으면 분류 없음(`null`)으로 저장된다.
- `sugar_grams`(당류)는 요청으로 보낼 수 없다 — 항상 `food_catalog` 매칭으로 서버가 계산한다.

응답 201: 아래 모양 (목록/상세 공통)
```json
{
  "id": "<uuid>",
  "context": "MEAL",
  "occurred_at": "2026-10-01T08:00:00Z",
  "photo_id": null,
  "items": [
    { "id": "<uuid>", "name": "초콜릿", "count": 3, "unit": "조각", "category_hint": "FAST_SUGAR", "tags": ["HIGH_FAT"], "sugar_grams": 12.0 }
  ],
  "created_at": "...", "updated_at": "..."
}
```

### `GET /v1/intakes` — 목록 (cursor, 최신순)
### `GET /v1/intakes/{id}` — 상세
### `PATCH /v1/intakes/{id}` — 수정 (요청 모양은 생성과 동일. **항목 전체를 교체**한다, 부분 수정 아님)
### `DELETE /v1/intakes/{id}` — 삭제

| 오류 | code | HTTP |
|---|---|---|
| `INTAKE_NOT_FOUND` | 404 | 본인 소유가 아니거나 없는 기록 |

## 12. 인슐린 기록 CRUD (B4)

입력만 저장한다. **용량 조언·판단은 하지 않는다.**

### `POST /v1/insulin-events`
```json
{ "occurred_at": "2026-10-01T08:05:00Z", "units": 6, "kind": "식사" }
```
- `units`는 0보다 커야 한다. `kind`는 자유 텍스트(예: "식사", "기저")

응답 201: `{ "id", "occurred_at", "units", "kind", "created_at", "updated_at" }`

### `GET /v1/insulin-events` — 목록 (cursor, 최신순)
### `GET /v1/insulin-events/{id}` — 상세
### `PATCH /v1/insulin-events/{id}` — 수정
### `DELETE /v1/insulin-events/{id}` — 삭제

| 오류 | code | HTTP |
|---|---|---|
| `INSULIN_EVENT_NOT_FOUND` | 404 | 본인 소유가 아니거나 없는 기록 |

## 13. 오류 코드 목록 (현재까지)

| code | HTTP | 설명 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 요청 값이 올바르지 않음 |
| `UNAUTHORIZED` | 401 | access 토큰 없음/만료/위조 |
| `INVALID_APPLE_TOKEN` | 401 | Apple identity token 검증 실패 |
| `INVALID_REFRESH_TOKEN` | 401 | refresh 토큰 없음 |
| `EXPIRED_REFRESH_TOKEN` | 401 | refresh 토큰 만료 |
| `REFRESH_TOKEN_REUSED` | 401 | refresh 토큰 재사용 감지 (전체 세션 폐기) |
| `PHOTO_NOT_FOUND` | 404 | 사진 없음/본인 소유 아님 |
| `PHOTO_NOT_UPLOADED` | 409 | R2 업로드 전에 완료 통보함 |
| `RECOGNITION_NOT_READY` | 409 | 음식 인식 job이 아직 대기/처리 중 |
| `RECOGNITION_FAILED` | 422 | 음식 인식이 재시도 후에도 실패 |
| `INTAKE_NOT_FOUND` | 404 | 기록 없음/본인 소유 아님 |
| `INSULIN_EVENT_NOT_FOUND` | 404 | 인슐린 기록 없음/본인 소유 아님 |
| `INTERNAL_ERROR` | 500 | 서버 오류 |

| `GLUCOSE_GRAPH_NOT_FOUND` | 404 | 그래프 업로드 없음/본인 소유 아님 |
| `GLUCOSE_GRAPH_NOT_UPLOADED` | 409 | R2 업로드 전에 완료 통보함 |
| `GRAPH_NOT_READY` | 409 | 그래프 분석 job이 아직 대기/처리 중 |
| `GRAPH_PARSE_FAILED` | 422 | 그래프 인식 실패(격자·눈금·날짜 인식 불가, 데이터 10% 미만 등) |
| `GLUCOSE_DAY_NOT_FOUND` | 404 | 해당 날짜의 혈당 데이터 없음 |

이후 단계(리포트)에서 추가되는 코드는 해당 PR에서 이 표에 이어 추가한다.

## 14. 혈당 그래프 (B5)

리브레 앱에서 공유한 일일 그래프 이미지 1장을 업로드하면 ml-service가 OCR로 그 날의 혈당 시계열을 읽어온다.
업로드 자체는 어떤 날짜인지 모른 채로 시작하고(이미지에서 날짜를 읽는 건 ml-service 몫), 분석이 끝나야 날짜를 알 수 있다.
**같은 사용자의 같은 날짜로 다시 업로드하면 기존 데이터를 덮어쓴다**(재업로드 정책, Dave 확인).

### `POST /v1/glucose-graphs` — 업로드 시작
```json
{ "content_type": "image/jpeg" }
```
응답 201: `{ "upload_id": "<uuid>", "upload_url": "<presigned PUT URL>", "object_key": "graphs/...", "expires_in": 600 }`

### `POST /v1/glucose-graphs/{uploadId}/complete` — 업로드 완료 통보
R2에 실제로 올라왔는지 확인한 뒤 그래프 분석을 백그라운드 job으로 큐에 넣는다(응답은 ml-service를 기다리지 않는다).
응답: `204 No Content`

### `GET /v1/glucose-graphs/{uploadId}` — 분석 상태 조회
분석 중이면 409 `GRAPH_NOT_READY`, 실패했으면 422 `GRAPH_PARSE_FAILED`.
완료되면 200: `{ "date": "2026-10-01", "coverage_ratio": 0.94 }`

### `GET /v1/glucose-readings/{date}` — 날짜별 혈당 시계열 조회
`date`는 `YYYY-MM-DD`. 응답 200:
```json
{
  "date": "2026-10-01",
  "coverage_ratio": 0.94,
  "readings": [
    { "time": "00:00", "value": 120, "flag": "NORMAL" },
    { "time": "00:15", "value": null, "flag": "MISSING" }
  ]
}
```
`value`가 `null`인 구간은 **보간하지 않은 결측**이다. 해당 날짜에 분석 완료된 그래프가 없으면 404 `GLUCOSE_DAY_NOT_FOUND`.

## 15. 앞으로 추가될 것 (문서 자리만 미리 잡아둠)

- 에피소드/반동 판정 (B6)
- 일일·주간 리포트 (B7)
