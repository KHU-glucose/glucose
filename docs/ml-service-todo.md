# ml-service TODO (팀원 전달용)

백엔드(Dave/Claude Code) 작업 중 확인된 항목입니다. 코드는 수정하지 않았습니다. `docs/ml-service-contract.md`가 계약 정답입니다.

- [ ] `/health` 응답의 버전 오타 수정: `"1.0.0."` → `"1.0.0"`
- [ ] `X-Request-Id` 헤더가 없을 때 422로 응답되는 문제: 인증(`X-Internal-Token`) 검사가 먼저 실행되도록 순서 조정. `X-Request-Id`는 선택값으로 두고 없으면 서버가 생성해서 응답에 사용
- [ ] 검증 오류(422 등)를 계약 형식 `{code, message, request_id}`로 통일
- [ ] `POST /v1/graph/parse`가 현재 가짜 응답만 반환함 — 실제 구현 또는 최소한 고정된 가짜 응답으로 Spring 연동 가능하게 유지
- [ ] pytest 테스트 추가
- [ ] `requirements.txt` 분리(운영/개발), Dockerfile non-root 사용자, `.dockerignore` 추가

후순위:
- [ ] EXIF 회전 처리
- [ ] threadpool/동시성 설정 점검
- [ ] 의존성 경량화 (서버 메모리 2GB를 Spring·PostgreSQL과 공유)
