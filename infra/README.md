# 운영 스크립트

서버 경로는 `/opt/glucose`. 서버 주소·키는 저장소에 쓰지 않는다(`CLAUDE.md` 인프라 절).

## DB 백업

서버의 `backup.sh`가 매일 새벽 4시(cron) `pg_dump --no-owner | gzip` 결과를
R2 `${R2_BACKUP_BUCKET}/db/glucose-YYYYmmdd-HHMM.sql.gz`에 올린다. 로그는 `/opt/glucose/backup.log`, 30일 보관은 R2 lifecycle 규칙.

- 업로드가 실패하면 스크립트는 멈추지만(`set -euo pipefail`) **알림은 꺼져 있다**(crontab 예시 주석). 실패를 알려면 알림을 켜야 한다.

## 복구 테스트 — `restore-test.sh`

최근 백업을 **임시 PostgreSQL 컨테이너**(네트워크 없음)에 복구하고 스키마 버전·테이블별 행 수를 운영과 비교한다.
운영 DB는 행 수 조회만 하고 쓰지 않는다. 끝나면 임시 컨테이너와 내려받은 파일을 지운다.

```bash
# 서버에서 (처음 한 번 스크립트를 /opt/glucose에 복사)
cd /opt/glucose
bash restore-test.sh                 # 가장 최근 백업
bash restore-test.sh glucose-20261008-0400.sql.gz   # 특정 백업
```

- `.env`의 `POSTGRES_USER`, `POSTGRES_DB`, `R2_ENDPOINT`, `R2_BACKUP_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`를 쓴다.
- 가장 최근 백업이 36시간보다 오래됐으면 경고한다(매일 백업이 안 돌고 있다는 뜻).
- 행 수 차이는 백업 이후 생긴/지운 데이터만큼 나는 게 정상이다.

## 실제 장애 시 복구 (수동, 되돌릴 수 없음 — 실행 전 Dave 확인)

1. `restore-test.sh <파일>`로 그 백업이 복구되는지 먼저 확인한다.
2. 쓰기를 멈춘다: `docker compose stop app`
3. 만약을 위해 지금 DB를 한 번 더 덤프해 둔다: `docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner | gzip > /tmp/before-restore.sql.gz`
4. DB를 비우고 백업을 넣는다:
   ```bash
   docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres \
     -c "DROP DATABASE \"$POSTGRES_DB\";" -c "CREATE DATABASE \"$POSTGRES_DB\" OWNER \"$POSTGRES_USER\";"
   gunzip -c <백업파일>.sql.gz | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"
   ```
5. 앱을 다시 켠다: `docker compose start app` (백업 이후 추가된 마이그레이션은 Flyway가 적용한다)

백업은 하루 한 번이라 마지막 백업 이후의 기록은 복구되지 않는다. R2의 사진·그래프 파일은 DB 백업에 포함되지 않는다(R2에 그대로 있음).
