# 혈당 관리 중심 음식 인식·평가 기준 v1

작성일: 2026-10-08. 상태: 개발용 평가 규칙과 실험용 프롬프트 구현 완료, 실제 사진 성능 미검증.

## 목적과 범위

총 탄수화물·당류와 관련된 음식의 누락 및 조리법·제품 종류의 혼동을 우선 관찰한다.
음식명 정확도는 그대로 유지하고 별도의 기록 분류 점수를 추가한다.
당류만 보면 전분을 놓칠 수 있다. 사진만으로 당 함량, 실제 섭취량, 혈당 상승량,
인슐린 용량이나 저혈당 치료 적합성을 확정하지 않는다.

이번 변경은 평가 도구와 실험용 프롬프트에 한정된다. 운영 API 응답 형식, 모델,
detail=low, 최대 768px, 식사류 count=null, 섭취 여부 null은 유지한다.
운영 프롬프트는 examples-v3이며 glucose-v6는 평가할 때 명시적으로 선택한다.
팀의 기존 그릇 수 정책과의 차이는 이번 변경에서 별도로 해결하지 않는다.

## 음식 그룹 기준표

실행 가능한 원본은 `ml-service/food_management_policy.json`이다. 같은 그룹은 기록용
채점 분류이며 동일한 혈당 반응이나 전체 영양성분의 동등성을 뜻하지 않는다.

| 그룹 ID | 확정 이름 | 상위 이름: 부분 분류만 | 근거와 제한 |
| --- | --- | --- | --- |
| FISH_PLAIN_GRILLED_REFERENCE | 갈치구이, 고등어구이 | 생선구이 | 아래 식약처 참고 요리의 탄수화물·당류 비교. 사람이 일반 구이임을 확인한 사진만 |
| FISH_SWEET_GLAZED | 달콤한 양념 생선구이, 데리야키 생선구이 | 양념 생선구이 | 별도 조리법 기록. 이름만으로 당 함량을 확정하지 않음 |
| FISH_BREADED_FRIED | 생선까스, 생선가스, 튀김옷 있는 생선튀김 | 생선튀김 | 튀김옷이 확인되는 별도 기록 분류 |
| WHITE_RICE | 흰쌀밥, 쌀밥 | 밥 | 동일 음식의 이름만 묶음. 잡곡밥·볶음밥까지 영양 동등 처리하지 않음 |
| REGULAR_COLA | 일반 콜라, 설탕 함유 콜라 | 콜라 | 제품 정보로 확인한 일반 제품. 사진에 표시 없으면 추측 금지 |
| ZERO_COLA | 제로 콜라, 무설탕 콜라 | 콜라 | 읽히는 제품 표시가 근거. 모든 제로 제품의 영양 동등성/혈당 무영향을 뜻하지 않음 |

카탈로그 밖의 이름은 미해결이다. 부분 문자열, HIGH_CARB/FAST_SUGAR 태그나
category_hint로 임의 그룹을 추론하지 않는다. 사탕·초콜릿·주스 등 서로 다른 음식은
FAST_SUGAR라는 기존 계약상 분류가 같아도 영양 동등 그룹으로 합치지 않는다.
면·빵·과일·간식은 우선 priority_food로 누락을 평가하고, 영양 근거 없이 넓은 그룹에 넣지 않는다.

### 생선 참고값

식약처 외식 영양성분 자료집 제2권(2013), 인쇄 페이지 12-15의 100g 열을 확인했다.

| 참고 음식 | 탄수화물 g/100g | 당류 g/100g | 지방 g/100g | 단백질 g/100g |
| --- | --- | --- | --- | --- |
| 갈치구이 | 0.1 | 0.1 | 10.3 | 24.8 |
| 고등어구이 | 0.7 | 0.1 | 18.9 | 23.7 |

두 참고 요리의 탄수화물은 0.6g 차이, 당류는 제시 정밀도에서 같다.
이는 초기 개발용 비교 그룹의 근거이지 임상적 허용 오차가 아니다. 지방·단백질 차이는 유지한다.
조리법·재료·계절·중량이 달라질 수 있고 실제 섭취량도 모른다. 다른 어종으로 자동 확대하거나
이 참고값을 사진의 영양 측정값처럼 API에 반환하지 않는다.

‘생선구이’는 어종과 세부 조리법이 불명확하므로 완전한 그룹 정답 대신 부분 분류로 기록한다.
예를 들어 갈치구이를 생선구이로 답하면 음식명 점수 0, 확정 그룹 점수 0, 부분 분류 점수 1이다.
고등어구이로 답하면 음식명 점수 0, 이 개발용 그룹 점수 1이다. 양념구이·튀김과는 그룹이 다르다.

## 정답 라벨 작성

실제 사진을 확인한 사람이 AI 결과를 보기 전에 아래 선택 필드를 작성한다.
기존 라벨을 자동으로 변경하거나 폴더 이름으로 조리법·당 함량을 확정하지 않는다.

```json
{
  "file": "plain-fish.jpg",
  "is_food_photo": true,
  "annotation_complete": true,
  "management_annotation_complete": true,
  "items": [{
    "name": "갈치구이",
    "management_group": "FISH_PLAIN_GRILLED_REFERENCE",
    "priority_food": false,
    "requires_uncertainty": false,
    "count": null, "unit": "개", "category_hint": "MEAL"
  }]
}
```

- `management_group`: 기준표에 있는 기록 그룹. 근거가 없거나 그룹이 없으면 null/생략.
- `priority_food`: 밥·면·빵·간식·음료 등 이번 평가에서 누락을 중요하게 볼 음식에 true.
  성능 결과를 보고 선택 대상을 바꾸지 말고, 라벨 작성 규칙을 개발/검증 세트에 동일하게 적용한다.
- `requires_uncertainty`: 세부 어종·일반/제로 여부 등이 사진만으로 확정되지 않아 음식명
  confidence=low가 필요한 경우 true. 실제로 알 수 없는 세부 종류는 정답 이름에도 만들지 않는다.
- `management_annotation_complete`: 사진의 모든 음식에 그룹 라벨이 있고 전체 음식 정답도
  완비됐을 때만 true. 그룹 정밀도의 분모를 안전하게 계산하기 위한 별도 표시다.
  카탈로그에 없는 음식이 하나라도 있으면 false. 비음식 사진은 items=[]와 두 완비 표시로 평가 가능.

전체 예시는 `labels.management.example.json`이다. 사진은 포함되지 않은 형식 예시다.
그룹과 중요도 등 평가용 필드는 OpenAI 입력이나 운영 API 출력에 전달하지 않는다.

## 추가 지표

| 지표 | 정의 |
| --- | --- |
| management_group_recall | 확정 그룹 일대일 일치 수 / 그룹 라벨이 있는 음식 수. 실패·누락은 분모에 포함 |
| management_group_precision | 그룹 정답 완비 사진에서 그룹 일치 수 / 반환한 모든 음식 수. 추가·미해결 음식도 분모에 포함 |
| management_parent_only_rate | 확정 그룹 일치 후 남은 정답 중 상위 이름만 일대일 식별한 수 / 그룹 라벨 음식 수. 완전 정답에 합산하지 않음 |
| management_confusion_rate | 남은 정답/예측에서 사전 지정 혼동 쌍에 일대일 해당하는 수 / 그룹 라벨 음식 수 |
| management_unresolved_prediction_rate | 그룹 라벨이 있는 사진에서 그룹에 등록되지 않은 예측 이름 수 / 해당 사진의 예측 음식 수 |
| priority_food_recall, priority_food_miss_rate | 중요 음식의 식별률/미식별률. 그룹 라벨 있으면 확정 그룹 일치, 없으면 이름·사전 동의어 일치 |
| uncertainty_low_confidence_recall | requires_uncertainty 정답에 맞는 이름/상위 이름과 low가 일대일 반환된 수 / 해당 정답 수 |
| low_name_confidence_rate | 전체 반환 음식 중 음식명 confidence=low 비율 |

확정 그룹의 일치부터 배정하고 올바른 다른 음식도 먼저 확보해서 혼동으로 잘못 세지 않는다.
혼동 쌍은 일반 구이/달콤한 양념/튀김옷과 일반/제로 콜라다. 이 비율은 모든 오분류율이 아니며,
미해결·부분 분류·API 실패는 다른 지표와 함께 봐야 한다. 혼동이 0%라고 성공 100%인 것은 아니다.

미해결에는 카탈로그 미등록 음식이 포함되므로 모델의 판단 불가율 그 자체가 아니다.
confidence는 현재 스키마에서 음식명 확신만 뜻한다. low 지표는 음식명의 대리 지표이며
영양정보에 대한 별도의 불확실성 표시를 검증하지 못한다. API 필드 확장은 팀 합의 후 별도 진행한다.
분모가 없으면 rate=null이고 100%로 간주하지 않는다. 그룹 precision이 미측정이면 recall만으로
전체 그룹 정확도를 주장하지 않는다. 음식명 precision/recall과 원래 완료 기준은 유지한다.

## 프롬프트 비교 절차

1. 개발용 사진에 사람이 정답과 새 라벨을 작성하고 정책 버전·멤버·출처를 확정한다.
2. 같은 사진/정답/모델/detail/해상도/스키마로 examples-v3와 glucose-v6를 비교한다.
3. 음식명 정확도, 그룹 recall/precision, 중요 음식 누락, 부분 분류, 혼동, 미해결,
   low 지표와 지연/사용량을 함께 보고 검토한다. 한 점수만 좋아졌다고 채택하지 않는다.
4. 정책·프롬프트를 변경하면 새 버전과 변경 이유를 기록하고 튜닝에 쓰지 않은 검증 세트를 사용한다.
5. 새 지표의 목표치는 아직 정하지 않았다. 임의의 PASS 기준이나 기존 이름 85% 대체 판정을 만들지 않는다.

```powershell
python ml-service/eval_food.py --labels eval/food/labels.json --check
python ml-service/eval_food.py --labels eval/food/labels.json --prompt-version examples-v3 --limit 100
python ml-service/eval_food.py --labels eval/food/labels.json --prompt-version glucose-v6 --limit 100
```

`--check`는 API를 호출하지 않는다. 나머지는 실제 API 사용료가 발생한다.
현재 저장소의 기존 10장 라벨은 샘플 폴더를 기준으로 한 상대 경로다.
이를 검사할 때는 실제 이미지 폴더를 다음처럼 명시해야 한다.

```powershell
python ml-service/eval_food.py --labels eval/food/labels.json --images "2018-01-011.한국음식이미지_sample" --prompt-version glucose-v6 --check
```

커스텀 정책은 `--management-policy 경로.json`으로 선택한다. 보고서에는 정답·사진·프롬프트·스키마
해시 외에 정책 전체 스냅샷, 버전, 정규화된 정책 SHA-256, 사진별 그룹·부분 분류·혼동 결과도 남는다.
과거 보고서는 새 기준으로 덮어쓰지 않는다. 원래 라벨에 새 필드가 없으면 새 지표는 미측정이다.

## 이번 구현에서 검증한 것과 남은 것

구현 검증: 별도 그룹 채점, 일대일 배정, 조리법/음료 혼동, 부분 분류, 실패·추가 음식 처리,
라벨/정책 오류 차단, 정책 출처 보존, API 형식·운영 프롬프트 유지.
자동 테스트의 정답·예측은 합성된 코드 예제다. 실제 사진 인식 정확도의 결과가 아니다.

남은 것: 사람이 새 라벨 작성, 대표 사진 확보, 실제 v3/v6 비교, 검증 세트 평가와 팀 검토.
영양 DB 전체 연동, 실제 섭취량 파악, 실제 혈당과의 연결 및 개인별 반응 검증은 아직 구현하지 않았다.
검증 기록은 `evidence/glucose-policy-implementation-2026-10-08.json`에 보관한다.

## 출처

- 식약처 외식 영양성분 자료집 제2권: https://www.foodsafetykorea.go.kr/upload/mkisna/2013.pdf
- CDC, Carb Counting: https://www.cdc.gov/diabetes/healthy-eating/carb-counting-manage-blood-sugar.html
- ADA, Carb Counting and Diabetes: https://diabetes.org/food-nutrition/understanding-carbs/carb-counting-and-diabetes
- OpenAI, Evaluation best practices: https://developers.openai.com/api/docs/guides/evaluation-best-practices
- OpenAI, Prompt engineering: https://developers.openai.com/api/docs/guides/prompt-engineering
