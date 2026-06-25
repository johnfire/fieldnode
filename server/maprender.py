"""
Server-side static map renderer for /nearby/map.png.

Draws the phone's location (a blue dot) plus a numbered red pin per lead onto an OpenStreetMap tile
image. No third-party map key and no billing — tiles come straight from OSM, composited with
staticmap + Pillow. The pin numbers match the /nearby lead list order (nearest first), so a glance at
the map and a tap on the list line up. Kept apart from app.py so the FastAPI layer stays thin.
"""
import io
import math

from PIL import ImageDraw, ImageFont
from staticmap import StaticMap

TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
# OSM's tile usage policy requires a meaningful User-Agent; identify ourselves honestly.
USER_AGENT = "fieldnode-forwarder/1.0 (self-hosted personal use; +https://github.com/johnfire/fieldnode)"
TILE_SIZE = 256
MIN_ZOOM, MAX_ZOOM = 3, 16
DEFAULT_ZOOM = 14  # when every point coincides (e.g. a single lead) there's no span to fit

ME_COLOR = (33, 99, 232)
LEAD_COLOR = (214, 40, 40)
WHITE = (255, 255, 255)


def _lon_to_world_x(lon: float, zoom: float) -> float:
    return (lon + 180.0) / 360.0 * TILE_SIZE * (2 ** zoom)


def _lat_to_world_y(lat: float, zoom: float) -> float:
    sin_lat = math.sin(math.radians(lat))
    return (0.5 - math.log((1 + sin_lat) / (1 - sin_lat)) / (4 * math.pi)) * TILE_SIZE * (2 ** zoom)


def _fit_zoom(points, width: int, height: int, usable: float = 0.82) -> int:
    """Largest zoom at which every point lands within `usable` fraction of the canvas."""
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    if (max(lats) - min(lats) < 1e-6) and (max(lons) - min(lons) < 1e-6):
        return DEFAULT_ZOOM
    for zoom in range(MAX_ZOOM, MIN_ZOOM - 1, -1):
        xs = [_lon_to_world_x(lon, zoom) for lon in lons]
        ys = [_lat_to_world_y(lat, zoom) for lat in lats]
        if (max(xs) - min(xs) <= width * usable) and (max(ys) - min(ys) <= height * usable):
            return zoom
    return MIN_ZOOM


def _font(size: int):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def _draw_centered(draw: ImageDraw.ImageDraw, xy, text: str, font, fill) -> None:
    """Center text at xy, working with or without anchor support (bitmap fallback font)."""
    try:
        draw.text(xy, text, fill=fill, font=font, anchor="mm")
    except (ValueError, TypeError):
        bbox = draw.textbbox((0, 0), text, font=font)
        draw.text((xy[0] - (bbox[2] - bbox[0]) / 2 - bbox[0],
                   xy[1] - (bbox[3] - bbox[1]) / 2 - bbox[1]), text, fill=fill, font=font)


def _build_map(width: int, height: int) -> StaticMap:
    try:
        return StaticMap(width, height, url_template=TILE_URL, headers={"User-Agent": USER_AGENT})
    except TypeError:  # older staticmap without a headers kwarg
        return StaticMap(width, height, url_template=TILE_URL)


def render_leads_map(me, leads, width: int = 720, height: int = 480) -> bytes:
    """
    me: (lat, lng) of the phone, or None. leads: ordered list of (lat, lng), nearest first.
    Returns PNG bytes: a blue 'you are here' dot + red pins numbered 1..N matching the list.
    """
    points = list(leads)
    if me:
        points.append(me)
    if not points:
        points = [(0.0, 0.0)]

    zoom = _fit_zoom(points, width, height)
    center_lon = (min(p[1] for p in points) + max(p[1] for p in points)) / 2
    center_lat = (min(p[0] for p in points) + max(p[0] for p in points)) / 2

    image = _build_map(width, height).render(zoom=zoom, center=[center_lon, center_lat]).convert("RGBA")

    center_x = _lon_to_world_x(center_lon, zoom)
    center_y = _lat_to_world_y(center_lat, zoom)

    def to_px(lat, lon):
        x = width / 2 + (_lon_to_world_x(lon, zoom) - center_x)
        y = height / 2 + (_lat_to_world_y(lat, zoom) - center_y)
        return max(0.0, min(float(width), x)), max(0.0, min(float(height), y))

    draw = ImageDraw.Draw(image)
    font = _font(15)

    for number, (lat, lon) in enumerate(leads, start=1):
        x, y = to_px(lat, lon)
        radius = 13
        draw.ellipse([x - radius, y - radius, x + radius, y + radius],
                     fill=LEAD_COLOR, outline=WHITE, width=2)
        _draw_centered(draw, (x, y - 1), str(number), font, WHITE)

    if me:
        x, y = to_px(me[0], me[1])
        radius = 8
        draw.ellipse([x - radius, y - radius, x + radius, y + radius],
                     fill=ME_COLOR, outline=WHITE, width=3)

    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, format="PNG")
    return buffer.getvalue()
