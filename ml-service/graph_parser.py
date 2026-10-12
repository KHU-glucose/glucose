import os
import re
import time
from collections.abc import Callable
from datetime import date

import cv2
import numpy as np
import pytesseract

from models import GraphGap, GraphParseMeta, GraphReading


PARSER_VERSION = "1.1.0"
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
        # Gray grid strokes are distinct from black/white text and the glucose curve.
        # Edge-based line detection can bridge the left labels into the grid.
        neutral = np.ptp(image.astype(np.int16), axis=2) < 48
        above = np.roll(gray, 3, axis=0).astype(np.int16)
        below = np.roll(gray, -3, axis=0).astype(np.int16)
        intensity = gray.astype(np.int16)
        if float(np.median(gray)) < 128:
            grid_pixels = (gray >= 45) & (gray <= 160) & neutral & (intensity > np.minimum(above, below) + 8)
        else:
            # Thin lines become very pale after resizing. Local contrast separates
            # them from uniform white/green backgrounds without a low gray cutoff.
            grid_pixels = (gray >= 110) & (gray <= 250) & neutral & (intensity < np.maximum(above, below) - 8)
        mask = grid_pixels.astype(np.uint8) * 255
        mask[: int(height * 0.15), :] = 0
        mask[int(height * 0.90) :, :] = 0
        # Keep long fragments, rather than bridging label-to-grid gaps. A curve
        # may obscure part of a grid line, especially after JPEG downsampling.
        kernel_width = max(15, round(width * 0.08)) | 1
        horizontal = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((1, kernel_width), np.uint8))
        rows = np.flatnonzero(np.count_nonzero(horizontal, axis=1) >= width * 0.30)
        groups = np.split(rows, np.flatnonzero(np.diff(rows) > 2) + 1)
        grid_lines = []
        for group in groups:
            if not group.size:
                continue
            xs = np.flatnonzero(np.any(horizontal[group] > 0, axis=0))
            grid_lines.append((round(float(np.median(group))), int(xs[0]), int(xs[-1])))

        # This parser's fixed 350..50 scale requires seven 50-mg/dL grid lines.
        # Choose the first aligned, evenly spaced group of seven; an additional
        # horizontal clock axis below the 50 line must not stretch the value scale.
        candidates = np.asarray(grid_lines)
        for start in range(len(grid_lines) - 6):
            top = grid_lines[start][0]
            for second in grid_lines[start + 1 :]:
                step = second[0] - top
                if step < 5 or step * 6 < height * 0.15:
                    continue
                targets = top + np.arange(7) * step
                indices = [int(np.argmin(np.abs(candidates[:, 0] - y))) for y in targets]
                grid = candidates[indices]
                if len(set(indices)) != 7 or np.max(np.abs(grid[:, 0] - targets)) > max(2, step * 0.06):
                    continue
                # Upper lines are usually unobscured; lower ones may be broken
                # by the glucose trace. Use the upper three to calibrate x.
                left = round(float(np.median(grid[:3, 1])))
                right = round(float(np.median(grid[:3, 2])))
                if (np.max(np.abs(grid[:3, 1] - left)) > width * 0.03
                        or np.max(np.abs(grid[:3, 2] - right)) > width * 0.03):
                    continue
                bottom = int(grid[-1, 0])
                if right - left >= width * 0.35:
                    return left, top, right, bottom
        raise GraphParseFailure(
            "GRAPH_NOT_RECOGNIZED",
            "50~350 mg/dL 그래프의 7개 격자선을 확인하지 못했습니다",
        )

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
            curve_pixels = gray > 165
        else:
            curve_pixels = gray < 105
        blue, green, red = np.moveaxis(plot.astype(np.int16), 2, 0)
        curve_pixels |= (red > 120) & (red - green > 60) & (red - blue > 60)
        # Grid intensity is already excluded. Removing long horizontal strokes
        # here would also erase genuine flat glucose plateaus.
        plot_height, plot_width = curve_pixels.shape
        edge_margin = max(1, round(plot_height * 0.002))
        readings: list[GraphReading] = []
        for index in range(READING_COUNT):
            # The right axis is next-day 00:00. 23:45 lies at 95/96 of its span.
            x = round(index * (plot_width - 1) / READING_COUNT)
            candidate_y = np.flatnonzero(curve_pixels[:, x])
            if index == 0 and not candidate_y.size and plot_width > 1:
                # The exact left edge can be clipped to background by screenshot
                # rasterization. Use its adjacent pixel, not a multi-minute window.
                candidate_y = np.flatnonzero(curve_pixels[:, 1])

            value: int | None = None
            flag = "MISSING"
            if candidate_y.size:
                runs = np.split(candidate_y, np.flatnonzero(np.diff(candidate_y) > 2) + 1)
                y = float(np.median(max(runs, key=len)))
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
