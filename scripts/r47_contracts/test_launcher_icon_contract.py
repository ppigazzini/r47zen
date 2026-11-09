"""Hold the launcher icon the app ships to its drawing source.

`android/app/src/main/assets/icons/r47zen.svg` is the drawing source, and
nothing generates the runtime vectors from it, so a change must reach each one
by hand. This holds that the full icons (`mipmap/ic_launcher.xml` and
`mipmap/ic_launcher_round.xml`) draw the SVG's paths in its colors, that the
adaptive icon's foreground draws the same paths but the background, and that
the adaptive icon's background color, `ic_launcher_background`, is the SVG's.

Limits: paths are compared in order, as path data with whitespace collapsed;
the foreground's inset and the vectors' viewport are not compared.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._repo_paths import ANDROID_APP_ROOT, ANDROID_RES_ROOT

_SVG: Final = ANDROID_APP_ROOT / "assets" / "icons" / "r47zen.svg"
_FULL_ICONS: Final = (
    ANDROID_RES_ROOT / "mipmap" / "ic_launcher.xml",
    ANDROID_RES_ROOT / "mipmap" / "ic_launcher_round.xml",
)
_FOREGROUND: Final = ANDROID_RES_ROOT / "drawable" / "ic_launcher_foreground.xml"
_COLORS: Final = ANDROID_RES_ROOT / "values" / "colors.xml"
_PATH_ELEMENT: Final = re.compile(r"<path\b(?P<attrs>[^>]*)/?>", re.DOTALL)
_ATTRIBUTE: Final = re.compile(r'(?P<name>[\w:]+)="(?P<value>[^"]*)"')
_STYLE_FILL: Final = re.compile(r"(?:^|;)\s*fill\s*:\s*(?P<fill>#[0-9A-Fa-f]{6})")
_BACKGROUND_COLOR: Final = re.compile(
    r'<color name="ic_launcher_background">(?P<color>#[0-9A-Fa-f]{6})</color>',
)
# SVG's initial fill value is black.
_SVG_DEFAULT_FILL: Final = "#000000"

Path = tuple[str, str]


def _paths(text: str, fill_of: str, data_of: str) -> list[dict[str, str]]:
    elements = []
    for element in _PATH_ELEMENT.finditer(text):
        attrs = {m["name"]: m["value"] for m in _ATTRIBUTE.finditer(element["attrs"])}
        elements.append(
            {
                "id": attrs.get("id", ""),
                "fill": attrs.get(fill_of, ""),
                "style": attrs.get("style", ""),
                "d": " ".join(attrs.get(data_of, "").split()),
            },
        )
    return elements


def svg_paths() -> list[tuple[str, Path]]:
    """Return the SVG's paths as (id, (fill, path data)), fill upper-cased."""
    result = []
    for path in _paths(_SVG.read_text(encoding="utf-8"), "fill", "d"):
        style = _STYLE_FILL.search(path["style"])
        fill = style["fill"] if style else path["fill"] or _SVG_DEFAULT_FILL
        result.append((path["id"], (fill.upper(), path["d"])))
    return result


def vector_paths(text: str) -> list[Path]:
    """Return a vector drawable's paths as (fill, path data), fill upper-cased."""
    return [
        (path["fill"].upper(), path["d"])
        for path in _paths(text, "android:fillColor", "android:pathData")
    ]


class LauncherIconContractTest(unittest.TestCase):
    """The shipped launcher icon draws what its drawing source draws."""

    def test_the_svg_draws_a_background_then_the_rest(self) -> None:
        """The SVG's first path is the background the adaptive icon splits off."""
        ids = [path_id for path_id, _ in svg_paths()]
        if not ids or ids[0] != "background":
            message = f"the SVG's first path should be the background: {ids}"
            raise AssertionError(message)

    def test_each_full_icon_draws_the_svg(self) -> None:
        """Each full icon has the SVG's paths, in order, in the SVG's colors."""
        expected = [path for _, path in svg_paths()]
        for icon in _FULL_ICONS:
            actual = vector_paths(icon.read_text(encoding="utf-8"))
            if actual != expected:
                pairs = enumerate(zip(actual, expected, strict=False))
                differ = [
                    f"path {index}: shipped {got}, SVG {want}"
                    for index, (got, want) in pairs
                    if got != want
                ]
                message = f"{icon.name} differs from r47zen.svg: {differ or 'count'}"
                raise AssertionError(message)

    def test_the_foreground_draws_the_svg_but_its_background(self) -> None:
        """The adaptive foreground has every SVG path after the background."""
        expected = [path for _, path in svg_paths()[1:]]
        actual = vector_paths(_FOREGROUND.read_text(encoding="utf-8"))
        if actual != expected:
            message = f"{_FOREGROUND.name} differs from r47zen.svg's foreground paths"
            raise AssertionError(message)

    def test_the_adaptive_background_is_the_svgs(self) -> None:
        """ic_launcher_background, the adaptive icon's background, is the SVG's."""
        match = _BACKGROUND_COLOR.search(_COLORS.read_text(encoding="utf-8"))
        if match is None:
            message = "colors.xml defines no ic_launcher_background"
            raise AssertionError(message)
        svg_background = svg_paths()[0][1][0]
        if match["color"].upper() != svg_background:
            message = (
                f"ic_launcher_background is {match['color']}, r47zen.svg's background"
                f" {svg_background}"
            )
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
