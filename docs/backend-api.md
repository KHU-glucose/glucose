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

현재 정의된 `code` 목록은 8번 표를 본다.

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

## 8. 오류 코드 목록 (현재까지)

| code | HTTP | 설명 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 요청 값이 올바르지 않음 |
| `UNAUTHORIZED` | 401 | access 토큰 없음/만료/위조 |
| `INVALID_APPLE_TOKEN` | 401 | Apple identity token 검증 실패 |
| `INVALID_REFRESH_TOKEN` | 401 | refresh 토큰 없음 |
| `EXPIRED_REFRESH_TOKEN` | 401 | refresh 토큰 만료 |
| `REFRESH_TOKEN_REUSED` | 401 | refresh 토큰 재사용 감지 (전체 세션 폐기) |
| `INTERNAL_ERROR` | 500 | 서버 오류 |

이후 단계(사진 업로드, 기록, 그래프, 리포트)에서 추가되는 코드는 해당 PR에서 이 표에 이어 추가한다.

## 9. 앞으로 추가될 것 (문서 자리만 미리 잡아둠)

- 사진 업로드: presigned PUT URL 발급, 완료 통보 (B2)
- 식사·인슐린 기록 CRUD (B4)
- 혈당 그래프 업로드 (B5)
- 일일·주간 리포트 (B7)
