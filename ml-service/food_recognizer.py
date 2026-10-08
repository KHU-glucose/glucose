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
from food_prompts import ACTIVE_PROMPT_VERSION, get_food_prompt


FOOD_PROMPT = get_food_prompt(ACTIVE_PROMPT_VERSION)
IMAGE_DETAIL = "low"
MAX_OUTPUT_TOKENS = 1200


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
        prompt_version: str = ACTIVE_PROMPT_VERSION,
    ):
        self.api_key = api_key or os.getenv("OPENAI_API_KEY")
        self.model = (
            model
            or os.getenv("FOOD_MODEL")
            or os.getenv("OPENAI_MODEL")
            or "luna"
        )
        self.timeout_seconds = timeout_seconds
        self.prompt_version = prompt_version
        self.prompt = get_food_prompt(prompt_version)
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
                            {"role": "system", "content": self.prompt},
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
                                        "detail": IMAGE_DETAIL,
                                    },
                                ],
                            },
                        ],
                        text_format=FoodRecognitionPayload,
                        max_output_tokens=MAX_OUTPUT_TOKENS,
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
