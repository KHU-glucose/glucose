# CLAUDE.md — 혈당 기록·리포트 앱 (KHU-glucose/glucose)

## 프로젝트 한 줄 요약
CGM(연속혈당측정기, 예: FreeStyle Libre)을 쓰며 인슐린을 맞는 사람이 **식사/저혈당 처치 사진과 인슐린 기록**을 남기면,
**일일 그래프 이미지**에서 읽은 혈당 곡선과 맞춰 일일·주간 리포트를 만들어 주는 iOS 앱 + 백엔드.
AI는 "무엇을 몇 개 먹었는지"까지만 인식한다. 당류·처치 판정·반동 판정 같은 숫자와 판단은 **코드가** 계산한다. 인슐린 용량 조언은 절대 하지 않는다.

## 저장소 구조 (모노레포)
```
backend/      Spring Boot 3.5, Java 21, Gradle  ← Dave 담당
ml-service/   FastAPI(Python 3.12)               ← 팀원 담당 (음식 인식: OpenAI Vision, 그래프 파싱: OpenCV+OCR)
jaehyun/      팀원의 독립 프로토타입 (계약과 다름: 영양 수치 반환. 팀원 허락 후 삭제 예정, 그 전엔 건드리지 않음)
ios/          SwiftUI 앱 (iOS 17+, Bundle ID com.glubee.glubee)
.github/workflows/deploy.yml   main push → test → build(amd64) → GHCR → SSH 배포
docs/ml-service-contract.md    ml-service API 계약서 (정답 문서)
AI_use_organize/               경진대회 제출용 AI 활용 기록 (건드리지 말 것)
```

## 담당과 규칙
- Dave: 백엔드 + 인프라 + iOS, **머지 관리자**. main에 직접 푸시하지 않고 브랜치 → PR → 머지. main에 머지되면 자동 배포된다.
- ml-service 계약 변경은 코드보다 `docs/ml-service-contract.md`를 먼저 고친다. 필드 삭제·이름·타입 변경은 사전 협의.
- 응답 필드는 snake_case. 오류 응답은 `{code, message, request_id}`.

## 백엔드 (backend/)
- 패키지 `com.glucoselog`. Spring Web, Data JPA, Validation, Security, Actuator, Flyway, PostgreSQL.
- 스키마는 **Flyway만** 변경 (`src/main/resources/db/migration`, `V1__init.sql`부터). `ddl-auto: validate`.
- Security: `/actuator/health`, `/v1/auth/**`만 공개, 나머지는 401. 로그인은 Sign in with Apple → 자체 JWT 발급 예정.
- 테스트는 Testcontainers(PostgreSQL 16). Docker가 켜져 있어야 한다. `ext['testcontainers.version'] = '1.21.4'` 고정 (Docker Engine 29 호환).
- 명령: `./gradlew test`, `./gradlew bootRun` (로컬 DB: `docker compose -f docker-compose.local.yml up -d`).
- 예정 테이블: glucose_reading, intake_photo, insulin_event, food_catalog, education_card, episode, daily/weekly_report, job. 무거운 처리는 job 테이블 + `@Scheduled` 워커로 비동기 처리하고, 앱 요청 스레드는 ml-service 응답을 기다리지 않는다.

## 분석 규칙 (핵심 도메인)
- 에피소드: 60~90분 안의 섭취는 하나로 묶는다. 분석 창: 식사 3~5시간, 저혈당 처치 2시간(직전 혈당 70 미만이면 자동으로 처치로 분류), 술 12시간.
- 혈당 데이터: 리브레 일일 그래프 이미지 1장 = 하루. 끊긴 구간은 **보간하지 않고 null/MISSING**으로 둔다. coverage_ratio 0.1 미만이면 422.
- 숫자는 코드, 문장만 AI. 근거 건수를 함께 보여준다.

## ml-service (계약은 docs/ml-service-contract.md)
- `POST /v1/food/recognize`, `POST /v1/graph/parse`, `GET /health`. 내부 토큰 `X-Internal-Token`.
- 영양 숫자(g, kcal)는 응답에 넣지 않는다. 모델 선택은 ml-service 담당 몫이지만 API 형식은 고정.

## 인프라 (AWS Lightsail, 서울)
- 서버 1대(Ubuntu 24.04, x86 → 이미지는 **amd64만**). Docker Compose: caddy, app, ml-service, postgres. 외부 포트는 22/80/443만. DB 포트는 열지 않는다.
- 서버 경로 `/opt/glucose` (`docker-compose.yml`, `Caddyfile`, `.env`, `backup.sh`). 서버 주소와 SSH 키는 GitHub Secrets(`LIGHTSAIL_HOST`, `LIGHTSAIL_SSH_KEY`)에 있고 **이 저장소에 쓰지 않는다**.
- 이미지는 `ghcr.io/khu-glucose/glucose-app`, `glucose-ml` (조직 이름이 대문자라 소문자로 변환해서 사용).
- 사진·백업은 Cloudflare R2(`glucose-photos`, `glucose-backups`, 비공개). 매일 새벽 4시 DB 백업(cron → R2, 30일 보관).
- 재시작 정책 `unless-stopped`. 서버 재부팅 후 자동 복구된다.
- 백업 복구 테스트·장애 시 복구 절차: `infra/README.md`, `infra/restore-test.sh` (임시 컨테이너에만 복구, 운영 DB는 안 건드림).

## 보안·개인정보 (반드시 지킬 것)
- `.env`, API 키, `.pem`, 혈당 CSV·그래프 이미지·PDF·음식 사진은 **절대 커밋하지 않는다.** 테스트 데이터는 팀 비공개 드라이브로만 공유.
- 로그에 혈당값, 사진 URL, 토큰, 이미지/AI 응답 원문을 남기지 않는다.
- 이 저장소가 공개 상태일 수 있으니 서버 IP, 도메인, 키를 코드·문서에 쓰지 않는다.

## 현재 상태와 다음 할 일
- 백엔드 완료(머지): A1, B0(`docs/backend-api.md`), B1(Sign in with Apple + JWT), B2(사진 업로드 R2 presigned URL), B3(job 워커 + ml-service 연동), B4(intake/insulin_event CRUD, `food_catalog` 시드, 음식 인식 결과 조회), B5(혈당 그래프 업로드·분석), B6(에피소드/반동 판정 `EpisodeAnalyzer`), B7(일일·주간 리포트). ml-service 실제 음식 인식 + 그래프 파서, 모델명 버그 수정(#20)도 머지됨.
- iOS 완료(머지): I0(뼈대), I1(기록 화면), I2(Sign in with Apple), I3(사진 촬영), I4(R2 업로드), I5(인식 결과 확인·수정), I6(인슐린 기록), I7(기록 목록 Live 전환), I8(그래프 업로드), I9(일일 리포트), I10(주간 리포트).
- 진행: **상세 작업 순서는 `docs/backend-plan.md`**(백엔드), `ios-plan.md`(iOS, Downloads에 있던 원본). 운영에서 음식 사진 인식(잡채)·그래프 업로드(96칸)까지 실제로 동작 확인(2026-10-08).
- B8 진행: **백업 복구 테스트 완료**(2026-10-08 서버에서 `infra/restore-test.sh`로 실제 백업 복구 성공, 3초). 매일 04:00 백업 정상. 남은 것: 레이트 리밋, OpenAPI, 로그 민감정보 점검, 백업 실패 알림 켜기.
- 열린 작업: 계정 삭제 시 R2 파일·job 정리(#37). 음식 인식 대분류는 제 안(#34: name을 대분류로)과 팀원 안(#38: name 유지 + `food_group` 필드)이 겹쳐 방향 결정 필요.
- iOS 빌드 확인: 이 Mac에 Xcode 26.6이 있음(xcode-select는 CommandLineTools라 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`로 지정). `xcodebuild -scheme glucose -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`. 시뮬레이터 런타임은 iOS 26.5뿐이라 iOS 17 동작은 직접 확인 못 함.
- 음식 인식 모델 ID는 `gpt-6-luna`(서버 `.env` `FOOD_MODEL`). `luna`는 없는 ID — 2026-10-08 장애 원인(#35에서 되돌림).
- **스택 PR 주의**: 아래 PR을 위 PR의 브랜치를 base로 쌓으면, 위 PR이 main에 머지된 뒤 아래 PR의 base를 main으로 바꾸지 않고 머지했을 때 main에 안 들어간다(#17~#19, #22~#23에서 실제로 발생 → landing PR로 복구). 저장소 설정 "Automatically delete head branches"를 켜면 GitHub가 자동으로 base를 옮겨준다.
- Bundle ID는 `com.glubee.glubee`로 확정(앱 실제 이름 Glubee). `com.glucose.glucose`는 쓰지 않음 — 저장소/패키지명(`com.glucoselog`)은 그대로 유지.
- 비용: AWS 크레딧(Free Tier)으로 Lightsail $12/월 차감 예정. 크레딧 소진 시점 확인 필요.
- 결정됨: 그래프 재업로드는 **덮어쓰기**. 반동 판정은 **그래프의 ABOVE_RANGE 플래그 재사용**(새 숫자 임계값을 코드에 고정하지 않음, 사용자별 목표범위 자동 반영). 리포트 문장(AI 요약)은 **지금은 안 만듦** — 숫자/건수만 반환(ml-service에 리포트용 엔드포인트가 없어서, 필요해지면 계약 문서부터 고치고 팀원과 협의).
- 미결정: 저장소 공개 여부(경진대회 요건 확인 후 private 전환 검토 — 지금은 public 유지), 그래프 파서 담당 범위/진행 상황(현재는 팀원이 실제 OpenCV+OCR 구현 완료).

## 작업 방식 선호
- 결론부터, 간결하게. 한국어. 확신이 없는 사실(버전, 모델명 등)은 확인된 것처럼 쓰지 않는다.
- 위험한 작업(삭제, 서버 변경, 강제 푸시)은 실행 전에 확인한다.
