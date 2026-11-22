"""Hold the smooth key-label font to the pixel font it is derived from.

`derive_smooth_font` rebuilds every glyph of `C47__StandardFont.ttf` as curves
and writes `C47__StandardFontSmooth.ttf`. Re-running the deriver re-blesses
whatever it makes, so these tests are the oracle, on a rasterizer of their own
that reads both fonts as TrueType quadratics:

- the smooth font maps the same codepoints to the same glyphs, with the same
  advance widths and vertical metrics, so the keypad's label layout cannot move;
- sampled at the centers of the source's design-grid cells, every glyph draws
  the source's pixels except at a cell on the source's own edge, keeps the
  source's count of strokes and counters, and at four samples per cell side
  keeps at least half of every stroke inked and every counter open;
- the provenance golden names the live source's digest and the committed
  font's, so a source an upstream sync moved fails here until re-derived.

Limits: the samples cannot see a boundary that moves less than a quarter cell,
and nothing here judges how a glyph looks.
"""

from __future__ import annotations

import hashlib
import json
import math
import unittest
from collections import deque
from typing import TYPE_CHECKING, Final

import numpy as np
from fontTools.ttLib import TTFont

from r47_contracts._repo_paths import DATA_ROOT, REPO_ROOT
from r47_contracts.runtime_fonts import resolve_runtime_font_path

if TYPE_CHECKING:
    from fontTools.ttLib.tables._g_l_y_f import Glyph, table__g_l_y_f
    from numpy.typing import NDArray

_SOURCE_FONT: Final = "C47__StandardFont.ttf"
_PROVENANCE: Final = DATA_ROOT / "r47_smooth_font_provenance.json"
# Each quadratic segment is drawn as this many chords; at the em sizes here a
# chord strays well under a sample spacing from its curve.
_CURVE_STEPS: Final = 8
_SUBSAMPLES: Final = 4
_MIN_KEPT_FRACTION: Final = 0.5
_ON_CURVE: Final = 1
# The glyphs whose stroke or counter count the smooth font changes. Each is a
# known defect, not a tolerance: a glyph that starts to change fails, and so
# does one listed here that stops changing, so the list only shrinks.
_KNOWN_TOPOLOGY_CHANGES: Final = {
    # The two chevrons, apart in the source, touch at their tips.
    "guillemotleft",
    "guillemotright",
    # A paper cell inside the bend of the small 7 is closed off from the rest.
    "uni2087",
    "uni2167",
    "uni2466",
}

type _Polygon = NDArray[np.float64]
type _Mask = NDArray[np.bool_]


def _quadratic_points(
    start: tuple[float, float],
    control: tuple[float, float],
    end: tuple[float, float],
) -> list[tuple[float, float]]:
    points: list[tuple[float, float]] = []
    for step in range(1, _CURVE_STEPS + 1):
        t = step / _CURVE_STEPS
        u = 1 - t
        points.append(
            (
                u * u * start[0] + 2 * u * t * control[0] + t * t * end[0],
                u * u * start[1] + 2 * u * t * control[1] + t * t * end[1],
            ),
        )
    return points


def _flatten_contour(
    points: list[tuple[float, float]],
    on_curve: list[bool],
) -> _Polygon:
    # Two off-curve points in a row imply an on-curve point between them.
    if not any(on_curve):
        first, last = points[0], points[-1]
        points = [((first[0] + last[0]) / 2, (first[1] + last[1]) / 2), *points]
        on_curve = [True, *on_curve]
    shift = on_curve.index(True)
    points = points[shift:] + points[:shift]
    on_curve = on_curve[shift:] + on_curve[:shift]
    polyline = [points[0]]
    current = points[0]
    ring = list(zip([*points[1:], points[0]], [*on_curve[1:], True], strict=True))
    index = 0
    while index < len(ring):
        point, is_on = ring[index]
        if is_on:
            polyline.append(point)
            current = point
            index += 1
            continue
        following, following_on = ring[index + 1]
        end = (
            following
            if following_on
            else ((point[0] + following[0]) / 2, (point[1] + following[1]) / 2)
        )
        polyline.extend(_quadratic_points(current, point, end))
        current = end
        index += 2 if following_on else 1
    return np.array(polyline, dtype=np.float64)


def _polygons(glyph: Glyph, glyf: table__g_l_y_f) -> list[_Polygon]:
    if glyph.numberOfContours == 0:
        return []
    coordinates, ends, flags = glyph.getCoordinates(glyf)
    polygons: list[_Polygon] = []
    start = 0
    for end in ends:
        points = [(float(x), float(y)) for x, y in coordinates[start : end + 1]]
        on_curve = [bool(flag & _ON_CURVE) for flag in flags[start : end + 1]]
        polygons.append(_flatten_contour(points, on_curve))
        start = end + 1
    return polygons


def _ink(polygons: list[_Polygon], xs: _Polygon, ys: _Polygon) -> _Mask:
    """Return nonzero-winding ink at every (x, y) sample, one row per y."""
    mask = np.zeros((len(ys), len(xs)), dtype=bool)
    if not polygons:
        return mask
    starts = np.concatenate(polygons)
    ends = np.concatenate([np.roll(polygon, -1, axis=0) for polygon in polygons])
    x0, y0, x1, y1 = starts[:, 0], starts[:, 1], ends[:, 0], ends[:, 1]
    for row, y in enumerate(ys):
        rising = (y0 <= y) & (y < y1)
        falling = (y1 <= y) & (y < y0)
        crossing = rising | falling
        if not crossing.any():
            continue
        cross_x = x0[crossing] + (y - y0[crossing]) * (x1[crossing] - x0[crossing]) / (
            y1[crossing] - y0[crossing]
        )
        direction = np.where(rising[crossing], 1, -1)
        order = np.argsort(cross_x)
        sorted_x = cross_x[order]
        # The winding at x sums the directions of the crossings right of x.
        right_sums = np.concatenate([np.cumsum(direction[order][::-1])[::-1], [0]])
        mask[row] = right_sums[np.searchsorted(sorted_x, xs, side="right")] != 0
    return mask


def _components(mask: _Mask, *, diagonal: bool) -> list[list[tuple[int, int]]]:
    steps = [(-1, 0), (1, 0), (0, -1), (0, 1)]
    if diagonal:
        steps += [(-1, -1), (-1, 1), (1, -1), (1, 1)]
    height, width = mask.shape
    seen = np.zeros_like(mask)
    found: list[list[tuple[int, int]]] = []
    for row, column in zip(*np.nonzero(mask), strict=True):
        if seen[row, column]:
            continue
        seen[row, column] = True
        cells = [(int(row), int(column))]
        queue = deque(cells)
        while queue:
            cell_row, cell_column = queue.popleft()
            for d_row, d_column in steps:
                r, c = cell_row + d_row, cell_column + d_column
                if 0 <= r < height and 0 <= c < width and mask[r, c] and not seen[r, c]:
                    seen[r, c] = True
                    cells.append((r, c))
                    queue.append((r, c))
        found.append(cells)
    return found


def _counters(paper: _Mask) -> list[list[tuple[int, int]]]:
    height, width = paper.shape
    return [
        cells
        for cells in _components(paper, diagonal=False)
        if all(0 < r < height - 1 and 0 < c < width - 1 for r, c in cells)
    ]


class _GlyphSamples:
    """One glyph of both fonts, sampled on the source's design grid."""

    def __init__(
        self,
        source: list[_Polygon],
        smooth: list[_Polygon],
        grid: float,
    ) -> None:
        points = np.concatenate(source)
        left, right = points[:, 0].min() - grid, points[:, 0].max() + grid
        bottom, top = points[:, 1].min() - grid, points[:, 1].max() + grid
        columns = round((right - left) / grid)
        rows = round((top - bottom) / grid)
        xs = left + (np.arange(columns) + 0.5) * grid
        ys = top - (np.arange(rows) + 0.5) * grid
        self.source = _ink(source, xs, ys)
        self.smooth = _ink(smooth, xs, ys)
        fine = grid / _SUBSAMPLES
        fine_xs = left + (np.arange(columns * _SUBSAMPLES) + 0.5) * fine
        fine_ys = top - (np.arange(rows * _SUBSAMPLES) + 0.5) * fine
        self.smooth_fine = _ink(smooth, fine_xs, fine_ys)

    def interior_changes(self) -> int:
        padded = np.pad(self.source, 1)
        on_edge = np.zeros_like(self.source)
        for d_row, d_column in ((0, 1), (2, 1), (1, 0), (1, 2)):
            neighbor = padded[
                d_row : d_row + self.source.shape[0],
                d_column : d_column + self.source.shape[1],
            ]
            on_edge |= neighbor != self.source
        return int(((self.source != self.smooth) & ~on_edge).sum())

    def topology(self, mask: _Mask) -> tuple[int, int]:
        return (len(_components(mask, diagonal=True)), len(_counters(~mask)))

    def failures(self) -> list[str]:
        """Return each way the smooth glyph strays from the source's pixels."""
        found: list[str] = []
        interior = self.interior_changes()
        if interior:
            found.append(f"{interior} interior cells changed")
        for stroke in _components(self.source, diagonal=True):
            kept = self.kept_fraction(stroke, ink=True)
            if kept < _MIN_KEPT_FRACTION:
                found.append(f"a stroke keeps {kept:.2f} of its ink")
        for counter in _counters(~self.source):
            kept = self.kept_fraction(counter, ink=False)
            if kept < _MIN_KEPT_FRACTION:
                found.append(f"a counter stays {kept:.2f} open")
        return found

    def kept_fraction(self, cells: list[tuple[int, int]], *, ink: bool) -> float:
        kept = 0
        for row, column in cells:
            block = self.smooth_fine[
                row * _SUBSAMPLES : (row + 1) * _SUBSAMPLES,
                column * _SUBSAMPLES : (column + 1) * _SUBSAMPLES,
            ]
            kept += int(block.sum()) if ink else int((~block).sum())
        return kept / (len(cells) * _SUBSAMPLES * _SUBSAMPLES)


class SmoothFontContractTest(unittest.TestCase):
    """Hold the smooth font to the source's metrics, pixels, and provenance."""

    source: TTFont
    smooth: TTFont
    provenance: dict[str, object]

    @classmethod
    def setUpClass(cls) -> None:
        """Load both fonts and the provenance golden."""
        cls.provenance = json.loads(_PROVENANCE.read_text(encoding="utf-8"))
        cls.source = TTFont(resolve_runtime_font_path(_SOURCE_FONT))
        cls.smooth = TTFont(REPO_ROOT / str(cls.provenance["smooth_font"]))

    def test_cmap_matches_source(self) -> None:
        """Map every codepoint to the glyph the source maps it to."""
        if self.source.getBestCmap() != self.smooth.getBestCmap():
            message = "The smooth font maps a codepoint the source does not"
            raise AssertionError(message)

    def test_advance_widths_match_source(self) -> None:
        """Keep every advance width, so label widths measure the same."""
        source_hmtx = self.source["hmtx"]
        smooth_hmtx = self.smooth["hmtx"]
        moved = [
            name
            for name in self.source.getGlyphOrder()
            if source_hmtx[name][0] != smooth_hmtx[name][0]
        ]
        if moved:
            message = f"Advance widths differ from the source: {moved}"
            raise AssertionError(message)

    def test_vertical_metrics_match_source(self) -> None:
        """Keep the em, ascent, descent, and line gap the label layout reads."""
        fields = (
            ("head", "unitsPerEm"),
            ("hhea", "ascent"),
            ("hhea", "descent"),
            ("hhea", "lineGap"),
            ("OS/2", "sTypoAscender"),
            ("OS/2", "sTypoDescender"),
            ("OS/2", "sTypoLineGap"),
            ("OS/2", "usWinAscent"),
            ("OS/2", "usWinDescent"),
        )
        moved = [
            f"{table}.{field}"
            for table, field in fields
            if getattr(self.source[table], field) != getattr(self.smooth[table], field)
        ]
        if moved:
            message = f"Vertical metrics differ from the source: {moved}"
            raise AssertionError(message)

    def test_glyphs_keep_the_source_pixels(self) -> None:
        """Move only edge pixels, and keep strokes, counters, and their area."""
        source_glyf = self.source["glyf"]
        smooth_glyf = self.smooth["glyf"]
        grid = _design_grid(self.source)
        failures: list[str] = []
        changed_topology: set[str] = set()
        for name in self.source.getGlyphOrder():
            source = _polygons(source_glyf[name], source_glyf)
            if not source:
                continue
            smooth = _polygons(smooth_glyf[name], smooth_glyf)
            samples = _GlyphSamples(source, smooth, grid)
            failures += [f"{name}: {failure}" for failure in samples.failures()]
            if samples.topology(samples.source) != samples.topology(samples.smooth):
                changed_topology.add(name)
        if changed_topology != _KNOWN_TOPOLOGY_CHANGES:
            failures.append(
                "stroke or counter counts changed in "
                f"{sorted(changed_topology - _KNOWN_TOPOLOGY_CHANGES)}, and stopped "
                f"changing in {sorted(_KNOWN_TOPOLOGY_CHANGES - changed_topology)}",
            )
        if failures:
            message = "Smooth glyphs strayed from the source pixels:\n" + "\n".join(
                failures,
            )
            raise AssertionError(message)

    def test_provenance_names_the_live_source(self) -> None:
        """Fail until the font is re-derived from the source a sync moved."""
        live = hashlib.sha256(
            resolve_runtime_font_path(_SOURCE_FONT).read_bytes(),
        ).hexdigest()
        if self.provenance["source_sha256"] != live:
            message = (
                "C47__StandardFont.ttf moved: run `PYTHONPATH=scripts uv run "
                "--locked --group dev python -m r47_contracts.derive_smooth_font` "
                "and review the glyphs"
            )
            raise AssertionError(message)

    def test_provenance_names_the_committed_font(self) -> None:
        """Refuse a smooth font edited by hand rather than re-derived."""
        committed = (REPO_ROOT / str(self.provenance["smooth_font"])).read_bytes()
        if self.provenance["smooth_sha256"] != hashlib.sha256(committed).hexdigest():
            message = "The committed smooth font is not the one the deriver recorded"
            raise AssertionError(message)


def _design_grid(font: TTFont) -> float:
    """Return the source's design-grid pitch: the GCD of every coordinate."""
    glyf = font["glyf"]
    grid = 0
    for name in font.getGlyphOrder():
        glyph = glyf[name]
        if glyph.numberOfContours == 0:
            continue
        coordinates, _, _ = glyph.getCoordinates(glyf)
        for x, y in coordinates:
            grid = math.gcd(grid, int(x), int(y))
    return float(grid)


if __name__ == "__main__":
    unittest.main()
