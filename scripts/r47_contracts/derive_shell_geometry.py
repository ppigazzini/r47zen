"""Derive the live shell and LCD geometry contract from measured R47 assets."""

from __future__ import annotations

import json
import struct
import sys
from dataclasses import dataclass
from typing import TYPE_CHECKING

from r47_contracts._contract_data import (
    load_android_app_contract,
)
from r47_contracts._contract_data import (
    mapping_member as contract_mapping_member,
)
from r47_contracts._contract_data import (
    number_member as contract_number_member,
)
from r47_contracts._repo_paths import (
    ANDROID_RES_ROOT,
    R47_GEOMETRY_DATA_PATH,
    REPO_ROOT,
)

if TYPE_CHECKING:
    from pathlib import Path

_LEGACY_SHARED_CANVAS_WIDTH = 526.0
_LEGACY_SHARED_CANVAS_HEIGHT = 980.0
_LEGACY_TEXTURE_CONTRACT_WIDTH = 537.0
_LEGACY_TEXTURE_CONTRACT_HEIGHT = 1005.0

_LEGACY_NATIVE_SHELL_DRAW_CORNER_RADIUS = 24.0
_NATIVE_SHELL_DRAW_CORNER_RADIUS_VISUAL_REDUCTION = 3.0

_LEGACY_SCALED_MODE_FIT_TRIM_LEFT = 12.0
_LEGACY_SCALED_MODE_FIT_TRIM_TOP = 14.0
_LEGACY_SCALED_MODE_FIT_TRIM_RIGHT = 12.0
_LEGACY_SCALED_MODE_FIT_TRIM_BOTTOM = 16.0

_LEGACY_TOP_BEZEL_SETTINGS_TAP_HEIGHT = 67.5
_LEGACY_LCD_WINDOW_LEFT = 25.5
_LEGACY_LCD_WINDOW_HEIGHT = 266.7

_LEGACY_NON_SOFTKEY_VIEW_HEIGHT = 68.0

_DRAWABLE_NAMES = ("r47_texture", "r47_background")
_DENSITY_BUCKETS = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
_DENSITY_SCALE_FACTORS = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


class ShellGeometryContractError(ValueError):
    """Raised when the measured shell-geometry inputs are invalid."""

    @classmethod
    def invalid_data(
        cls,
        label: str,
        expected: str,
        actual: object,
    ) -> ShellGeometryContractError:
        """Build an error for invalid structured geometry data."""
        message = f"Expected {label} to be {expected}, got {actual!r}"
        return cls(message)

    @classmethod
    def not_webp(cls, path: Path) -> ShellGeometryContractError:
        """Build an error for a non-WebP drawable asset."""
        message = f"Expected a WebP file at {path}"
        return cls(message)

    @classmethod
    def unreadable_webp_size(cls, path: Path) -> ShellGeometryContractError:
        """Build an error for a WebP file without readable dimensions."""
        message = f"Could not determine the WebP size for {path}"
        return cls(message)


@dataclass(frozen=True)
class _Rect:
    left: float
    top: float
    width: float
    height: float


def _rounded(value: float) -> float:
    return round(value, 6)


def _nearest_int(value: float) -> float:
    return float(round(value))


def _rounded_rect(rect: _Rect) -> dict[str, float]:
    return {
        "left": _rounded(rect.left),
        "top": _rounded(rect.top),
        "width": _rounded(rect.width),
        "height": _rounded(rect.height),
    }


def _require_mapping(value: object, *, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ShellGeometryContractError.invalid_data(label, "a JSON object", value)
    return {
        _require_string(key, label=f"{label}.key"): nested_value
        for key, nested_value in value.items()
    }


def _require_list(value: object, *, label: str) -> list[object]:
    if not isinstance(value, list):
        raise ShellGeometryContractError.invalid_data(label, "a JSON list", value)
    return list(value)


def _require_number(value: object, *, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise ShellGeometryContractError.invalid_data(label, "a number", value)
    return float(value)


def _require_string(value: object, *, label: str) -> str:
    if not isinstance(value, str):
        raise ShellGeometryContractError.invalid_data(label, "a string", value)
    return value


def _require_scalar(value: object, *, label: str) -> str | int | float:
    if isinstance(value, bool) or not isinstance(value, str | int | float):
        raise ShellGeometryContractError.invalid_data(label, "a scalar value", value)
    return value


def _load_geometry(path: Path = R47_GEOMETRY_DATA_PATH) -> dict[str, object]:
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    return _require_mapping(payload, label="geometry document")


def _index_tables(
    geometry: dict[str, object],
) -> dict[str, dict[str, dict[str, object]]]:
    tables = _require_list(geometry["tables"], label="tables")
    indexed_tables: dict[str, dict[str, dict[str, object]]] = {}
    for raw_table in tables:
        table = _require_mapping(raw_table, label="geometry table")
        table_id = _require_string(table.get("id"), label="table.id")
        raw_entries = _require_list(table.get("entries"), label=f"{table_id}.entries")
        entries: dict[str, dict[str, object]] = {}
        for raw_entry in raw_entries:
            entry = _require_mapping(raw_entry, label=f"{table_id} entry")
            entry_id = _require_string(entry.get("id"), label=f"{table_id}.entry.id")
            entries[entry_id] = entry
        indexed_tables[table_id] = entries
    return indexed_tables


def _reference_lcd_rect(geometry: dict[str, object]) -> _Rect:
    tables = _index_tables(geometry)
    horizontal_lcd = tables["horizontal_main"]["lcd"]
    vertical_lcd = tables["vertical_main"]["lcd"]
    return _Rect(
        left=_require_number(horizontal_lcd.get("start"), label="horizontal lcd.start"),
        top=_require_number(vertical_lcd.get("start"), label="vertical lcd.start"),
        width=_require_number(horizontal_lcd.get("span"), label="horizontal lcd.span"),
        height=_require_number(vertical_lcd.get("span"), label="vertical lcd.span"),
    )


def _center_x(rect: _Rect) -> float:
    return rect.left + rect.width / 2.0


def _center_y(rect: _Rect) -> float:
    return rect.top + rect.height / 2.0


def _aspect_ratio(rect: _Rect) -> float:
    return rect.width / rect.height


def _webp_size(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise ShellGeometryContractError.not_webp(path)

    offset = 12
    while offset + 8 <= len(data):
        chunk = data[offset : offset + 4]
        size = struct.unpack_from("<I", data, offset + 4)[0]
        payload = data[offset + 8 : offset + 8 + size]
        if chunk == b"VP8X":
            width = 1 + int.from_bytes(payload[4:7], "little")
            height = 1 + int.from_bytes(payload[7:10], "little")
            return width, height
        if chunk == b"VP8L":
            bits = int.from_bytes(payload[1:5], "little")
            width = (bits & 0x3FFF) + 1
            height = ((bits >> 14) & 0x3FFF) + 1
            return width, height
        if chunk == b"VP8 ":
            start = payload.find(b"\x9d\x01\x2a")
            if start != -1 and start + 7 <= len(payload):
                width, height = struct.unpack_from("<HH", payload, start + 3)
                return width & 0x3FFF, height & 0x3FFF
        offset += 8 + size + (size % 2)

    raise ShellGeometryContractError.unreadable_webp_size(path)


def _measure_drawable_assets() -> dict[str, dict[str, dict[str, int | float | str]]]:
    assets: dict[str, dict[str, dict[str, int | float | str]]] = {}
    for drawable_name in _DRAWABLE_NAMES:
        density_map: dict[str, dict[str, int | float | str]] = {}
        for density in _DENSITY_BUCKETS:
            path = ANDROID_RES_ROOT / f"drawable-{density}" / f"{drawable_name}.webp"
            width, height = _webp_size(path)
            density_map[density] = {
                "path": str(path.relative_to(REPO_ROOT)),
                "width": width,
                "height": height,
                "density_scale": _DENSITY_SCALE_FACTORS[density],
            }
        assets[drawable_name] = density_map
    return assets


def _rebase_width(value: float, logical_width: float) -> float:
    return value * logical_width / _LEGACY_TEXTURE_CONTRACT_WIDTH


def _rebase_height(value: float, logical_height: float) -> float:
    return value * logical_height / _LEGACY_TEXTURE_CONTRACT_HEIGHT


def _rebase_shared_width(value: float, logical_width: float) -> float:
    return value * logical_width / _LEGACY_SHARED_CANVAS_WIDTH


def _rebase_shared_height(value: float, logical_height: float) -> float:
    return value * logical_height / _LEGACY_SHARED_CANVAS_HEIGHT


def build_shell_geometry_payload() -> dict[str, object]:
    """Build the shell and LCD payload used by the Android contract tests."""
    geometry = _load_geometry()
    reference_frame = _require_mapping(
        geometry["reference_frame"],
        label="reference_frame",
    )
    reference_width = _require_number(
        reference_frame.get("width"),
        label="reference_frame.width",
    )
    reference_height = _require_number(
        reference_frame.get("height"),
        label="reference_frame.height",
    )
    drawable_assets = _measure_drawable_assets()
    android_app_contract = load_android_app_contract()
    chrome_contract = contract_mapping_member(
        android_app_contract,
        "chrome",
        label="android_app_contract",
    )
    scaled_mode_fit_trim = contract_mapping_member(
        chrome_contract,
        "scaled_mode_fit_trim",
        label="android_app_contract.chrome",
    )
    lcd_window_contract = contract_mapping_member(
        chrome_contract,
        "lcd_window",
        label="android_app_contract.chrome",
    )
    lcd_frame_buffer_contract = contract_mapping_member(
        chrome_contract,
        "lcd_frame_buffer",
        label="android_app_contract.chrome",
    )

    logical_width = reference_width
    logical_height = reference_height
    logical_scale_x = 1.0
    logical_scale_y = 1.0

    native_shell_draw_corner_radius = contract_number_member(
        chrome_contract,
        "native_shell_draw_corner_radius",
        label="android_app_contract.chrome",
    )
    scaled_mode_fit_trim_left = contract_number_member(
        scaled_mode_fit_trim,
        "left",
        label="android_app_contract.chrome.scaled_mode_fit_trim",
    )
    scaled_mode_fit_trim_top = contract_number_member(
        scaled_mode_fit_trim,
        "top",
        label="android_app_contract.chrome.scaled_mode_fit_trim",
    )
    scaled_mode_fit_trim_right = contract_number_member(
        scaled_mode_fit_trim,
        "right",
        label="android_app_contract.chrome.scaled_mode_fit_trim",
    )
    scaled_mode_fit_trim_bottom = contract_number_member(
        scaled_mode_fit_trim,
        "bottom",
        label="android_app_contract.chrome.scaled_mode_fit_trim",
    )
    top_bezel_settings_tap_height = contract_number_member(
        chrome_contract,
        "settings_strip_tap_height",
        label="android_app_contract.chrome",
    )
    lcd_window_left = contract_number_member(
        lcd_window_contract,
        "left",
        label="android_app_contract.chrome.lcd_window",
    )
    lcd_window_top = contract_number_member(
        lcd_window_contract,
        "top",
        label="android_app_contract.chrome.lcd_window",
    )
    lcd_window_width = contract_number_member(
        lcd_window_contract,
        "width",
        label="android_app_contract.chrome.lcd_window",
    )
    lcd_window_height = contract_number_member(
        lcd_window_contract,
        "height",
        label="android_app_contract.chrome.lcd_window",
    )
    non_softkey_view_height = contract_number_member(
        chrome_contract,
        "non_softkey_view_height",
        label="android_app_contract.chrome",
    )

    logical_android_lcd_window = _Rect(
        left=lcd_window_left,
        top=lcd_window_top,
        width=lcd_window_width,
        height=lcd_window_height,
    )
    logical_real_lcd = _reference_lcd_rect(geometry)
    android_lcd_rect = _rounded_rect(logical_android_lcd_window)
    real_lcd_rect = _rounded_rect(logical_real_lcd)

    logical_shell_center_x = logical_width / 2.0
    logical_shell_center_y = logical_height / 2.0

    return {
        "source": {
            "dataset": _require_string(geometry.get("dataset"), label="dataset"),
            "geometry_path": str(R47_GEOMETRY_DATA_PATH.relative_to(REPO_ROOT)),
            "version": _require_scalar(geometry.get("version"), label="version"),
            "reference_width": reference_width,
            "reference_height": reference_height,
        },
        "lcd_contract": {
            "real_calculator": {
                "role": "physical R47 LCD measured from reference image",
                "coordinate_space": "reference image / logical canvas",
                "rect": dict(real_lcd_rect),
            },
            "android_app": {
                "role": "implemented Android calculator LCD window",
                "coordinate_space": "logical canvas",
                "rect": dict(android_lcd_rect),
                "settings_strip_tap_height": top_bezel_settings_tap_height,
                "frame_buffer": {
                    "pixel_width": contract_number_member(
                        lcd_frame_buffer_contract,
                        "pixel_width",
                        label="android_app_contract.chrome.lcd_frame_buffer",
                    ),
                    "pixel_height": contract_number_member(
                        lcd_frame_buffer_contract,
                        "pixel_height",
                        label="android_app_contract.chrome.lcd_frame_buffer",
                    ),
                },
            },
        },
        "drawable_assets": drawable_assets,
        "logical_canvas": {
            "source": "measured reference_frame",
            "width": logical_width,
            "height": logical_height,
            "native_shell_draw_corner_radius": native_shell_draw_corner_radius,
            "scaled_mode_fit_trim": {
                "left": scaled_mode_fit_trim_left,
                "top": scaled_mode_fit_trim_top,
                "right": scaled_mode_fit_trim_right,
                "bottom": scaled_mode_fit_trim_bottom,
            },
            "top_bezel_settings_tap_height": top_bezel_settings_tap_height,
            "lcd_window": dict(android_lcd_rect),
            "real_measured_lcd": dict(real_lcd_rect),
            "keypad": {
                "standard_left": _rounded(134.0 * logical_scale_x),
                "standard_pitch": _rounded(272.0 * logical_scale_x),
                "standard_key_width": _rounded(192.0 * logical_scale_x),
                "matrix_first_visible_left": _rounded(465.0 * logical_scale_x),
                "matrix_pitch": _rounded(331.0 * logical_scale_x),
                "matrix_key_width": _rounded(228.0 * logical_scale_x),
                "enter_width": _rounded(462.0 * logical_scale_x),
                "row_height": _rounded(144.0 * logical_scale_y),
                "row_step": _rounded(260.0 * logical_scale_y),
                "softkey_row_top": _rounded(1290.0 * logical_scale_y),
                "first_small_row_top": _rounded(1550.0 * logical_scale_y),
                "enter_row_top": _rounded(2070.0 * logical_scale_y),
                "first_large_row_top": _rounded(2330.0 * logical_scale_y),
                "non_softkey_view_height": non_softkey_view_height,
            },
        },
        "checks": {
            "logical_scale_x": _rounded(logical_scale_x),
            "logical_scale_y": _rounded(logical_scale_y),
            "legacy_texture_to_logical_scale_x": _rounded(
                logical_width / _LEGACY_TEXTURE_CONTRACT_WIDTH,
            ),
            "legacy_texture_to_logical_scale_y": _rounded(
                logical_height / _LEGACY_TEXTURE_CONTRACT_HEIGHT,
            ),
            "lcd_window_center_x_delta_vs_shell_center": _rounded(
                _center_x(logical_android_lcd_window) - logical_shell_center_x,
            ),
            "lcd_window_center_y_delta_vs_shell_center": _rounded(
                _center_y(logical_android_lcd_window) - logical_shell_center_y,
            ),
            "lcd_window_vs_real_lcd_center_x_delta": _rounded(
                _center_x(logical_android_lcd_window) - _center_x(logical_real_lcd),
            ),
            "lcd_window_vs_real_lcd_center_y_delta": _rounded(
                _center_y(logical_android_lcd_window) - _center_y(logical_real_lcd),
            ),
            "lcd_window_vs_real_lcd_width_delta": _rounded(
                logical_android_lcd_window.width - logical_real_lcd.width,
            ),
            "lcd_window_vs_real_lcd_height_delta": _rounded(
                logical_android_lcd_window.height - logical_real_lcd.height,
            ),
            "lcd_window_vs_real_lcd_aspect_ratio_delta_pct": _rounded(
                (
                    (
                        _aspect_ratio(logical_android_lcd_window)
                        / _aspect_ratio(logical_real_lcd)
                    )
                    - 1.0
                )
                * 100.0,
            ),
        },
    }


def main() -> int:
    """Write the shell-geometry payload to standard output as formatted JSON."""
    json.dump(build_shell_geometry_payload(), sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
