# CGM 음식 이미지 분석 API

CGM을 사용하는 인슐린 사용자가 음식 사진을 기록하면, 사진에서 음식을 인식하고 영양 정보를 추정하는 FastAPI 서버입니다.

> 이 분석 결과는 사진을 기반으로 한 추정치이며 의료적 진단이나 인슐린 투여량 결정에 사용할 수 없습니다.

## 현재 구현된 기능

- 음식 이미지 업로드
- 여러 음식 구분
- 음식별 예상 섭취량 분석
- 음식별 영양소 추정
  - 탄수화물
  - 단백질
  - 지방
  - 당류
- 전체 영양소 합계 계산
- 음식이 아닌 이미지 판별
- 이미지 형식 및 크기 검증
- OpenAI API 예외 처리
- 구조화된 JSON 응답

## 프로젝트 구조

```text
ai_challenge
├── .env.example
├── .gitignore
├── README.md
├── requirements.txt
└── jaehyun
    ├── main.py
    └── test_openai.py
```

## 지원 이미지

- JPEG
- PNG
- WEBP
- 최대 파일 크기: 10MB

iPhone의 HEIC 사진은 현재 지원하지 않습니다. iOS 앱에서 JPEG로 변환한 후 업로드해야 합니다.

## 설치 방법

### 1. 가상환경 생성

프로젝트 최상위 폴더에서 실행합니다.

```powershell
python -m venv .venv
```

### 2. 가상환경 활성화

```powershell
.\.venv\Scripts\Activate.ps1
```

PowerShell 실행 정책 오류가 발생하면 현재 PowerShell 창에만 임시로 허용합니다.

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

그다음 다시 활성화합니다.

```powershell
.\.venv\Scripts\Activate.ps1
```

### 3. 패키지 설치

```powershell
python -m pip install -r .\jaehyun\requirements.txt
```

### 4. 환경변수 파일 생성

```powershell
Copy-Item .env.example .env
```

생성된 `.env`에 자신의 OpenAI API 키를 입력합니다.

```env
OPENAI_API_KEY=자신의_API_KEY
OPENAI_MODEL=gpt-6-luna
```

`.env`에는 실제 API 키가 들어 있으므로 GitHub에 올리거나 다른 사람과 공유하면 안 됩니다.

## 서버 실행

```powershell
fastapi dev jaehyun/main.py
```

서버가 실행되면 다음 주소를 사용합니다.

- API 문서: http://127.0.0.1:8000/docs
- 상태 확인: http://127.0.0.1:8000/health

## API

### 상태 확인

```http
GET /health
```

응답:

```json
{
  "status": "ok"
}
```

### 음식 이미지 분석

```http
POST /analyze-food
```

요청 형식:

```text
multipart/form-data
file: 음식 이미지
```

응답 예시:

```json
{
  "filename": "meal.jpg",
  "analysis": {
    "is_food_image": true,
    "foods": [
      {
        "name": "흰쌀밥",
        "estimated_portion": "약 1공기",
        "estimated_carbohydrates_g": 65,
        "estimated_protein_g": 6,
        "estimated_fat_g": 1,
        "estimated_sugar_g": 0,
        "confidence": 0.9
      }
    ],
    "total_estimated_carbohydrates_g": 65,
    "total_estimated_protein_g": 6,
    "total_estimated_fat_g": 1,
    "total_estimated_sugar_g": 0,
    "summary": "흰쌀밥이 포함된 식사로 보입니다.",
    "cautions": [
      "사진을 기반으로 추정한 값이므로 실제 영양 정보와 다를 수 있습니다."
    ]
  }
}
```

## 주요 상태 코드

- `200`: 분석 성공
- `413`: 이미지가 10MB를 초과함
- `415`: 지원하지 않는 이미지 형식
- `500`: 서버 API 인증 설정 오류
- `502`: AI 요청 또는 JSON 응답 오류
- `503`: AI 서버 혼잡, 속도 제한 또는 연결 오류

## 보안 주의사항

- 실제 API 키를 코드에 작성하지 않습니다.
- `.env`를 GitHub에 올리지 않습니다.
- `.env.example`에는 실제 키를 넣지 않습니다.
- iOS 앱에 OpenAI API 키를 넣지 않습니다.
- iOS 앱은 FastAPI 서버를 통해서만 OpenAI 기능을 사용합니다.

## 현재 한계

- 모든 영양 정보는 사진 기반 추정치입니다.
- 조리용 기름, 소스, 설탕의 정확한 양을 알 수 없습니다.
- 가려진 음식이나 사진 밖의 음식은 분석할 수 없습니다.
- 분석 결과를 인슐린 용량 결정에 사용하면 안 됩니다.