# 음식 인식 평가

현재 실제 사진은 없습니다. `labels.example.json`은 형식 예시이며 평가 데이터가 아닙니다.
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

`count`를 셀 수 없으면 null 또는 생략하고 개수 평가에서 제외합니다. 개수가 있으면
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


## 프롬프트 버전 보존과 비교

현재 후보: baseline-v1, rules-v2, examples-v3, focused-v4. 기본값: examples-v3.
원문은 food_prompts.py에 보존하며 --prompt-version으로 선택합니다.
평가에는 프롬프트 원문·해시·사진·스키마·설정을 기록합니다.
make eval은 기존 완료 목표와 데이터 조건을 검사하며 미측정은 통과로 취급하지 않습니다.
개발용 10장의 결과는 최종 정확도가 아니며, 새 후보를 추가했다고 성능 향상이 입증된 것은 아닙니다.
