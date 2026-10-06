import os
import re
import time
from collections.abc import Callable
from datetime import date

import cv2
import numpy as np
import pytesseract

from models import GraphGap, GraphParseMeta, GraphReading


PARSER_VERSION = "1.0.0"
READING_COUNT = 96


class GraphParseFailure(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class LibreDailyGraphParser:
    def __init__(
        self,
        date_reader: Callable[[np.ndarray], str] | None = None,
        minimum_glucose: int = 50,
        maximum_glucose: int = 350,
    ):
        self.date_reader = date_reader or self._read_date_text
        self.minimum_glucose = minimum_glucose
        self.maximum_glucose = maximum_glucose

    def parse(self, image_bytes: bytes) -> dict:
        started_at = time.perf_counter()
        encoded = np.frombuffer(image_bytes, dtype=np.uint8)
        image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
        if image is None:
            raise GraphParseFailure("INVALID_IMAGE", "이미지를 디코딩하지 못했습니다")

        height, width = image.shape[:2]
        parsed_date = self._parse_date(self.date_reader(image))
        x_left, y_top, x_right, y_bottom = self._find_graph_bounds(image)
        readings = self._extract_readings(
            image,
            x_left=x_left,
            y_top=y_top,
            x_right=x_right,
            y_bottom=y_bottom,
        )
        present_count = sum(reading.value is not None for reading in readings)
        coverage_ratio = round(present_count / READING_COUNT, 4)
        if coverage_ratio < 0.1:
            raise GraphParseFailure(
                "TOO_LITTLE_DATA",
                "그래프에서 충분한 혈당 데이터를 찾지 못했습니다",
            )

        return {
            "date": parsed_date.isoformat(),
            "readings": readings,
            "gaps": self._summarize_gaps(readings),
            "coverage_ratio": coverage_ratio,
            "meta": GraphParseMeta(
                parser_version=PARSER_VERSION,
                image_width=width,
                image_height=height,
                latency_ms=round((time.perf_counter() - started_at) * 1000),
            ),
        }

    @staticmethod
    def _read_date_text(image: np.ndarray) -> str:
        configured_command = os.getenv("TESSERACT_CMD")
        if configured_command:
            pytesseract.pytesseract.tesseract_cmd = configured_command

        header = image[: max(1, int(image.shape[0] * 0.35)), :]
        gray = cv2.cvtColor(header, cv2.COLOR_BGR2GRAY)
        gray = cv2.resize(gray, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC)
        binary = cv2.threshold(
            gray,
            0,
            255,
            cv2.THRESH_BINARY + cv2.THRESH_OTSU,
        )[1]
        try:
            return pytesseract.image_to_string(binary, lang="kor+eng", config="--psm 6")
        except (pytesseract.TesseractError, pytesseract.TesseractNotFoundError) as error:
            raise GraphParseFailure(
                "GRAPH_NOT_RECOGNIZED",
                "그래프 날짜 OCR을 실행하지 못했습니다",
            ) from error

    @staticmethod
    def _parse_date(text: str) -> date:
        patterns = (
            r"(20\d{2})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일",
            r"(20\d{2})\s*[-./]\s*(\d{1,2})\s*[-./]\s*(\d{1,2})",
        )
        for pattern in patterns:
            match = re.search(pattern, text)
            if not match:
                continue
            try:
                return date(*(int(value) for value in match.groups()))
            except ValueError:
                break
        raise GraphParseFailure(
            "GRAPH_NOT_RECOGNIZED",
            "그래프에서 날짜를 찾지 못했습니다",
        )

    @staticmethod
    def _find_graph_bounds(image: np.ndarray) -> tuple[int, int, int, int]:
        height, width = image.shape[:2]
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        edges = cv2.Canny(gray, 50, 150)
        lines = cv2.HoughLinesP(
            edges,
            1,
            np.pi / 180,
            threshold=max(40, width // 12),
            minLineLength=max(80, int(width * 0.35)),
            maxLineGap=max(10, int(width * 0.03)),
        )
        if lines is None:
            raise GraphParseFailure(
                "GRAPH_NOT_RECOGNIZED",
                "그래프 격자선을 찾지 못했습니다",
            )

        horizontal: list[tuple[int, int, int]] = []
        y_tolerance = max(3, int(height * 0.006))
        for raw_line in lines[:, 0]:
            x1, y1, x2, y2 = (int(value) for value in raw_line)
            if abs(y2 - y1) <= y_tolerance and y1 >= int(height * 0.15):
                left, right = sorted((x1, x2))
                if right - left >= width * 0.35:
                    horizontal.append(((y1 + y2) // 2, left, right))

        if len(horizontal) < 3:
            raise GraphParseFailure(
                "GRAPH_NOT_RECOGNIZED",
                "그래프 가로 격자선을 충분히 찾지 못했습니다",
            )

        horizontal.sort()
        grouped: list[list[tuple[int, int, int]]] = []
        for line in horizontal:
            if not grouped or abs(line[0] - np.median([item[0] for item in grouped[-1]])) > y_tolerance:
                grouped.append([line])
            else:
                grouped[-1].append(line)

        grid_lines: list[tuple[int, int, int]] = []
        for group in grouped:
            y = round(float(np.median([item[0] for item in group])))
            left = round(float(np.median([item[1] for item in group])))
            right = round(float(np.median([item[2] for item in group])))
            if right - left >= width * 0.35:
                grid_lines.append((y, left, right))

        if len(grid_lines) < 3:
            raise GraphParseFailure(
                "GRAPH_NOT_RECOGNIZED",
                "그래프 격자 영역을 결정하지 못했습니다",
            )

        common_left = round(float(np.median([line[1] for line in grid_lines])))
        common_right = round(float(np.median([line[2] for line in grid_lines])))
        aligned = [
            line
            for line in grid_lines
            if abs(line[1] - common_left) <= width * 0.12
            and abs(line[2] - common_right) <= width * 0.12
        ]
        if len(aligned) < 3:
            aligned = grid_lines

        y_top = min(line[0] for line in aligned)
        y_bottom = max(line[0] for line in aligned)
        x_left = round(float(np.median([line[1] for line in aligned])))
        x_right = round(float(np.median([line[2] for line in aligned])))

        if x_right - x_left < width * 0.35 or y_bottom - y_top < height * 0.15:
            raise GraphParseFailure(
                "GRAPH_NOT_RECOGNIZED",
                "그래프 영역의 크기가 올바르지 않습니다",
            )
        return x_left, y_top, x_right, y_bottom

    def _extract_readings(
        self,
        image: np.ndarray,
        *,
        x_left: int,
        y_top: int,
        x_right: int,
        y_bottom: int,
    ) -> list[GraphReading]:
        plot = image[y_top : y_bottom + 1, x_left : x_right + 1]
        gray = cv2.cvtColor(plot, cv2.COLOR_BGR2GRAY)
        if float(np.median(gray)) < 128:
            curve_candidates = cv2.threshold(gray, 165, 255, cv2.THRESH_BINARY)[1]
        else:
            curve_candidates = cv2.threshold(gray, 105, 255, cv2.THRESH_BINARY_INV)[1]

        plot_height, plot_width = curve_candidates.shape
        horizontal_kernel = cv2.getStructuringElement(
            cv2.MORPH_RECT,
            (max(15, plot_width // 8), 1),
        )
        vertical_kernel = cv2.getStructuringElement(
            cv2.MORPH_RECT,
            (1, max(15, plot_height // 5)),
        )
        grid_mask = cv2.bitwise_or(
            cv2.morphologyEx(curve_candidates, cv2.MORPH_OPEN, horizontal_kernel),
            cv2.morphologyEx(curve_candidates, cv2.MORPH_OPEN, vertical_kernel),
        )
        curve_mask = cv2.bitwise_and(curve_candidates, cv2.bitwise_not(grid_mask))
        curve_mask = cv2.morphologyEx(
            curve_mask,
            cv2.MORPH_CLOSE,
            cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3)),
        )

        edge_margin = max(2, int(min(plot_height, plot_width) * 0.008))
        curve_mask[:edge_margin, :] = 0
        curve_mask[-edge_margin:, :] = 0
        curve_mask[:, :edge_margin] = 0
        curve_mask[:, -edge_margin:] = 0

        x_radius = max(2, round(plot_width / (READING_COUNT * 2)))
        readings: list[GraphReading] = []
        previous_y: float | None = None
        for index in range(READING_COUNT):
            x = round(index * (plot_width - 1) / (READING_COUNT - 1))
            start = max(0, x - x_radius)
            end = min(plot_width, x + x_radius + 1)
            candidate_y, _ = np.where(curve_mask[:, start:end] > 0)

            value: int | None = None
            flag = "MISSING"
            if candidate_y.size:
                if previous_y is None:
                    y = float(np.median(candidate_y))
                else:
                    unique_y = np.unique(candidate_y)
                    y = float(unique_y[np.argmin(np.abs(unique_y - previous_y))])
                previous_y = y
                ratio = y / max(1, plot_height - 1)
                raw_value = self.maximum_glucose - ratio * (
                    self.maximum_glucose - self.minimum_glucose
                )
                value = int(round(np.clip(raw_value, self.minimum_glucose, self.maximum_glucose)))
                if y <= edge_margin * 2:
                    flag = "ABOVE_RANGE"
                elif y >= plot_height - edge_margin * 2 - 1:
                    flag = "BELOW_RANGE"
                else:
                    flag = "NORMAL"
            else:
                previous_y = None

            readings.append(
                GraphReading(
                    time=self._time_for_index(index),
                    value=value,
                    flag=flag,
                )
            )
        return readings

    @classmethod
    def _summarize_gaps(cls, readings: list[GraphReading]) -> list[GraphGap]:
        gaps: list[GraphGap] = []
        gap_start: int | None = None
        for index, reading in enumerate(readings):
            if reading.value is None and gap_start is None:
                gap_start = index
            if reading.value is not None and gap_start is not None:
                gaps.append(
                    GraphGap(
                        from_time=cls._time_for_index(gap_start),
                        to=cls._time_for_index(index),
                    )
                )
                gap_start = None
        if gap_start is not None:
            gaps.append(GraphGap(from_time=cls._time_for_index(gap_start), to="24:00"))
        return gaps

    @staticmethod
    def _time_for_index(index: int) -> str:
        total_minutes = index * 15
        return f"{total_minutes // 60:02d}:{total_minutes % 60:02d}"
