import hmac
import json
import logging
import os
import time
import uuid

from io import BytesIO
from typing import Literal

from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict
from PIL import Image, UnidentifiedImageError

MAX_IMAGE_SIZE_BYTES = 8 * 1024 * 1024
MAX_IMAGE_EDGE = 768
ALLOWED_IMAGE_TYPES = {
    "image/jpeg",
    "image/png",
}

LOGGER = logging.getLogger("ml-service")
LOGGER.setLevel(
    getattr(
        logging,
        os.getenv("LOG_LEVEL", "INFO").upper(),
        logging.INFO,
    )
)
LOGGER.propagate = False

if not LOGGER.handlers:
    log_handler = logging.StreamHandler()
    log_handler.setFormatter(logging.Formatter("%(message)s"))
    LOGGER.addHandler(log_handler)

class PackagedProduct(BaseModel):
    brand: str | None
    product_name: str | None
    volume_ml: int | None


class FoodItem(BaseModel):
    name: str
    count: int | None
    unit: Literal["개", "조각", "팩", "컵", "그릇", "공기", "병", "잔"]
    category_hint: Literal[
        "MEAL",
        "SNACK",
        "FAST_SUGAR",
        "DRINK",
        "ALCOHOL",
    ]
    tags: list[Literal["HIGH_FAT", "HIGH_CARB", "FAST_SUGAR"]]
    packaged_product: PackagedProduct | None
    confidence: Literal["high", "medium", "low"]


class FoodRecognitionMeta(BaseModel):
    model: str
    latency_ms: int
    input_tokens: int
    output_tokens: int


class FoodRecognitionResponse(BaseModel):
    request_id: str
    is_food_photo: bool
    items: list[FoodItem]
    likely_consumed_all: bool | None
    meta: FoodRecognitionMeta

    model_config = ConfigDict(
        json_schema_extra={
            "examples": [
                {
                    "request_id": "test-request-001",
                    "is_food_photo": True,
                    "items": [
                        {
                            "name": "초콜릿",
                            "count": 3,
                            "unit": "조각",
                            "category_hint": "FAST_SUGAR",
                            "tags": ["HIGH_FAT"],
                            "packaged_product": None,
                            "confidence": "high",
                        }
                    ],
                    "likely_consumed_all": True,
                    "meta": {
                        "model": "mock",
                        "latency_ms": 0,
                        "input_tokens": 0,
                        "output_tokens": 0,
                    },
                }
            ]
        }
    )

class ErrorResponse(BaseModel):
    code: str
    message: str
    request_id: str

class ServiceError(Exception):
    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        request_id: str,
    ):
        self.status_code = status_code
        self.code = code
        self.message = message
        self.request_id = request_id

app = FastAPI()

@app.exception_handler(ServiceError)
async def service_error_handler(
    request: Request,
    error: ServiceError,
):
    return JSONResponse(
        status_code=error.status_code,
        content=ErrorResponse(
            code=error.code,
            message=error.message,
            request_id=error.request_id,
        ).model_dump(),
    )

@app.middleware("http")
async def log_request(
    request: Request,
    call_next,
):
    started_at = time.perf_counter()

    request_id = (
        request.headers.get("X-Request-Id")
        or str(uuid.uuid4())
    )

    request.state.request_id = request_id

    try:
        response = await call_next(request)
    except Exception:
        latency_ms = round(
            (time.perf_counter() - started_at) * 1000
        )

        LOGGER.error(
            json.dumps(
                {
                    "request_id": request_id,
                    "path": request.url.path,
                    "status_code": 500,
                    "latency_ms": latency_ms,
                    "model": None,
                    "input_tokens": None,
                    "output_tokens": None,
                },
                ensure_ascii=False,
            )
        )
        raise

    latency_ms = round(
        (time.perf_counter() - started_at) * 1000
    )

    is_food_request = (
        request.url.path == "/v1/food/recognize"
    )

    LOGGER.info(
        json.dumps(
            {
                "request_id": request_id,
                "path": request.url.path,
                "status_code": response.status_code,
                "latency_ms": latency_ms,
                "model": "mock" if is_food_request else None,
                "input_tokens": 0 if is_food_request else None,
                "output_tokens": 0 if is_food_request else None,
            },
            ensure_ascii=False,
        )
    )

    response.headers["X-Request-Id"] = request_id

    return response

def verify_internal_token(
    x_internal_token: str | None = Header(
        default=None,
        alias="X-Internal-Token",
    ),
    x_request_id: str = Header(..., alias="X-Request-Id"),
):
    expected_token = os.getenv("INTERNAL_TOKEN")

    if (
        expected_token is None
        or x_internal_token is None
        or not hmac.compare_digest(x_internal_token, expected_token)
    ):
        raise ServiceError(
            status_code=401,
            code="UNAUTHORIZED",
            message="내부 토큰이 올바르지 않습니다",
            request_id=x_request_id,
        )

async def validate_and_prepare_image(
    image: UploadFile,
    request_id: str,
) -> bytes:
    if image.content_type not in ALLOWED_IMAGE_TYPES:
        raise ServiceError(
            status_code=400,
            code="INVALID_IMAGE",
            message="JPEG 또는 PNG 이미지만 지원합니다",
            request_id=request_id,
        )

    image_bytes = await image.read(MAX_IMAGE_SIZE_BYTES + 1)

    if not image_bytes:
        raise ServiceError(
            status_code=400,
            code="INVALID_IMAGE",
            message="이미지 파일이 비어 있습니다",
            request_id=request_id,
        )

    if len(image_bytes) > MAX_IMAGE_SIZE_BYTES:
        raise ServiceError(
            status_code=400,
            code="INVALID_IMAGE",
            message="이미지 크기는 8MB 이하여야 합니다",
            request_id=request_id,
        )

    try:
        with Image.open(BytesIO(image_bytes)) as opened_image:
            if opened_image.format not in {"JPEG", "PNG"}:
                raise UnidentifiedImageError

            opened_image.load()
            prepared_image = opened_image.copy()
            image_format = opened_image.format

    except (
        Image.DecompressionBombError,
        UnidentifiedImageError,
        OSError,
    ) as error:
        raise ServiceError(
            status_code=400,
            code="INVALID_IMAGE",
            message="유효한 JPEG 또는 PNG 이미지가 아닙니다",
            request_id=request_id,
        ) from error

    prepared_image.thumbnail(
        (MAX_IMAGE_EDGE, MAX_IMAGE_EDGE),
    )

    output = BytesIO()
    prepared_image.save(output, format=image_format)

    return output.getvalue()

@app.get("/health")
def health():
    return {"status": "ok", "version" : "1.0.0."}


@app.post(
    "/v1/food/recognize",
    response_model=FoodRecognitionResponse,
    dependencies=[Depends(verify_internal_token)],
    responses={
        400: {
            "model": ErrorResponse,
            "description": "INVALID_IMAGE",
        },
        401: {
            "model": ErrorResponse,
            "description": "UNAUTHORIZED",
        },
        502: {
            "model": ErrorResponse,
            "description": "UPSTREAM_AI_ERROR",
        },
        504: {
            "model": ErrorResponse,
            "description": "TIMEOUT",
        },
    },
)
async def recognize_food(
    image: UploadFile = File(...),
    context: Literal[
        "MEAL",
        "SNACK",
        "HYPO_TREATMENT",
        "ALCOHOL",
    ]
    | None = Form(default=None),
    x_request_id: str = Header(..., alias="X-Request-Id"),
):
    await validate_and_prepare_image(
        image=image,
        request_id=x_request_id,
    )
    return FoodRecognitionResponse(
        request_id=x_request_id,
        is_food_photo=True,
        items=[
            FoodItem(
                name="초콜릿",
                count=3,
                unit="조각",
                category_hint="FAST_SUGAR",
                tags=["HIGH_FAT"],
                packaged_product=None,
                confidence="high",
            )
        ],
        likely_consumed_all=True,
        meta=FoodRecognitionMeta(
            model="mock",
            latency_ms=0,
            input_tokens=0,
            output_tokens=0,
        ),
    )
