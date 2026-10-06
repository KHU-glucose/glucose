# 백엔드 개발 계획 (Claude Code 작업 지시서)

> 기준: `main` = PR #1(ml-service 음식 인식 mock) 머지 직후. 규칙과 도메인 배경은 `CLAUDE.md`, ml-service 계약은 `docs/ml-service-contract.md`가 정답이다.
> 작업 방식: **한 PR = 한 목적.** 브랜치 → PR → Dave가 머지. main에 직접 푸시 금지(머지되면 자동 배포됨). 각 단계 끝에 변경 요약과 실행한 테스트 결과를 보고한다.

## 먼저 확인 (읽기만, 수정 금지)
1. `git log --oneline -15`, `git ls-files`로 현재 구조 파악.
2. 작업 전 이 문서의 가정이 현재 코드와 다르면 코드 기준으로 보고하고 계획을 조정한다.

---

## Phase A. 정리 (백엔드 기능 개발 전에 끝낼 것 — A1 하나만)

### PR A1 — 저장소 위생 `chore/repo-hygiene`
현재 확인된 문제:
- `.DS_Store`가 루트에 **커밋돼 있음** → `git rm --cached .DS_Store`
- 루트 `.gitignore`가 빈약함(`.venv/`, `.env`, `__pycache__/`, `*.pyc`, `IMPLEMENTATION_LOG_2026-10-05.md`뿐). 추가: `.DS_Store`, `.env.*`(단 `!.env.example`), `*.pem`, `*.key`, `*.p12`, `*.csv`, `*.xlsx`, `.idea/`, `.vscode/`, `build/`, `.gradle/`, `*.log`, `data/`, `samples/`(혈당 CSV·그래프·음식 사진은 커밋 금지)
- `CLAUDE.md`, `docs/ml-service-contract.md`, 이 문서(`docs/backend-plan.md`)가 저장소에 아직 없음 → 추가
- `.github/workflows/deploy.yml`: 문서만 바뀐 push에도 배포가 돌지 않게 `paths-ignore: ['docs/**','design/**','AI_use_organize/**','**.md']` (ml-service 테스트 job은 팀원이 테스트를 추가한 뒤)
- 서버 IP·도메인·키가 저장소에 없는지 `git grep -nE '([0-9]{1,3}\.){3}[0-9]{1,3}|sslip\.io|BEGIN .*PRIVATE'`로 점검하고 결과 보고.
- **하지 말 것**: `AI_use_organize/` 수정(대회 제출용 기록).

### 이번 범위에서 제외 (백엔드 우선)
- **ml-service 수정(구 A2)은 팀원 몫**이라 Claude Code가 하지 않는다. 대신 아래 목록을 `docs/ml-service-todo.md`로 정리만 한다(팀원 전달용, 코드 수정 금지):
  health 버전 오타 `"1.0.0."`, `X-Request-Id` 누락 시 422(인증 오류가 가려짐 → 선택값 + 서버 생성, 인증 검사가 먼저), 검증 오류를 계약 형식 `{code, message, request_id}`로 변환, `/v1/graph/parse` 가짜 응답, pytest, requirements 분리·non-root·`.dockerignore`, (후순위) EXIF 회전·threadpool·의존성 경량화.
- **`jaehyun/` 삭제(구 A3)는 팀원 허락을 받은 뒤** Dave가 따로 지시할 때만 한다. 삭제 시 지킬 것: 삭제 전 `git tag legacy-jaehyun-prototype`, 별도 PR 하나, `AI_use_organize/`(수정 금지)가 `../jaehyun/*`를 링크하고 있어 링크가 깨짐을 PR 설명에 명시. 지금은 **건드리지 않는다.**
- **PR #2(`design/`)는 무시한다.** 머지·수정·리뷰하지 않는다.

---

## Phase B. 백엔드 기능 (순서 고정)

공통 규칙: 스키마는 Flyway만(`V2__...`부터, `ddl-auto: validate`), 응답 snake_case, 오류 `{code, message, request_id}`, 시간은 UTC `timestamptz`·응답은 ISO-8601, 테스트는 Testcontainers(PostgreSQL 16, `testcontainers.version=1.21.4` 유지). 로그에 혈당값·사진 URL·토큰 금지. 용량 조언 관련 필드·문구 금지.

| PR | 브랜치 | 내용 | 완료 기준 |
|---|---|---|---|
| B0 | `docs/backend-api` | `docs/backend-api.md`: iOS↔백엔드 API 규약(시간, 오류, 페이지네이션 cursor, 인증 헤더) | Dave 승인 |
| B1 | `feat/auth-apple` | Sign in with Apple 토큰 검증(서명·`aud`·`exp`·nonce, 검증기는 인터페이스로 분리해 mock 가능), `app_user` upsert, 자체 JWT(access 15~30분 + refresh 회전, DB엔 해시 저장), `POST /v1/auth/apple`, `POST /v1/auth/refresh`, `DELETE /v1/me`(계정·데이터 삭제) | 토큰 없음/만료/위조 401, 삭제 후 데이터 없음 |
| B2 | `feat/photo-upload` | R2 presigned PUT 발급(S3 호환 SDK), `intake_photo`, 완료 통보 API | 본인 사진만 접근, 서버 경유 업로드 없음 |
| B3 | `feat/job-worker` | `job` 테이블, `@Scheduled` 워커(`FOR UPDATE SKIP LOCKED`), 재시도·FAILED, ml-service 클라이언트(`X-Internal-Token`, `X-Request-Id`, 타임아웃) | ml-service 장애 시에도 앱 요청은 정상 응답 |
| B4 | `feat/records` | 식사·저혈당 처치 `intake` + `insulin_event` CRUD, AI 결과를 사용자가 수정·확정(수정값 우선), `food_catalog` 시드 | 계약의 `category_hint`/`tags` 매핑 테스트 |
| B5 | `feat/glucose-graph` | 그래프 이미지 업로드 → `graph/parse` → `glucose_reading`. 끊긴 구간은 **보간 없이 null/MISSING**, `coverage_ratio < 0.1`이면 422 | 재업로드 정책(덮어쓰기 vs 버전) Dave 확인 후 구현 |
| B6 | `feat/episodes` | 에피소드 묶기(60~90분), 분석 창(식사 3~5h / 저혈당 처치 2h / 술 12h), 직전 혈당 70 미만이면 처치로 자동 분류, 반동 판정. **AI 없는 순수 함수**, 파라미터는 `application.yml` | 경계값 단위 테스트 다수 |
| B7 | `feat/reports` | 일일·주간 리포트, 근거 건수 표시, `education_card`. 숫자는 코드, 문장만 AI, 데이터 부족 시 "판단 불가" | 같은 입력이면 같은 숫자 |
| B8 | 병행 | 레이트 리밋, OpenAPI, 로그 민감정보 점검, 백업 복구 테스트 | 복구 1회 성공 |

## Dave가 결정해야 할 것 (Claude Code는 임의로 정하지 말고 질문)
1. 같은 날 그래프 재업로드: 덮어쓰기 vs 새 버전
2. JWT 수명·refresh 회전 방식 최종안
3. 그래프 파서(곡선 읽기)를 누가 어디까지 만드는지 → B5 일정 좌우
4. `jaehyun/` 삭제 시점(팀원 허락 후)
5. 저장소 공개 여부(대회 요건 확인 후 private 복귀)

## 하지 말 것
- `.env`, 키, 서버 주소, 혈당 CSV·그래프 이미지·음식 사진 커밋
- 서버 접속·배포 설정 변경(요청 없이), DB 포트 개방, `AI_use_organize/` 수정
- 계약서(`docs/ml-service-contract.md`)를 코드보다 나중에 고치기(변경은 문서 먼저, 필드 삭제·이름·타입 변경은 사전 협의)
