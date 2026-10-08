#!/usr/bin/env bash
# DB 백업 복구 테스트 (backend-plan B8 "백업 복구 테스트").
# R2의 백업(backup.sh가 만든 db/glucose-YYYYmmdd-HHMM.sql.gz)을 내려받아 **임시 PostgreSQL 컨테이너**에 복구하고,
# 스키마 버전과 테이블별 행 수를 운영 DB와 비교한다. 운영 DB는 읽기(행 수 조회)만 하고 절대 쓰지 않는다.
#
# 사용 (서버에서):  cd /opt/glucose && bash restore-test.sh [백업 파일 이름]
#   파일 이름을 안 주면 가장 최근 백업을 쓴다.
set -euo pipefail

GLUCOSE_DIR="${GLUCOSE_DIR:-/opt/glucose}"
cd "$GLUCOSE_DIR"
set -a; source .env; set +a
: "${POSTGRES_USER:?.env에 POSTGRES_USER가 없습니다}" "${POSTGRES_DB:?.env에 POSTGRES_DB가 없습니다}"
: "${R2_ENDPOINT:?.env에 R2_ENDPOINT가 없습니다}" "${R2_BACKUP_BUCKET:?.env에 R2_BACKUP_BUCKET이 없습니다}"
: "${R2_ACCESS_KEY_ID:?.env에 R2_ACCESS_KEY_ID가 없습니다}" "${R2_SECRET_ACCESS_KEY:?.env에 R2_SECRET_ACCESS_KEY가 없습니다}"

TABLES="app_user refresh_token intake_photo intake intake_item insulin_event food_catalog glucose_graph_upload glucose_reading job education_card"
WORK=$(mktemp -d /tmp/glucose-restore-XXXXXX)
NAME="glucose-restore-test-$$"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

aws() {
  docker run --rm \
    -e AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
    -e AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
    -e AWS_DEFAULT_REGION=auto \
    -v "$WORK:$WORK" \
    amazon/aws-cli "$@" --endpoint-url "$R2_ENDPOINT"
}

echo "== 1. 백업 목록 (최근 3개) =="
LISTING=$(aws s3 ls "s3://${R2_BACKUP_BUCKET}/db/" | grep -E 'glucose-[0-9]{8}-[0-9]{4}\.sql\.gz$' | sort -k4 || true)
if [ -z "$LISTING" ]; then
  echo "실패: R2에 백업 파일이 하나도 없습니다. backup.sh/crontab/backup.log를 확인하세요."
  exit 1
fi
echo "$LISTING" | tail -3
FILE="${1:-$(echo "$LISTING" | tail -1 | awk '{print $4}')}"

# aws-cli 컨테이너는 TZ 설정이 없어서 목록 시각을 UTC로 출력한다.
LATEST_AT=$(echo "$LISTING" | tail -1 | awk '{print $1" "$2}')
AGE_HOURS=$(( ( $(date +%s) - $(date -u -d "$LATEST_AT" +%s) ) / 3600 ))
if [ "$AGE_HOURS" -gt 36 ]; then
  echo "경고: 가장 최근 백업이 약 ${AGE_HOURS}시간 전입니다. 매일 새벽 4시 백업이 돌고 있는지 확인하세요."
fi

echo "== 2. 내려받기: $FILE =="
aws s3 cp "s3://${R2_BACKUP_BUCKET}/db/$FILE" "$WORK/$FILE" --only-show-errors
gzip -t "$WORK/$FILE"
echo "크기: $(du -h "$WORK/$FILE" | cut -f1)"

echo "== 3. 임시 컨테이너에 복구 (네트워크 없음, 운영 DB와 무관) =="
START=$(date +%s)
docker run -d --name "$NAME" --network none --memory 256m \
  -e POSTGRES_PASSWORD=restore-test -e POSTGRES_DB=restore postgres:16-alpine >/dev/null
# 초기화 중에 잠깐 떴다 꺼지는 임시 서버에 복구하지 않도록 초기화 완료 로그를 기다린다.
for _ in $(seq 1 60); do
  if docker logs "$NAME" 2>&1 | grep -q "PostgreSQL init process complete" \
     && docker exec "$NAME" pg_isready -U postgres -d restore >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
gunzip -c "$WORK/$FILE" | docker exec -i "$NAME" psql -v ON_ERROR_STOP=1 -q -U postgres -d restore >/dev/null
echo "복구 완료: $(( $(date +%s) - START ))초"

echo "== 4. 비교 =="
restored_sql() { docker exec "$NAME" psql -U postgres -d restore -tAc "$1"; }
prod_sql() { docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"; }

VERSION_SQL="SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"
RESTORED_VERSION=$(restored_sql "$VERSION_SQL")
PROD_VERSION=$(prod_sql "$VERSION_SQL")
echo "스키마 버전: 백업 V${RESTORED_VERSION} / 운영 V${PROD_VERSION}"

printf "%-22s %10s %10s\n" "테이블" "백업" "운영(지금)"
for table in $TABLES; do
  printf "%-22s %10s %10s\n" "$table" \
    "$(restored_sql "SELECT count(*) FROM $table" 2>/dev/null || echo 없음)" \
    "$(prod_sql "SELECT count(*) FROM $table" 2>/dev/null || echo 없음)"
done

if [ -z "$RESTORED_VERSION" ]; then
  echo "실패: 복구한 DB에 flyway_schema_history가 없습니다."
  exit 1
fi
echo
echo "결과: 복구 성공. 백업($FILE)을 실제로 되살릴 수 있습니다."
echo "행 수 차이는 백업 이후 생긴/지운 데이터만큼 나는 게 정상입니다. 스키마 버전이 다르면 백업 이후 배포된 마이그레이션이 있다는 뜻입니다."
