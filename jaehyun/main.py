import base64
import os

import openai
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, UploadFile
from openai import AsyncOpenAI
from pydantic import BaseModel, ValidationError


class FoodItem(BaseModel):
    name: str
    estimated_portion: str
    estimated_carbohydrates_g: float
    estimated_protein_g: float
    estimated_fat_g: float
    estimated_sugar_g: float
    confidence: float


class FoodAnalysis(BaseModel):
    is_food_image: bool
    foods: list[FoodItem]
    total_estimated_carbohydrates_g: float
    total_estimated_protein_g: float
    total_estimated_fat_g: float
    total_estimated_sugar_g: float
    summary: str
    cautions: list[str]


class AnalyzeFoodResponse(BaseModel):
    filename: str | None
    analysis: FoodAnalysis


load_dotenv()

api_key = os.getenv("OPENAI_API_KEY")
model = os.getenv("OPENAI_MODEL", "gpt-6-luna")

if not api_key:
    raise RuntimeError("OPENAI_API_KEY를 찾을 수 없습니다.")

client = AsyncOpenAI(api_key=api_key)

app = FastAPI(title="음식 분석")

ALLOWED_IMAGE_TYPES = {
    "image/jpeg",
    "image/png",
    "image/webp",
}

MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024

@app.get("/health")
def health_check():
    return {"status": "ok"}


@app.post("/analyze-food", response_model=AnalyzeFoodResponse)
async def analyze_food(file: UploadFile):
    if file.content_type not in ALLOWED_IMAGE_TYPES:
        raise HTTPException(
            status_code=415,
            detail="JPEG, PNG, WEBP 이미지만 업로드할 수 있습니다.",
        )

    image_bytes = await file.read()

    if len(image_bytes) > MAX_IMAGE_SIZE_BYTES:
        raise HTTPException(
            status_code=413,
            detail="이미지 크기는 10MB 이하여야 합니다.",
        )
    
    if not image_bytes:
        raise HTTPException(
            status_code=400,
            detail="비어 있는 이미지 파일입니다.",
        )

    base64_image = base64.b64encode(image_bytes).decode("utf-8")
    image_data_url = f"data:{file.content_type};base64,{base64_image}"

    try:
        response = await client.responses.parse(
            model=model,
            reasoning={"effort": "none"},
            input=[
                {
                    "role": "system",
                    "content": (
                        "당신은 음식 사진의 영양 정보를 분석하는 도우미입니다. "
                        "모든 설명은 한국어로 작성하세요. "
                        "사진에서 보이는 음식을 각각 구분하고 섭취량을 추정하세요. "
                        "각 음식의 탄수화물, 단백질, 지방, 당류를 "
                        "그램 단위로 추정하세요. "
                        "전체 음식의 탄수화물, 단백질, 지방, 당류 합계도 계산하세요. "
                        "당류는 탄수화물에 포함되는 값이므로 "
                        "탄수화물과 당류를 서로 더하지 마세요. "
                        "confidence는 분석 신뢰도를 0.0부터 1.0 사이로 표현하세요. "
                        "사진으로 알 수 없는 조리용 기름, 소스, 설탕 등의 양은 "
                        "cautions에 불확실한 점으로 작성하세요. "
                        "사진 분석 결과는 추정치라는 점을 명시하세요. "
                        "인슐린 투여량이나 의료적 진단은 제공하지 마세요. "
                        "음식 사진이 아니라면 is_food_image를 false로 설정하고, "
                        "foods는 빈 배열로 설정하며 모든 영양소 합계를 0으로 반환하세요."
                    ),
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "input_text",
                            "text": "이 음식 사진을 분석해주세요.",
                        },
                        {
                            "type": "input_image",
                            "image_url": image_data_url,
                            "detail": "low",
                        },
                    ],
                },
            ],
            text_format=FoodAnalysis,
            max_output_tokens=2000,
            store=False,
        )

    except openai.AuthenticationError as error:
        raise HTTPException(
            status_code=500,
            detail="서버의 OpenAI API 인증 설정에 문제가 있습니다.",
        ) from error

    except (openai.RateLimitError, openai.InternalServerError) as error:
        raise HTTPException(
            status_code=503,
            detail="AI 서버가 혼잡합니다. 잠시 후 다시 시도해주세요.",
        ) from error

    except (openai.APITimeoutError, openai.APIConnectionError) as error:
        raise HTTPException(
            status_code=503,
            detail="AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.",
        ) from error

    except openai.BadRequestError as error:
        raise HTTPException(
            status_code=502,
            detail="AI에 전달한 이미지 또는 요청 형식에 문제가 있습니다.",
        ) from error

    except ValidationError as error:
        raise HTTPException(
            status_code=502,
            detail="AI 분석 결과가 완전한 JSON 형식이 아닙니다.",
        ) from error

    except openai.APIError as error:
        raise HTTPException(
            status_code=502,
            detail="AI 분석 서비스에서 오류가 발생했습니다.",
        ) from error

    analysis = response.output_parsed

    if analysis is None:
        raise HTTPException(
            status_code=502,
            detail="AI 분석 결과를 구조화하지 못했습니다.",
        )

    analysis.total_estimated_carbohydrates_g = round(
        sum(
            food.estimated_carbohydrates_g
            for food in analysis.foods
        ),
        1,
    )

    analysis.total_estimated_protein_g = round(
        sum(
            food.estimated_protein_g
            for food in analysis.foods
        ),
        1,
    )

    analysis.total_estimated_fat_g = round(
        sum(
            food.estimated_fat_g
            for food in analysis.foods
        ),
        1,
    )

    analysis.total_estimated_sugar_g = round(
        sum(
            food.estimated_sugar_g
            for food in analysis.foods
        ),
        1,
    )

    return AnalyzeFoodResponse(
        filename=file.filename,
        analysis=analysis,
    )