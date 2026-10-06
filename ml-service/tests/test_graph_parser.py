import cv2
import numpy as np
import pytest
import pytesseract

from graph_parser import GraphParseFailure, LibreDailyGraphParser


def build_graph_image(dark_mode: bool = False) -> bytes:
    background = 24 if dark_mode else 255
    grid = 85 if dark_mode else 185
    secondary_grid = 70 if dark_mode else 205
    curve = 240 if dark_mode else 20
    image = np.full((800, 1200, 3), background, dtype=np.uint8)
    left, top, right, bottom = 120, 220, 1080, 700

    for y in np.linspace(top, bottom, 7, dtype=int):
        cv2.line(image, (left, int(y)), (right, int(y)), (grid, grid, grid), 2)
    for x in np.linspace(left, right, 9, dtype=int):
        cv2.line(
            image,
            (int(x), top),
            (int(x), bottom),
            (secondary_grid, secondary_grid, secondary_grid),
            1,
        )

    points = []
    for index in range(96):
        if 40 <= index <= 47:
            if len(points) > 1:
                cv2.polylines(image, [np.asarray(points)], False, (curve, curve, curve), 4)
            points = []
            continue
        x = round(left + index * (right - left) / 95)
        glucose = 150 + 45 * np.sin(index / 10)
        y = round(top + (350 - glucose) / 300 * (bottom - top))
        points.append((x, y))
    if len(points) > 1:
        cv2.polylines(image, [np.asarray(points)], False, (curve, curve, curve), 4)

    ok, encoded = cv2.imencode(".png", image)
    assert ok
    return encoded.tobytes()


def test_parse_date_accepts_korean_and_iso_formats():
    assert LibreDailyGraphParser._parse_date("2026년 10월 1일").isoformat() == "2026-10-01"
    assert LibreDailyGraphParser._parse_date("2026-10-01").isoformat() == "2026-10-01"


def test_parse_date_rejects_missing_date():
    with pytest.raises(GraphParseFailure, match="날짜"):
        LibreDailyGraphParser._parse_date("일일 그래프")


def test_missing_tesseract_is_reported_as_graph_error(monkeypatch):
    def raise_not_found(*args, **kwargs):
        raise pytesseract.TesseractNotFoundError()

    monkeypatch.setattr(pytesseract, "image_to_string", raise_not_found)
    image = np.full((100, 100, 3), 255, dtype=np.uint8)

    with pytest.raises(GraphParseFailure, match="OCR"):
        LibreDailyGraphParser._read_date_text(image)


def test_synthetic_graph_produces_96_readings_and_gap():
    parser = LibreDailyGraphParser(date_reader=lambda _: "2026년 10월 1일")

    result = parser.parse(build_graph_image())

    assert result["date"] == "2026-10-01"
    assert len(result["readings"]) == 96
    assert result["coverage_ratio"] >= 0.75
    assert any(reading.value is None for reading in result["readings"])
    assert result["gaps"]
    present_values = [
        reading.value for reading in result["readings"] if reading.value is not None
    ]
    assert min(present_values) >= 50
    assert max(present_values) <= 350


def test_dark_mode_graph_is_supported():
    parser = LibreDailyGraphParser(date_reader=lambda _: "2026-10-01")

    result = parser.parse(build_graph_image(dark_mode=True))

    assert len(result["readings"]) == 96
    assert result["coverage_ratio"] >= 0.75
    assert result["gaps"]
