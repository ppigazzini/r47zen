"""Lock the native packed-LCD row stride to the Kotlin display contract.

A genuine cross-language oracle: it parses the native `LCD_ROW_SIZE_BYTES`
`#define` in `hal/lcd.h` and the Kotlin `PACKED_ROW_SIZE_BYTES` constant and
proves they agree. The packed display buffer is produced natively and consumed
in Kotlin across the JNI boundary, so a silent drift between the two row strides
misaligns every row of the snapshot copied to the overlay. It also checks the
Kotlin packed buffer size stays the row stride times the pixel height.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._kotlin_consts import parse_kotlin_const_values
from r47_contracts._repo_paths import ANDROID_CPP_ROOT, KOTLIN_R47ZEN_ROOT

_NATIVE_LCD_HEADER: Final = ANDROID_CPP_ROOT / "hal" / "lcd.h"
_KOTLIN_GEOMETRY: Final = KOTLIN_R47ZEN_ROOT / "R47Geometry.kt"
_ROW_SIZE_DEFINE: Final = re.compile(r"#define\s+LCD_ROW_SIZE_BYTES\s+(?P<value>\d+)")


def _native_row_size_bytes() -> int:
    """Read the `LCD_ROW_SIZE_BYTES` value from the native HAL header."""
    text = _NATIVE_LCD_HEADER.read_text(encoding="utf-8")
    match = _ROW_SIZE_DEFINE.search(text)
    if match is None:
        message = f"LCD_ROW_SIZE_BYTES #define not found in {_NATIVE_LCD_HEADER}"
        raise AssertionError(message)
    return int(match.group("value"))


def _kotlin_constants() -> dict[str, float]:
    """Parse the Kotlin display geometry constants."""
    return parse_kotlin_const_values(_KOTLIN_GEOMETRY)


class LcdPackedRowContractTest(unittest.TestCase):
    """Verify the native and Kotlin packed-LCD row strides stay identical."""

    def test_native_row_stride_matches_kotlin(self) -> None:
        """`LCD_ROW_SIZE_BYTES` must equal Kotlin `PACKED_ROW_SIZE_BYTES`."""
        native = _native_row_size_bytes()
        kotlin = int(_kotlin_constants()["PACKED_ROW_SIZE_BYTES"])
        if native != kotlin:
            message = (
                "Packed LCD row stride drift across the JNI boundary: native "
                f"LCD_ROW_SIZE_BYTES={native} but Kotlin "
                f"PACKED_ROW_SIZE_BYTES={kotlin}"
            )
            raise AssertionError(message)

    def test_kotlin_buffer_size_is_row_stride_times_height(self) -> None:
        """The Kotlin packed buffer must be the row stride times pixel height."""
        constants = _kotlin_constants()
        row = int(constants["PACKED_ROW_SIZE_BYTES"])
        height = int(constants["PIXEL_HEIGHT"])
        buffer_size = int(constants["PACKED_BUFFER_SIZE"])
        if buffer_size != row * height:
            message = (
                "PACKED_BUFFER_SIZE is not PIXEL_HEIGHT * PACKED_ROW_SIZE_BYTES: "
                f"{buffer_size} != {height} * {row}"
            )
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
