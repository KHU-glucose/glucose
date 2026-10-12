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
        x = round(left + index * (right - left) / 96)
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


def screenshot_graph(values, *, dark=False, red_curve=False, missing=None, missing_grid=None, thickness=3):
    """Known numeric curve in a screenshot layout with labels and a clock axis."""
    background, grid, curve = (24, 85, 240) if dark else (255, 185, 20)
    image = np.full((800, 1200, 3), background, dtype=np.uint8)
    left, top, right, bottom = 120, 220, 1080, 700
    for index, y in enumerate(np.linspace(top, bottom, 7, dtype=int)):
        if index == missing_grid:
            continue
        cv2.line(image, (left, int(y)), (right, int(y)), (grid, grid, grid), 1)
        cv2.putText(image, str(350-index*50), (58, int(y)+8), cv2.FONT_HERSHEY_SIMPLEX,
                    .65, (curve, curve, curve), 2, cv2.LINE_AA)
    # Separate clock baseline below the plot. It is not the 50-mg/dL grid.
    cv2.line(image, (left, 760), (right, 760), (grid, grid, grid), 1)
    color = (100, 100, 255) if red_curve else (curve, curve, curve)
    points=[]
    for index, value in enumerate(values):
        if missing and missing[0] <= index < missing[1]:
            if len(points)>1:
                cv2.polylines(image,[np.asarray(points)],False,color,thickness)
            points=[]
            continue
        x=round(left+index*(right-left)/96)
        y=round(top+(350-value)/300*(bottom-top))
        points.append((x,y))
    if len(points)>1:
        cv2.polylines(image,[np.asarray(points)],False,color,thickness)
    return image


def encoded(image):
    ok,data=cv2.imencode('.png',image)
    assert ok
    return data.tobytes()


@pytest.mark.parametrize('dark',[False,True])
def test_plot_bounds_exclude_tick_labels_and_clock_axis(dark):
    image=screenshot_graph(np.full(97,120),dark=dark)
    bounds=LibreDailyGraphParser._find_graph_bounds(image)
    assert np.all(np.abs(np.asarray(bounds)-np.asarray([120,220,1080,700]))<=2)


@pytest.mark.parametrize('dark',[False,True])
def test_flat_plateau_is_preserved_at_all_96_times(dark):
    parser=LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result=parser.parse(encoded(screenshot_graph(np.full(97,120),dark=dark)))
    assert len(result['readings'])==96
    assert result['coverage_ratio']==1
    assert all(abs(r.value-120)<=2 for r in result['readings'])
    assert result['gaps']==[]


def test_red_curve_is_read_even_when_its_grayscale_is_not_dark():
    parser=LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result=parser.parse(encoded(screenshot_graph(np.full(97,65),red_curve=True)))
    assert result['coverage_ratio']==1
    assert all(abs(r.value-65)<=2 and r.flag=='NORMAL' for r in result['readings'])


def test_2345_samples_before_next_midnight():
    values=np.full(97,130)
    values[93]=180
    values[94]=200
    values[95]=220
    values[96]=190
    parser=LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result=parser.parse(encoded(screenshot_graph(values)))
    assert result['readings'][-1].time=='23:45'
    assert abs(result['readings'][-1].value-220)<=5
    assert abs(result['readings'][94].value-200)<=5


def test_real_gap_is_not_filled_from_neighboring_curve_pixels():
    parser=LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result=parser.parse(encoded(screenshot_graph(np.full(97,120),missing=(35,49))))
    assert all(r.value is None for r in result['readings'][35:49])
    assert result['readings'][34].value==120
    assert result['readings'][49].value==120
    assert any(g.from_time=='08:45' and g.to=='12:15' for g in result['gaps'])


def test_incomplete_scale_is_rejected_instead_of_using_clock_as_grid_line():
    parser=LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    with pytest.raises(GraphParseFailure,match='7개 격자선'):
        parser.parse(encoded(screenshot_graph(np.full(97,120),missing_grid=0)))


def test_single_pixel_speck_in_gap_does_not_create_a_reading():
    image = screenshot_graph(np.full(97,120),missing=(35,49))
    x = round(120+42*(1080-120)/96)
    image[500,x] = (0,0,0)
    parser = LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result = parser.parse(encoded(image))
    assert result['readings'][42].value is None
    assert all(r.value is None for r in result['readings'][35:49])


@pytest.mark.parametrize('dark',[False,True])
def test_genuinely_one_pixel_thin_curve_is_not_discarded(dark):
    image = screenshot_graph(np.full(97,120),dark=dark,missing=(0,97))
    color = (240,240,240) if dark else (20,20,20)
    cv2.line(image,(120,588),(1080,588),color,1)
    parser = LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result = parser.parse(encoded(image))
    assert result['coverage_ratio']==1
    assert all(abs(r.value-120)<=2 for r in result['readings'])


@pytest.mark.parametrize('dark,red_curve', [(False,False), (True,False), (False,True)])
@pytest.mark.parametrize('rising', [False,True])
def test_thick_curve_at_steep_corner_tracks_center_path(dark,red_curve,rising):
    values = np.full(97,230)
    values[44] = 210
    values[45:] = 125
    values[45] = 130
    if rising:
        values = 350-values
    parser = LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result = parser.parse(encoded(screenshot_graph(values,dark=dark,red_curve=red_curve,thickness=13)))
    # The known bend is at 11:15, not at the middle of the thick vertical span.
    assert abs(result['readings'][45].value-int(values[45]))<=5
    assert all(abs(result['readings'][i].value-int(values[i]))<=3 for i in [43,47,48])


def test_thick_steep_curve_preserves_a_real_gap():
    values = np.full(97,125)
    values[32:35] = [230,180,130]
    parser = LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result = parser.parse(encoded(screenshot_graph(values,missing=(35,49),thickness=13)))
    assert all(r.value is None for r in result['readings'][35:49])
    assert result['readings'][34].value is not None
    assert result['readings'][49].value is not None


@pytest.mark.parametrize('dark,red_curve', [(False,False), (True,False), (False,True)])
@pytest.mark.parametrize('valley', [False,True])
def test_thick_peak_and_valley_recover_known_vertex(dark,red_curve,valley):
    values = np.full(97,130)
    values[39:50] = [130,150,170,190,210,230,210,190,170,150,130]
    if valley:
        values = 350-values
    parser = LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    result = parser.parse(encoded(screenshot_graph(values,dark=dark,red_curve=red_curve,thickness=13)))
    assert abs(result['readings'][44].value-int(values[44]))<=3
    assert all(abs(result['readings'][i].value-int(values[i]))<=3 for i in [42,43,45,46])


def test_empty_curve_reports_too_little_data():
    parser = LibreDailyGraphParser(date_reader=lambda _:'2026-10-01')
    with pytest.raises(GraphParseFailure) as error:
        parser.parse(encoded(screenshot_graph(np.full(97,120),missing=(0,97))))
    assert error.value.code=='TOO_LITTLE_DATA'


def test_fractional_column_samples_a_linear_curve_at_the_requested_time():
    image = np.full((800,1200,3),255,dtype=np.uint8)
    left,top,right,bottom = 120,220,1093,700
    cv2.line(image,(left,bottom),(right,top),(20,20,20),3)
    readings = LibreDailyGraphParser()._extract_readings(
        image,x_left=left,y_top=top,x_right=right,y_bottom=bottom)
    # The 973-pixel span does not divide into 96 integer sample columns.
    assert all(abs(r.value-(50+i*300/96))<=1 for i,r in enumerate(readings))
