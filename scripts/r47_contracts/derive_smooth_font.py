"""Derive the smooth key-label font from the pixel outlines of the standard font.

`C47__StandardFont.ttf` draws every glyph as a union of design-grid squares, so
a key label drawn from it at device resolution shows enlarged pixels. This
deriver rebuilds each glyph as curves and writes `C47__StandardFontSmooth.ttf`
into the Android assets:

1. rasterize the glyph on its design grid, one bitmap pixel per grid cell;
2. magnify the bitmap 4x with two passes of MMPX (`_mmpx`), which rebuilds
   diagonals and curves while it keeps corners and single pixels;
3. trace the magnified bitmap with potrace, through the `potracer` port;
4. write the curves back as TrueType quadratics on the same em.

The cmap, every advance width, and the vertical metrics stay the source's, so
the keypad's label-fit math measures the same font: only the outlines change.
The provenance golden records the source and output digests. The output is a
faithful re-derivation, not proof the glyphs are right: `test_smooth_font_contract`
holds each smooth glyph to the source's pixels on its own rasterizer.
"""

from __future__ import annotations

import hashlib
import io
import json
import math
import sys
from importlib import metadata
from typing import TYPE_CHECKING, Final

import numpy as np
import potrace
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.reverseContourPen import ReverseContourPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

from r47_contracts import _mmpx
from r47_contracts._repo_paths import ANDROID_APP_ROOT, DATA_ROOT, REPO_ROOT
from r47_contracts.runtime_fonts import resolve_runtime_font_path

if TYPE_CHECKING:
    from collections.abc import Sequence
    from pathlib import Path

    from fontTools.pens.basePen import AbstractPen
    from fontTools.ttLib.tables._g_l_y_f import Glyph, table__g_l_y_f

SOURCE_FONT_NAME: Final = "C47__StandardFont.ttf"
SMOOTH_FONT_NAME: Final = "C47__StandardFontSmooth.ttf"
SMOOTH_FAMILY_NAME: Final = "C47__StandardFontSmooth"
SMOOTH_FONT_PATH: Final = ANDROID_APP_ROOT / "assets" / "fonts" / SMOOTH_FONT_NAME
PROVENANCE_PATH: Final = DATA_ROOT / "r47_smooth_font_provenance.json"

# MMPX reads up to three pixels past the one it magnifies, so each glyph bitmap
# gets this much paper on every side; less would clamp a stroke against the edge
# and read it as a feature that crosses the border.
_PAD_PIXELS: Final = 3
# potrace smooths at about one pixel of the bitmap it traces. At one MMPX pass
# (2x) that is half a source pixel, enough to join the slash of % to a ring and
# fill cells of the shade patterns; at two passes (4x) neither happens.
_MMPX_PASSES: Final = 2
_MAGNIFICATION: Final = 2**_MMPX_PASSES
# potrace's corner threshold: 0 keeps every corner, 4/3 rounds every corner.
# 1.0, potrace's own default, keeps the right angles of E, T, and + while it
# rounds the diagonal and curved runs MMPX rebuilt.
_ALPHA_MAX: Final = 1.0
# Keep every speck: a single pixel is the dot of i, j, or a decimal point.
_TURD_SIZE: Final = 0
# The largest distance, in font units, a quadratic may stray from potrace's
# cubic.
_QUADRATIC_MAX_ERROR: Final = 1.0
# gasp: smooth at every size and never grid-fit, since the glyphs carry no
# hinting instructions.
_GASP_ALL_SIZES: Final = 0xFFFF
_GASP_DOGRAY_SYMMETRIC_SMOOTHING: Final = 0x000A
_GASP_VERSION_WITH_SYMMETRIC_FLAGS: Final = 1
# The source carries a control value table for instructions it does not have;
# hdmx, LTSH, and VDMX would record pixel widths of the pixel outlines.
_STALE_TABLES: Final = ("cvt ", "fpgm", "prep", "hdmx", "LTSH", "VDMX")
_FAMILY_NAME_IDS: Final = (1, 4, 6)
_UNIQUE_NAME_ID: Final = 3
_TOOLS: Final = ("fonttools", "numpy", "potracer")

type _Point = tuple[float, float]


class NotAGridFontError(ValueError):
    """Raised when a source glyph is not drawn on a pixel grid."""

    @classmethod
    def off_curve(cls, glyph_name: str) -> NotAGridFontError:
        """Build an error for a glyph with a curve point."""
        return cls(f"glyph {glyph_name} has an off-curve point: not a pixel font")

    @classmethod
    def no_grid(cls, grid: int) -> NotAGridFontError:
        """Build an error for a font whose coordinates share no usable grid."""
        return cls(f"the outlines share a grid of {grid} units: not a pixel font")


def design_grid(font: TTFont) -> int:
    """Return the design-grid pitch in font units: the GCD of every coordinate."""
    glyf: table__g_l_y_f = font["glyf"]
    grid = 0
    for name in font.getGlyphOrder():
        glyph = glyf[name]
        if glyph.numberOfContours == 0:
            continue
        for contour in _contours(glyph, glyf, name):
            for x, y in contour:
                grid = math.gcd(grid, int(x), int(y))
    # A pitch of one or two units means the outlines are not on a pixel grid
    # at all, and every later step would trace noise.
    minimum_pitch = 8
    if grid < minimum_pitch:
        raise NotAGridFontError.no_grid(grid)
    return grid


def _contours(glyph: Glyph, glyf: table__g_l_y_f, name: str) -> list[list[_Point]]:
    """Return the glyph's contours as polygons of their on-curve points.

    An off-curve point is accepted only where it sits on a neighbor, which
    makes its segment a straight line; a real curve would make the polygon
    wrong, and a pixel font has none.
    """
    coordinates, ends, flags = glyph.getCoordinates(glyf)
    contours: list[list[_Point]] = []
    start = 0
    for end in ends:
        points = [(float(x), float(y)) for x, y in coordinates[start : end + 1]]
        on_curve = [bool(flag & 1) for flag in flags[start : end + 1]]
        polygon: list[_Point] = []
        for index, point in enumerate(points):
            if on_curve[index]:
                polygon.append(point)
            elif point not in (points[index - 1], points[(index + 1) % len(points)]):
                raise NotAGridFontError.off_curve(name)
        contours.append(polygon)
        start = end + 1
    return contours


def _ink_at(contours: Sequence[Sequence[_Point]], px: float, py: float) -> bool:
    # Nonzero winding, the TrueType fill rule; the source outlines may overlap.
    winding = 0
    for contour in contours:
        for index, (x0, y0) in enumerate(contour):
            x1, y1 = contour[(index + 1) % len(contour)]
            if (y0 <= py < y1) or (y1 <= py < y0):
                cross_x = x0 + (py - y0) * (x1 - x0) / (y1 - y0)
                if cross_x > px:
                    winding += 1 if y1 > y0 else -1
    return winding != 0


def rasterize_glyph(
    glyph: Glyph,
    glyf: table__g_l_y_f,
    grid: int,
    name: str,
) -> tuple[_mmpx.Bitmap, int, int]:
    """Return the glyph's padded bitmap, ink as 1, and its left and top in units.

    Each bitmap pixel is one grid cell, sampled at the cell's center, so the
    bitmap is exactly the pixels the outline draws.
    """
    contours = _contours(glyph, glyf, name)
    xs = [x for contour in contours for x, _ in contour]
    ys = [y for contour in contours for _, y in contour]
    left = int(min(xs)) - _PAD_PIXELS * grid
    top = int(max(ys)) + _PAD_PIXELS * grid
    columns = round((max(xs) - min(xs)) / grid) + 2 * _PAD_PIXELS
    rows = round((max(ys) - min(ys)) / grid) + 2 * _PAD_PIXELS
    bitmap = [
        [
            int(
                _ink_at(
                    contours,
                    left + (column + 0.5) * grid,
                    top - (row + 0.5) * grid,
                ),
            )
            for column in range(columns)
        ]
        for row in range(rows)
    ]
    return bitmap, left, top


def _signed_area(points: Sequence[_Point]) -> float:
    return 0.5 * sum(
        x0 * y1 - x1 * y0
        for (x0, y0), (x1, y1) in zip(points, [*points[1:], points[0]], strict=True)
    )


def _inside(points: Sequence[_Point], px: float, py: float) -> bool:
    inside = False
    for (x0, y0), (x1, y1) in zip(points, [*points[1:], points[0]], strict=True):
        if (y0 > py) != (y1 > py):
            cross_x = x0 + (py - y0) * (x1 - x0) / (y1 - y0)
            if cross_x > px:
                inside = not inside
    return inside


def trace_glyph(
    bitmap: _mmpx.Bitmap,
    left: int,
    top: int,
    grid: int,
) -> list[tuple[RecordingPen, list[_Point]]]:
    """Trace a magnified bitmap; return each contour, in font units, as drawn.

    Each contour comes back as a recording and the polygon of its nodes. The
    polygon is coarse, but potrace's contours never cross, so it is enough to
    tell which contour lies inside which.
    """
    # potracer traces the False pixels, so ink goes in as False.
    paper = ~np.array(bitmap, dtype=bool)
    paths = potrace.Bitmap(paper).trace(
        turdsize=_TURD_SIZE,
        alphamax=_ALPHA_MAX,
        opticurve=False,
    )
    unit = grid / _MAGNIFICATION

    def to_font(point: object) -> _Point:
        x = float(getattr(point, "x"))  # noqa: B009 - potracer ships no types
        y = float(getattr(point, "y"))  # noqa: B009 - potracer ships no types
        return (left + x * unit, top - y * unit)

    traced: list[tuple[RecordingPen, list[_Point]]] = []
    for curve in paths:
        pen = RecordingPen()
        start = to_font(curve.start_point)
        polygon = [start]
        pen.moveTo(start)
        for segment in curve.segments:
            end = to_font(segment.end_point)
            if segment.is_corner:
                corner = to_font(segment.c)
                pen.lineTo(corner)
                pen.lineTo(end)
                polygon.append(corner)
            else:
                pen.curveTo(to_font(segment.c1), to_font(segment.c2), end)
            polygon.append(end)
        pen.closePath()
        traced.append((pen, polygon))
    return traced


def draw_oriented(
    traced: Sequence[tuple[RecordingPen, list[_Point]]],
    out: AbstractPen,
) -> None:
    """Replay the contours into `out`, outer ones clockwise, holes counter.

    TrueType fills by nonzero winding, so a hole must wind against the contour
    around it. A contour inside an even number of others is an outer edge.
    """
    for index, (pen, polygon) in enumerate(traced):
        probe_x, probe_y = polygon[0]
        depth = sum(
            1
            for other, (_, outer) in enumerate(traced)
            if other != index and _inside(outer, probe_x, probe_y)
        )
        wants_clockwise = depth % 2 == 0
        is_clockwise = _signed_area(polygon) < 0
        pen.replay(out if wants_clockwise == is_clockwise else ReverseContourPen(out))


def smooth_glyph(glyph: Glyph, glyf: table__g_l_y_f, grid: int, name: str) -> Glyph:
    """Return the glyph rebuilt from curves; an empty glyph stays empty."""
    tt_pen = TTGlyphPen(None)
    if glyph.numberOfContours != 0:
        magnified, left, top = rasterize_glyph(glyph, glyf, grid, name)
        for _ in range(_MMPX_PASSES):
            magnified = _mmpx.magnify_2x(magnified)
        traced = trace_glyph(magnified, left, top, grid)
        draw_oriented(
            traced,
            Cu2QuPen(tt_pen, _QUADRATIC_MAX_ERROR, reverse_direction=False),
        )
    return tt_pen.glyph()


def _rename(font: TTFont) -> None:
    for record in font["name"].names:
        if record.nameID in _FAMILY_NAME_IDS:
            record.string = SMOOTH_FAMILY_NAME
        elif record.nameID == _UNIQUE_NAME_ID:
            record.string = f"{SMOOTH_FAMILY_NAME} Regular"


def build_smooth_font_bytes(source_path: Path) -> bytes:
    """Return the smooth font derived from the pixel font at `source_path`."""
    font = TTFont(source_path)
    grid = design_grid(font)
    glyf: table__g_l_y_f = font["glyf"]
    hmtx = font["hmtx"]
    smoothed = {
        name: smooth_glyph(glyf[name], glyf, grid, name)
        for name in font.getGlyphOrder()
    }
    for name, glyph in smoothed.items():
        glyf[name] = glyph
    for name, glyph in smoothed.items():
        glyph.recalcBounds(glyf)
        advance, _ = hmtx[name]
        hmtx[name] = (advance, glyph.xMin if glyph.numberOfContours else 0)
    for tag in _STALE_TABLES:
        if tag in font:
            del font[tag]
    if "gasp" in font:
        font["gasp"].version = _GASP_VERSION_WITH_SYMMETRIC_FLAGS
        font["gasp"].gaspRange = {_GASP_ALL_SIZES: _GASP_DOGRAY_SYMMETRIC_SMOOTHING}
    _rename(font)
    # Keep the source's head timestamps, so the same source always derives the
    # same bytes and the provenance digest means something.
    font.recalcTimestamp = False
    buffer = io.BytesIO()
    font.save(buffer)
    return buffer.getvalue()


def sha256_hex(data: bytes) -> str:
    """Return the SHA-256 digest of `data` as lowercase hex."""
    return hashlib.sha256(data).hexdigest()


def build_smooth_font_provenance_payload() -> dict[str, object]:
    """Return the provenance golden for the live source font."""
    source_path = resolve_runtime_font_path(SOURCE_FONT_NAME)
    smooth = build_smooth_font_bytes(source_path)
    return _provenance(source_path, smooth)


def _provenance(source_path: Path, smooth: bytes) -> dict[str, object]:
    return {
        "source_font": SOURCE_FONT_NAME,
        "source_sha256": sha256_hex(source_path.read_bytes()),
        "smooth_font": str(SMOOTH_FONT_PATH.relative_to(REPO_ROOT)),
        "smooth_sha256": sha256_hex(smooth),
        "parameters": {
            "magnifier": "MMPX 2x",
            "magnifier_passes": _MMPX_PASSES,
            "pad_pixels": _PAD_PIXELS,
            "potrace_alphamax": _ALPHA_MAX,
            "potrace_turdsize": _TURD_SIZE,
            "quadratic_max_error_units": _QUADRATIC_MAX_ERROR,
        },
        "tools": {tool: metadata.version(tool) for tool in _TOOLS},
    }


def main() -> int:
    """Derive the smooth font and its provenance golden."""
    source_path = resolve_runtime_font_path(SOURCE_FONT_NAME)
    smooth = build_smooth_font_bytes(source_path)
    SMOOTH_FONT_PATH.parent.mkdir(parents=True, exist_ok=True)
    SMOOTH_FONT_PATH.write_bytes(smooth)
    payload = _provenance(source_path, smooth)
    PROVENANCE_PATH.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    sys.stdout.write(f"wrote {SMOOTH_FONT_PATH.relative_to(REPO_ROOT)}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
