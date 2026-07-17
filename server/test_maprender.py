"""Unit tests for the static-map renderer's pin-placement math, plus one integration test of the
full render pipeline with the network tile fetch mocked out (no real call to openstreetmap.org)."""
import staticmap
from PIL import Image

import maprender


# --- zoom fitting ----------------------------------------------------------------------------------


def test_fit_zoom_uses_default_when_every_point_coincides():
    zoom = maprender._fit_zoom([(48.1, 10.8), (48.1, 10.8)], 720, 480)
    assert zoom == maprender.DEFAULT_ZOOM


def test_fit_zoom_stays_within_configured_bounds():
    # Wildly spread points force the smallest zoom that still fits everything on the canvas.
    zoom = maprender._fit_zoom([(-60.0, -170.0), (70.0, 170.0)], 720, 480)
    assert maprender.MIN_ZOOM <= zoom <= maprender.MAX_ZOOM


def test_fit_zoom_is_lower_for_a_wider_spread_of_points():
    tight = maprender._fit_zoom([(48.10, 10.80), (48.11, 10.81)], 720, 480)
    wide = maprender._fit_zoom([(40.0, 0.0), (55.0, 25.0)], 720, 480)
    assert wide < tight


# --- projection math ---------------------------------------------------------------------------


def test_lon_to_world_x_is_monotonic_increasing():
    zoom = 10
    assert maprender._lon_to_world_x(-10.0, zoom) < maprender._lon_to_world_x(10.0, zoom)


def test_lat_to_world_y_is_monotonic_decreasing_with_latitude():
    # Web Mercator: higher latitude (further north) maps to a SMALLER y (further up the image).
    zoom = 10
    assert maprender._lat_to_world_y(50.0, zoom) < maprender._lat_to_world_y(10.0, zoom)


def test_world_coordinates_scale_with_zoom():
    lon = 11.5
    assert maprender._lon_to_world_x(lon, 10) < maprender._lon_to_world_x(lon, 12)


# --- font fallback -----------------------------------------------------------------------------


def test_font_returns_something_usable_even_without_dejavu(monkeypatch):
    # Simulate neither DejaVu path being available (e.g. a slim image missing fonts-dejavu-core), but
    # leave Pillow's own default-font loading alone — load_default() uses truetype() internally too.
    import PIL.ImageFont as image_font

    real_truetype = image_font.truetype

    def fail_only_for_dejavu(path, *args, **kwargs):
        if "dejavu" in str(path).lower():
            raise OSError("missing")
        return real_truetype(path, *args, **kwargs)

    monkeypatch.setattr(image_font, "truetype", fail_only_for_dejavu)
    font = maprender._font(15)
    assert font is not None


# --- full render, network mocked -----------------------------------------------------------------


def test_render_leads_map_returns_a_decodable_png_with_correct_size(monkeypatch):
    def fake_render(self, zoom, center):
        return Image.new("RGB", (self.width, self.height), "white")

    monkeypatch.setattr(staticmap.StaticMap, "render", fake_render)

    me = (48.1374, 11.5755)
    leads = [(48.14, 11.58), (48.10, 11.50)]
    png_bytes = maprender.render_leads_map(me, leads, width=400, height=300)

    image = Image.open(__import__("io").BytesIO(png_bytes))
    assert image.format == "PNG"
    assert image.size == (400, 300)


def test_render_leads_map_handles_no_points_at_all(monkeypatch):
    def fake_render(self, zoom, center):
        return Image.new("RGB", (self.width, self.height), "white")

    monkeypatch.setattr(staticmap.StaticMap, "render", fake_render)

    png_bytes = maprender.render_leads_map(None, [], width=200, height=200)

    image = Image.open(__import__("io").BytesIO(png_bytes))
    assert image.size == (200, 200)
