import asyncio
import base64
import os
import time
from dataclasses import dataclass
from typing import Protocol

import openai
from openai import AsyncOpenAI
from pydantic import ValidationError

from models import FoodRecognitionMeta, FoodRecognitionPayload


FOOD_PROMPT = """
당신은 음식 사진 인식기입니다. 반드시 제공된 구조화 출력 스키마로만 답하세요.

규칙:
- 사진에 실제 음식이나 음료가 없으면 is_food_photo=false, items=[], likely_consumed_all=null로 답합니다.
- 각 음식은 한국어 일반 명칭으로 분리합니다. 브랜드가 보여도 name에는 일반 명칭을 씁니다.
- count는 낱개 수를 신뢰성 있게 셀 수 있을 때만 정수로 씁니다. 그릇 음식은 한 그릇이면 1이고, 셀 수 없으면 null입니다.
- unit은 개, 조각, 팩, 컵, 그릇, 공기, 병, 잔 중 하나만 사용합니다.
- category_hint는 MEAL, SNACK, FAST_SUGAR, DRINK, ALCOHOL 중 하나입니다.
- tags는 HIGH_FAT, HIGH_CARB, FAST_SUGAR 중 사진에서 근거가 있는 값만 사용합니다.
- 포장 제품이고 브랜드, 제품명, 용량을 읽을 수 있을 때 packaged_product에 기록합니다.
- confidence는 high, medium, low 중 하나입니다.
- 먹기 전 사진인지 확실하면 likely_consumed_all=true, 남은 음식이면 false, 판단할 수 없으면 null입니다.
- 칼로리, 탄수화물 g, 당류 g, 인슐린 용량, 치료 적합성은 추정하거나 반환하지 않습니다.
""".strip()


class FoodRecognizerFailure(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class FoodRecognitionResult:
    payload: FoodRecognitionPayload
    meta: FoodRecognitionMeta


class FoodRecognizer(Protocol):
    async def recognize(
        self,
        image_bytes: bytes,
        image_mime_type: str,
        context: str | None,
    ) -> FoodRecognitionResult: ...


class OpenAIFoodRecognizer:
    def __init__(
        self,
        api_key: str | None = None,
        model: str | None = None,
        timeout_seconds: float = 10.0,
        client: AsyncOpenAI | None = None,
    ):
        self.api_key = api_key or os.getenv("OPENAI_API_KEY")
        self.model = (
            model
            or os.getenv("FOOD_MODEL")
            or os.getenv("OPENAI_MODEL")
            or "luna"
        )
        self.timeout_seconds = timeout_seconds
        self._client = client

    def _get_client(self) -> AsyncOpenAI:
        if self._client is not None:
            return self._client
        if not self.api_key:
            raise FoodRecognizerFailure(
                "UPSTREAM_AI_ERROR",
                "OPENAI_API_KEY가 설정되지 않았습니다",
            )
        self._client = AsyncOpenAI(api_key=self.api_key)
        return self._client

    async def recognize(
        self,
        image_bytes: bytes,
        image_mime_type: str,
        context: str | None,
    ) -> FoodRecognitionResult:
        client = self._get_client()
        encoded_image = base64.b64encode(image_bytes).decode("ascii")
        image_url = f"data:{image_mime_type};base64,{encoded_image}"
        context_text = context or "선택하지 않음"
        started_at = time.perf_counter()

        for attempt in range(2):
            try:
                async with asyncio.timeout(self.timeout_seconds):
                    response = await client.responses.parse(
                        model=self.model,
                        input=[
                            {"role": "system", "content": FOOD_PROMPT},
                            {
                                "role": "user",
                                "content": [
                                    {
                                        "type": "input_text",
                                        "text": (
                                            "이 음식 사진을 분석하세요. "
                                            f"사용자가 선택한 촬영 상황은 {context_text}입니다. "
                                            "상황은 인식 힌트로만 사용하세요."
                                        ),
                                    },
                                    {
                                        "type": "input_image",
                                        "image_url": image_url,
                                        "detail": "low",
                                    },
                                ],
                            },
                        ],
                        text_format=FoodRecognitionPayload,
                        max_output_tokens=1200,
                        store=False,
                    )

                payload = response.output_parsed
                if payload is None:
                    raise ValueError("structured output is empty")

                usage = getattr(response, "usage", None)
                latency_ms = round((time.perf_counter() - started_at) * 1000)
                return FoodRecognitionResult(
                    payload=payload,
                    meta=FoodRecognitionMeta(
                        model=self.model,
                        latency_ms=latency_ms,
                        input_tokens=int(getattr(usage, "input_tokens", 0) or 0),
                        output_tokens=int(getattr(usage, "output_tokens", 0) or 0),
                    ),
                )
            except (asyncio.TimeoutError, openai.APITimeoutError) as error:
                if attempt == 0:
                    continue
                raise FoodRecognizerFailure(
                    "TIMEOUT",
                    "음식 인식 처리 시간이 초과되었습니다",
                ) from error
            except openai.AuthenticationError as error:
                raise FoodRecognizerFailure(
                    "UPSTREAM_AI_ERROR",
                    "AI 서비스 인증 설정이 올바르지 않습니다",
                ) from error
            except openai.BadRequestError as error:
                raise FoodRecognizerFailure(
                    "UPSTREAM_AI_ERROR",
                    "AI 서비스가 이미지 요청을 처리하지 못했습니다",
                ) from error
            except (
                openai.RateLimitError,
                openai.APIConnectionError,
                openai.InternalServerError,
                ValidationError,
                ValueError,
            ) as error:
                if attempt == 0:
                    continue
                raise FoodRecognizerFailure(
                    "UPSTREAM_AI_ERROR",
                    "AI 서비스 응답을 검증하지 못했습니다",
                ) from error
            except openai.APIError as error:
                if attempt == 0:
                    continue
                raise FoodRecognizerFailure(
                    "UPSTREAM_AI_ERROR",
                    "AI 서비스에서 오류가 발생했습니다",
                ) from error

        raise FoodRecognizerFailure(
            "UPSTREAM_AI_ERROR",
            "AI 서비스 응답을 받지 못했습니다",
        )
