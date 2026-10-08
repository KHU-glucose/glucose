# 음식 인식 평가

실제 사진과 정답은 로컬에만 보관합니다. `labels.example.json`은 형식 예시이며 평가 데이터가 아닙니다.
AI가 생성한 정답 대신, 사진을 확인한 사람이 음식명·개수 정답을 먼저 작성하세요.

## 준비

1. JPEG/PNG 사진을 `eval/food/images/`에 넣습니다. 한 장당 최대 8MB입니다.
2. `labels.example.json`을 `labels.json`으로 복사하고 실제 사진의 정답으로 바꿉니다.
3. 정답은 AI 결과를 보기 전에 확정합니다. 사진에 보이는 음식은 빠짐없이 기록합니다.

```json
[
  {
    "file": "001.jpg",
    "is_food_photo": true,
    "items": [
      {"name": "사과", "count": 2, "unit": "개", "category_hint": "SNACK"}
    ]
  },
  {
    "file": "002.jpg",
    "is_food_photo": false,
    "items": []
  }
]
```

식사류(MEAL)는 음식명만 평가하고 `count=null`로 둡니다. 간식 개수를 셀 수 없으면
null 또는 생략하고 개수 평가에서 제외합니다. 개수가 있으면
`unit`도 필요합니다. `aliases`에 미리 허용한 동의어를 넣을 수 있습니다. 예: 흰쌀밥의
동의어 쌀밥. 의미가 다른 음식명은 동의어로 추가하지 마세요. 정답과 동의어는 실행 후
점수를 올리기 위해 바꾸지 말고, 정답 오류 수정은 별도 기록으로 남겨야 합니다.

`context`는 실제 사용자가 선택한 상황을 재현할 때만 사용합니다. `source`, `license`는
사진 출처 기록용 선택 필드입니다. 평가용 정답은 모델 입력에 전달되지 않습니다.

## 실행 (저장소 루트)

```powershell
python -m pip install -r ml-service/requirements-dev.txt
python ml-service/eval_food.py --check
python ml-service/eval_food.py --limit 10
```

`--check`는 파일과 정답만 검사하고 API를 호출하지 않습니다. 실제 실행에는 루트
`.env`의 OPENAI_API_KEY가 필요합니다. 모델은 FOOD_MODEL, OPENAI_MODEL 순서로
선택되며 `--model`로 명시할 수 있습니다. 기본 실행은 정답 배열의 처음 10장입니다.
전체 평가를 하려면 사진 수 이상의 `--limit`을 지정하세요. API 사용료가 발생합니다.

서비스와 동일한 이미지 검증·768px 축소와 FoodRecognizer를 실행합니다. HTTP 인증이나
백엔드 통합을 검사하는 도구는 아닙니다. 사진을 순차 실행하며 각 결과를 로컬 보고서에
저장합니다. 기존 보고서를 덮어쓰지 않습니다.

## 점수 읽기

| 지표 | 계산 |
| --- | --- |
| food_name_recall | 맞게 찾은 음식 / 정답 음식. 누락을 측정 |
| food_name_precision | 맞게 찾은 음식 / AI가 반환한 음식. 잘못 추가한 음식을 측정 |
| food_name_f1 | 음식명 precision과 recall을 함께 고려한 점수 |
| count_and_unit_accuracy | 음식명·개수·단위 모두 맞춘 음식 / 개수 정답이 있는 음식 |
| food_photo_accuracy | 음식 여부를 맞춘 사진 / 전체 시도 사진 |
| nonfood_rejection_rate | 비음식 판별 및 빈 items 반환 / 비음식 사진 |
| category_hint_accuracy | 음식명과 분류 힌트를 모두 맞춘 음식 / 분류 정답이 있는 음식 |
| successful_response_rate | 검증된 응답을 받은 사진 / 전체 시도 사진 |
| median_success_latency_ms | 성공 요청의 전체 처리 시간 중앙값 |

같은 음식명이 여러 번 나오면 일대일 대응으로 채점합니다. API 실패도 정확도의 분모에
포함됩니다. 정답이 없는 지표는 rate=null이며 100%로 간주하지 않습니다. `correct`,
`total`은 맞춘 수와 전체 수, `rate`는 0~1 사이 비율입니다. 0.85는 85%입니다.

보고서는 `eval/food/reports/`에 생성됩니다. 각 사진의 정답·AI 결과·오류·처리 시간과
모델명, 프롬프트 및 정답 파일 해시를 포함합니다. 토큰 합계는 성공 응답의 SDK 사용량이며
실패나 재시도 비용을 모두 반영하지 않을 수 있어 정확한 청구 금액으로 쓰면 안 됩니다.

사진, 실제 정답 파일, 보고서는 Git에서 제외했습니다. example과 평가 코드만 공유합니다.

## 프롬프트 전후 비교와 완료 기준

프롬프트 원문은 [food_prompts.py](../../ml-service/food_prompts.py)에 버전별로 보관합니다.
`baseline-v1`은 기존 평가의 원문, `rules-v2`는 상세 규칙 실험,
`examples-v3`는 핵심 규칙과 짧은 정책 예시 실험입니다. `focused-v4`는 대표 요리명
선택 문단만 교체했고, `contrast-v5`는 v3의 이름 선택 문단에 시각적 구별 단서만 추가했습니다.
사진 예시를 학습시킨 것은 아닙니다.
실제 서비스 기본 버전은 `ACTIVE_PROMPT_VERSION`을 확인하세요.
추가 반복 평가에서 v4의 향상은 입증되지 않아 현재 기본값은 v3로 유지했습니다.
[추가 실험 증빙](evidence/prompt-refinement-2026-10-07.json)을 함께 확인하세요.

```powershell
python ml-service/eval_food.py --prompt-version baseline-v1 --limit 10
python ml-service/eval_food.py --prompt-version rules-v2 --limit 10
python ml-service/eval_food.py --prompt-version examples-v3 --limit 10
python ml-service/eval_food.py --prompt-version focused-v4 --limit 10
python ml-service/eval_food.py --prompt-version contrast-v5 --limit 10
```

각 실행은 API 사용료가 발생합니다. 같은 정답·사진·모델·detail·축소 크기·출력 스키마를
유지하고 프롬프트만 비교하세요. 프롬프트를 수정한 뒤에는 새 버전 이름을 사용하며,
이미 실행한 버전 원문과 결과 파일은 덮어쓰지 않습니다. 보고서에는 프롬프트 원문과 버전,
프롬프트·스키마·정답·원본 사진 해시, 설정, 각 응답, 처리 시간과 토큰 수가 기록됩니다.
이전 10장을 개선에 참고했다면 개발용 데이터이며, 최종 성능은 별도 미사용 사진으로 검증합니다.

| 계약서 목표 | 현재 측정 방법 |
| --- | --- |
| 음식 이름 85% | `food_name_recall`: 정답 음식명과 사전에 정한 동의어 일치. precision/F1도 함께 보고 오검출 확인 |
| 낱개 개수 80% | `snack_count_and_unit_accuracy`: 정답 분류가 SNACK/FAST_SUGAR인 음식의 이름·개수·단위 모두 일치 |
| 처치 음식 구분 95% | `fast_sugar_detection_accuracy`: 음식명이 맞고 FAST_SUGAR 여부도 맞는 비율. 양성 recall·음성 정확도도 별도 기록 |
| 비음식 거르기 95% | `nonfood_rejection_rate`: false와 빈 items를 모두 반환 |
| 스키마 통과 99% | 아직 순수 통과율 미계측. `successful_response_rate`는 스키마를 만족한 최종 응답 비율이며 API 실패도 분모에 포함 |
| 응답 중앙값 3초 이하 | `median_attempt_latency_ms`: 실패·재시도 포함, 이미지 준비부터 최종 결과까지. HTTP 인증·네트워크·큐 대기는 제외 |
| 장당 $0.003 이하 목표 | 토큰 합계만 측정. 실패·재시도·캐시·단가를 포함한 실제 청구 비용은 별도 확인 |

FAST_SUGAR는 계약서의 기록용 분류입니다. 사탕·주스·초콜릿·젤리·포도당 식별이
의학적 치료 적합성을 뜻하지는 않습니다. 예상 라벨에 분류가 없거나 양성/음성 중 한쪽이
없으면 이 기준을 통과한 것으로 취급하지 않습니다. 미측정 항목은 자동 PASS가 아닙니다.

계약서의 데이터 구성은 한식 식사 25, 간식 15, 처치 음식 20, 포장 식품 10,
비음식 10 이상입니다. 총량은 60~100장이지만 그룹을 배타적으로 구성하면 최소 80장입니다.
이 도구는 보수적으로 배타적 `dataset_group`을 사용합니다. 그룹은 MEAL/SNACK/HYPO/PACKAGED/NONFOOD 중 하나로,
포장 간식은 그 사진을 어느 그룹에 배정할지 미리 결정하세요. 이는 음식의 `category_hint`와 별개입니다.
`annotation_complete=true`는 해당 사진의 모든 보이는 음식에 정답을 작성했다는 뜻이며,
폴더 이름으로 주된 음식만 라벨링한 사진에는 설정하지 않습니다.

```json
{
  "file": "cookie.jpg", "is_food_photo": true,
  "dataset_group": "SNACK", "annotation_complete": true,
  "items": [{"name": "쿠키", "count": 3, "unit": "개", "category_hint": "SNACK"}]
}
```

`make eval`은 위 목표와 데이터 조건을 확인하며 미달·미측정이면 종료 코드 1입니다.
실행은 완료됐지만 목표에 못 미친 경우도 보고서는 보존됩니다. 기본은 개발용 비교입니다.

```text
make eval PROMPT=examples-v3 LIMIT=100 SPLIT=holdout
```

Windows에 make가 없으면 같은 평가를 다음처럼 실행합니다.

```powershell
python ml-service/eval_food.py --prompt-version examples-v3 --limit 100 --split holdout --require-targets
```

`--split holdout`은 실제로 튜닝에 사용하지 않은 사진을 넣었을 때만 지정하세요. 플래그만
바꾼다고 검증용 데이터가 되는 것은 아닙니다. 현재 스키마 순수 통과율과 실제 청구 비용은
자동 계측되지 않아 `completion_passed`는 완전한 완료 판정을 내릴 수 없습니다.
결과가 좋은 10장만으로 최종 목표 달성을 주장하지 않습니다. 그래프 파싱 평가는 이 도구 범위 밖입니다.

## 혈당 관리 중심 추가 평가 (2026-10-08)

새 기준·그룹별 근거·라벨 작성법·실험 절차는 [혈당 관리 평가 기준](glucose-management-policy.md)을
확인하세요. `labels.management.example.json`은 새 라벨 형식 예시이며 실제 평가 데이터가 아닙니다.

기존 음식명/개수 점수는 그대로 유지하고, 사람이 미리 작성한 `management_group`,
`priority_food`, `requires_uncertainty`로 별도의 기록 그룹·중요 음식 미식별·음식명 low 지표를
계산합니다. 상위 이름은 부분 분류로만 기록하며 영양 동등이나 동일 혈당 반응으로 간주하지 않습니다.
그룹 precision은 `management_annotation_complete=true`인 사진에서만 측정합니다.
보고서에 정책 버전·해시·전체 스냅샷·사진별 혼동을 보존합니다. 새 필드가 없으면 새 정답 지표는 미측정입니다.

`glucose-v6`는 실험용 프롬프트이고 운영 기본값은 `examples-v3`입니다.
API 스키마·모델·detail·축소 크기·식사 count 정책은 바꾸지 않았습니다.

```powershell
python ml-service/eval_food.py --prompt-version glucose-v6 --check
python ml-service/eval_food.py --prompt-version glucose-v6 --limit 100
```

두 번째 실행은 API 비용이 발생합니다. 새 그룹 지표는 기존 완료 목표를 대체하지 않으며
목표치가 합의되지 않아 자동 PASS 판정에 넣지 않습니다. 실제 사진에서 개선됐다는 증거는 아직 없습니다.

## 사진 수집

처음 10장은 식사 3장, 간식 2장, 사탕·초콜릿·주스 등 3장, 비음식 2장으로 시작하세요.
이는 소규모 동작 확인이며 전체 정확도의 증거가 아닙니다. 이후 한식 식사, 포장 제품,
여러 음식, 흐림·가림 등 실제 앱 사용 조건을 포함해 60~100장으로 확장하세요.

- 직접 촬영: 실제 사용할 휴대폰과 음식으로 찍어 개수·포장 제품 정답을 확실히 기록합니다.
- AI Hub 음식 이미지 및 영양정보: 한식 사진을 추가할 때 참고. 다운로드 승인 및
  외부 AI API에 사진을 전송해도 되는지 등 해당 데이터의 이용 조건을 확인하세요.
  https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=120&dataSetSn=74
- Wikimedia Commons: 이미지별 출처·작성자·라이선스를 기록하세요.
  https://commons.wikimedia.org/wiki/Commons:Simple_media_reuse_guide

공개 데이터셋의 음식 분류 라벨은 우리 개수·단위 정답과 같지 않으므로 직접 보완해야 합니다.
공개 인터넷 사진만으로 얻은 점수가 새로 촬영한 실제 사진 성능을 보장하지는 않습니다.
