import asyncio
import hmac
import json
import logging
import os
import time
import uuid
from functools import lru_cache
from io import BytesIO
from typing import Literal

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from PIL import Image, UnidentifiedImageError

from food_recognizer import FoodRecognizer, FoodRecognizerFailure, OpenAIFoodRecognizer
from graph_parser import GraphParseFailure, LibreDailyGraphParser
from models import ErrorResponse, FoodRecognitionResponse, GraphParseResponse


load_dotenv()

MAX_IMAGE_SIZE_BYTES = 8 * 1024 * 1024
MAX_IMAGE_EDGE = 768
ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png"}
PROCESSING_LIMIT = asyncio.Semaphore(int(os.getenv("ML_MAX_CONCURRENCY", "4")))

LOGGER = logging.getLogger("ml-service")
LOGGER.setLevel(getattr(logging, os.getenv("LOG_LEVEL", "INFO").upper(), logging.INFO))
LOGGER.propagate = False
if not LOGGER.handlers:
    log_handler = logging.StreamHandler()
    log_handler.setFormatter(logging.Formatter("%(message)s"))
    LOGGER.addHandler(log_handler)


class ServiceError(Exception):
    def __init__(self, status_code: int, code: str, message: str, request_id: str):
        self.status_code = status_code
        self.code = code
        self.message = message
        self.request_id = request_id


@lru_cache
def get_food_recognizer() -> FoodRecognizer:
    return OpenAIFoodRecognizer()


@lru_cache
def get_graph_parser() -> LibreDailyGraphParser:
    return LibreDailyGraphParser()


app = FastAPI(title="Glucose Log ML Service", version="1.0.0")


@app.exception_handler(ServiceError)
async def service_error_handler(request: Request, error: ServiceError):
    return JSONResponse(
        status_code=error.status_code,
        content=ErrorResponse(
            code=error.code,
            message=error.message,
            request_id=error.request_id,
        ).model_dump(),
    )


@app.exception_handler(RequestValidationError)
async def request_validation_error_handler(request: Request, error: RequestValidationError):
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    missing_image = any(item.get("loc", ())[-1:] == ("image",) for item in error.errors())
    if request.url.path in {"/v1/food/recognize", "/v1/graph/parse"} and missing_image:
        return JSONResponse(
            status_code=400,
            content=ErrorResponse(
                code="INVALID_IMAGE",
                message="image 파일이 필요합니다",
                request_id=request_id,
            ).model_dump(),
        )
    return JSONResponse(
        status_code=422,
        content=ErrorResponse(
            code="INVALID_REQUEST",
            message="요청 형식이 올바르지 않습니다",
            request_id=request_id,
        ).model_dump(),
    )


@app.middleware("http")
async def log_request(request: Request, call_next):
    started_at = time.perf_counter()
    request_id = request.headers.get("X-Request-Id") or str(uuid.uuid4())
    request.state.request_id = request_id
    request.state.ml_meta = None

    try:
        response = await call_next(request)
    except Exception:
        LOGGER.exception(
            json.dumps(
                {
                    "request_id": request_id,
                    "path": request.url.path,
                    "status_code": 500,
                    "latency_ms": round((time.perf_counter() - started_at) * 1000),
                    "model": None,
                    "input_tokens": None,
                    "output_tokens": None,
                },
                ensure_ascii=False,
            )
        )
        raise

    meta = request.state.ml_meta
    LOGGER.info(
        json.dumps(
            {
                "request_id": request_id,
                "path": request.url.path,
                "status_code": response.status_code,
                "latency_ms": round((time.perf_counter() - started_at) * 1000),
                "model": getattr(meta, "model", None),
                "input_tokens": getattr(meta, "input_tokens", None),
                "output_tokens": getattr(meta, "output_tokens", None),
            },
            ensure_ascii=False,
        )
    )
    response.headers["X-Request-Id"] = request_id
    return response


def verify_internal_token(
    request: Request,
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
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
            request_id=request.state.request_id,
        )


async def validate_and_prepare_image(
    image: UploadFile,
    request_id: str,
) -> tuple[bytes, str]:
    if image.content_type not in ALLOWED_IMAGE_TYPES:
        raise ServiceError(400, "INVALID_IMAGE", "JPEG 또는 PNG 이미지만 지원합니다", request_id)

    image_bytes = await image.read(MAX_IMAGE_SIZE_BYTES + 1)
    if not image_bytes:
        raise ServiceError(400, "INVALID_IMAGE", "이미지 파일이 비어 있습니다", request_id)
    if len(image_bytes) > MAX_IMAGE_SIZE_BYTES:
        raise ServiceError(400, "INVALID_IMAGE", "이미지 크기는 8MB 이하여야 합니다", request_id)

    try:
        with Image.open(BytesIO(image_bytes)) as opened_image:
            if opened_image.format not in {"JPEG", "PNG"}:
                raise UnidentifiedImageError
            opened_image.load()
            prepared_image = opened_image.copy()
            image_format = opened_image.format
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError) as error:
        raise ServiceError(
            400,
            "INVALID_IMAGE",
            "유효한 JPEG 또는 PNG 이미지가 아닙니다",
            request_id,
        ) from error

    prepared_image.thumbnail((MAX_IMAGE_EDGE, MAX_IMAGE_EDGE))
    if image_format == "JPEG" and prepared_image.mode not in {"RGB", "L"}:
        prepared_image = prepared_image.convert("RGB")
    output = BytesIO()
    prepared_image.save(output, format=image_format)
    mime_type = "image/jpeg" if image_format == "JPEG" else "image/png"
    return output.getvalue(), mime_type


@app.get("/health")
def health():
    return {"status": "ok", "version": "1.0.0"}


@app.post(
    "/v1/food/recognize",
    response_model=FoodRecognitionResponse,
    dependencies=[Depends(verify_internal_token)],
    responses={
        400: {"model": ErrorResponse, "description": "INVALID_IMAGE"},
        401: {"model": ErrorResponse, "description": "UNAUTHORIZED"},
        502: {"model": ErrorResponse, "description": "UPSTREAM_AI_ERROR"},
        504: {"model": ErrorResponse, "description": "TIMEOUT"},
    },
)
async def recognize_food(
    request: Request,
    image: UploadFile = File(...),
    context: Literal["MEAL", "SNACK", "HYPO_TREATMENT", "ALCOHOL"] | None = Form(
        default=None
    ),
    recognizer: FoodRecognizer = Depends(get_food_recognizer),
):
    request_id = request.state.request_id
    image_bytes, image_mime_type = await validate_and_prepare_image(image, request_id)
    try:
        async with PROCESSING_LIMIT:
            result = await recognizer.recognize(image_bytes, image_mime_type, context)
    except FoodRecognizerFailure as error:
        status_code = 504 if error.code == "TIMEOUT" else 502
        raise ServiceError(status_code, error.code, error.message, request_id) from error

    request.state.ml_meta = result.meta
    return FoodRecognitionResponse(
        request_id=request_id,
        **result.payload.model_dump(),
        meta=result.meta,
    )


@app.post(
    "/v1/graph/parse",
    response_model=GraphParseResponse,
    dependencies=[Depends(verify_internal_token)],
    responses={
        400: {"model": ErrorResponse, "description": "INVALID_IMAGE"},
        401: {"model": ErrorResponse, "description": "UNAUTHORIZED"},
        422: {
            "model": ErrorResponse,
            "description": "GRAPH_NOT_RECOGNIZED or TOO_LITTLE_DATA",
        },
    },
)
async def parse_graph(
    request: Request,
    image: UploadFile = File(...),
    parser: LibreDailyGraphParser = Depends(get_graph_parser),
):
    request_id = request.state.request_id
    image_bytes, _ = await validate_and_prepare_image(image, request_id)
    try:
        async with PROCESSING_LIMIT:
            result = await asyncio.to_thread(parser.parse, image_bytes)
    except GraphParseFailure as error:
        status_code = 400 if error.code == "INVALID_IMAGE" else 422
        raise ServiceError(status_code, error.code, error.message, request_id) from error

    return GraphParseResponse(request_id=request_id, **result)
