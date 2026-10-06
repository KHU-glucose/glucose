from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


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


class FoodRecognitionPayload(BaseModel):
    is_food_photo: bool
    items: list[FoodItem]
    likely_consumed_all: bool | None


class FoodRecognitionMeta(BaseModel):
    model: str
    latency_ms: int
    input_tokens: int
    output_tokens: int


class FoodRecognitionResponse(FoodRecognitionPayload):
    request_id: str
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
                            "tags": ["HIGH_FAT", "FAST_SUGAR"],
                            "packaged_product": None,
                            "confidence": "high",
                        }
                    ],
                    "likely_consumed_all": True,
                    "meta": {
                        "model": "gpt-6-luna",
                        "latency_ms": 1840,
                        "input_tokens": 1120,
                        "output_tokens": 160,
                    },
                }
            ]
        }
    )


class GraphReading(BaseModel):
    time: str
    value: int | None
    flag: Literal["NORMAL", "ABOVE_RANGE", "BELOW_RANGE", "MISSING"]


class GraphGap(BaseModel):
    from_time: str = Field(alias="from")
    to: str

    model_config = ConfigDict(populate_by_name=True)


class GraphParseMeta(BaseModel):
    parser_version: str
    image_width: int
    image_height: int
    latency_ms: int


class GraphParseResponse(BaseModel):
    request_id: str
    date: str
    source: Literal["LIBRE_DAILY_GRAPH"] = "LIBRE_DAILY_GRAPH"
    unit: Literal["mg/dL"] = "mg/dL"
    interval_minutes: Literal[15] = 15
    readings: list[GraphReading]
    gaps: list[GraphGap]
    coverage_ratio: float
    meta: GraphParseMeta


class ErrorResponse(BaseModel):
    code: str
    message: str
    request_id: str
