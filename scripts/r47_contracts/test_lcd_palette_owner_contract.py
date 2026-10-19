"""Hold every LCD palette value to its one owner, the `LcdTheme` enum.

`LcdThemePolicy.kt` declares each theme's text and background base color once,
and `LcdThemePolicy.resolve` derives every palette the LCD paints from them. A
copy of one of those values anywhere else in the shipped Kotlin or resources is
a second owner: it stays right only until the enum changes, and code that starts
from it treats the one theme it equals unlike every other. This reads the enum,
requires one notation for its values, and fails on a copy in any other
`src/main` Kotlin file or resource XML, in any color notation the tree uses.

Limits: black is exempt, since it is a theme's text color and also the
generic shadow and background color, and a copy of a *resolved* palette, which
`resolve` computes at run time, is not detectable from source.
"""

from __future__ import annotations

import re
import unittest
from typing import TYPE_CHECKING, Final

from r47_contracts._repo_paths import ANDROID_RES_ROOT, KOTLIN_R47ZEN_ROOT, REPO_ROOT

if TYPE_CHECKING:
    from pathlib import Path

_POLICY_FILE: Final = KOTLIN_R47ZEN_ROOT / "LcdThemePolicy.kt"
_ENUM_START: Final = "internal enum class LcdTheme("
_BASE_FIELD: Final = re.compile(
    r"\b(?P<field>lightTextBase|lightBackgroundBase)\s*=\s*(?P<value>[^,\n]+),",
)
_ONE_NOTATION: Final = re.compile(r'^"#(?P<rgb>[0-9A-Fa-f]{6})"\.toColorInt\(\)$')
_EXEMPT_RGB: Final = frozenset({0x000000})

_HEX_ARGB: Final = re.compile(r"\b0x(?P<argb>[0-9A-Fa-f]{8})\b")
_HASH_RGB: Final = re.compile(
    r"#(?P<digits>[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})(?![0-9A-Fa-f])",
)
_COLOR_RGB: Final = re.compile(
    r"Color\.(?:rgb\(|argb\(\s*\d+\s*,)\s*(?P<r>\d+)\s*,\s*(?P<g>\d+)\s*,\s*(?P<b>\d+)\s*\)",
)


def _enum_block(text: str) -> str:
    """Return the `LcdTheme` entries, from the enum header to the `;`."""
    start = text.find(_ENUM_START)
    if start < 0:
        message = f"{_ENUM_START} not found in {_POLICY_FILE}"
        raise AssertionError(message)
    end = text.find(";", start)
    return text[start:end]


def palette_bases(text: str) -> dict[int, str]:
    """Map each base RGB in the `LcdTheme` entries to the field that owns it.

    Fail on a base not written as `"#RRGGBB".toColorInt()`, the one notation.
    """
    bases: dict[int, str] = {}
    fields = list(_BASE_FIELD.finditer(_enum_block(text)))
    if not fields:
        message = f"no lightTextBase or lightBackgroundBase in {_POLICY_FILE}"
        raise AssertionError(message)
    for match in fields:
        value = match.group("value").strip()
        notation = _ONE_NOTATION.match(value)
        if notation is None:
            message = (
                f"LcdTheme {match.group('field')} = {value} is not in the one "
                'palette notation "#RRGGBB".toColorInt()'
            )
            raise AssertionError(message)
        bases[int(notation.group("rgb"), 16)] = match.group("field")
    return bases


def color_literals(text: str) -> list[tuple[int, int]]:
    """Return (line, RGB) for every color literal in Kotlin or resource text."""
    found: list[tuple[int, int]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        found.extend(
            (line_number, int(m.group("argb"), 16) & 0xFFFFFF)
            for m in _HEX_ARGB.finditer(line)
        )
        found.extend(
            (line_number, int(m.group("digits")[-6:], 16))
            for m in _HASH_RGB.finditer(line)
        )
        found.extend(
            (
                line_number,
                (int(m.group("r")) << 16)
                | (int(m.group("g")) << 8)
                | int(m.group("b")),
            )
            for m in _COLOR_RGB.finditer(line)
        )
    return found


def palette_copies(text: str, label: str, bases: dict[int, str]) -> list[str]:
    """Return one finding per literal in `text` equal to a palette base."""
    return [
        f"{label}:{line}: #{rgb:06X} copies the LcdTheme {bases[rgb]} value"
        for line, rgb in color_literals(text)
        if rgb in bases and rgb not in _EXEMPT_RGB
    ]


def _shipped_sources() -> list[Path]:
    kotlin = sorted(KOTLIN_R47ZEN_ROOT.rglob("*.kt"))
    resources = sorted(ANDROID_RES_ROOT.rglob("*.xml"))
    return [path for path in kotlin + resources if path != _POLICY_FILE]


class LcdPaletteOwnerContractTest(unittest.TestCase):
    """Every LCD palette value has one owner and one notation."""

    def test_no_shipped_source_copies_a_palette_value(self) -> None:
        """No src/main Kotlin or resource outside the policy repeats a base."""
        bases = palette_bases(_POLICY_FILE.read_text(encoding="utf-8"))
        sources = _shipped_sources()
        if not sources:
            message = "found no shipped Kotlin or resource files to scan"
            raise AssertionError(message)
        findings = [
            finding
            for path in sources
            for finding in palette_copies(
                path.read_text(encoding="utf-8"),
                str(path.relative_to(REPO_ROOT)),
                bases,
            )
        ]
        if findings:
            raise AssertionError("\n".join(findings))

    def test_scanner_finds_a_seeded_copy_in_every_notation(self) -> None:
        """The scanner flags a palette base however it is spelled."""
        bases = palette_bases(_POLICY_FILE.read_text(encoding="utf-8"))
        rgb = next(value for value in bases if value not in _EXEMPT_RGB)
        red, green, blue = rgb >> 16, (rgb >> 8) & 0xFF, rgb & 0xFF
        seeds = [
            f"private var color = 0xFF{rgb:06X}.toInt()",
            f'val color = "#{rgb:06x}".toColorInt()',
            f'val color = "#80{rgb:06X}".toColorInt()',
            f"val color = Color.rgb({red}, {green}, {blue})",
            f"val color = Color.argb(255, {red}, {green}, {blue})",
            f'<color name="lcd">#{rgb:06X}</color>',
        ]
        for seed in seeds:
            with self.subTest(seed=seed):
                if not palette_copies(seed, "seed", bases):
                    message = f"the scanner missed a seeded copy: {seed}"
                    raise AssertionError(message)
        clean = f"val color = 0xFF{rgb ^ 0x010101:06X}.toInt()"
        if palette_copies(clean, "seed", bases):
            message = f"the scanner flagged a value that is not a base: {clean}"
            raise AssertionError(message)

    def test_policy_rejects_a_second_notation(self) -> None:
        """A base written as an ARGB Int fails the one-notation rule."""
        policy = _POLICY_FILE.read_text(encoding="utf-8")
        first = _BASE_FIELD.search(_enum_block(policy))
        if first is None:
            message = f"no base field to seed in {_POLICY_FILE}"
            raise AssertionError(message)
        seeded = policy.replace(
            first.group(0),
            f"{first.group('field')} = 0xFF123456.toInt(),",
            1,
        )
        try:
            palette_bases(seeded)
        except AssertionError:
            return
        message = "palette_bases accepted a base written as an ARGB Int"
        raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
