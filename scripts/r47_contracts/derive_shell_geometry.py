"""Derive the live shell and LCD geometry contract from measured R47 assets."""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from typing import TYPE_CHECKING

from r47_contracts._contract_data import (
    load_android_ui_contract,
    load_physical_geometry,
)
from r47_contracts._contract_data import mapping_member as contract_mapping_member
from r47_contracts._contract_data import number_member as contract_number_member
from r47_contracts._repo_paths import (
    R47_ANDROID_UI_CONTRACT_PATH,
    R47_PHYSICAL_GEOMETRY_DATA_PATH,
    REPO_ROOT,
)

if TYPE_CHECKING:
    from pathlib import Path

_LEGACY_TEXTURE_CONTRACT_WIDTH = 537.0
_LEGACY_TEXTURE_CONTRACT_HEIGHT = 1005.0


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


@dataclass(frozen=True)
class _Rect:
    left: float
    top: float
    width: float
    height: float


def _rounded(value: float) -> float:
    return round(value, 6)


def _rounded_rect(rect: _Rect) -> dict[str, float]:
    return {
        "left": _rounded(rect.left),
        "top": _rounded(rect.top),
        "width": _rounded(rect.width),
        "height": _rounded(rect.height),
    }


def _rect_from_contract(
    mapping: dict[str, object],
    *,
    label: str,
) -> _Rect:
    return _Rect(
        left=contract_number_member(mapping, "left", label=label),
        top=contract_number_member(mapping, "top", label=label),
        width=contract_number_member(mapping, "width", label=label),
        height=contract_number_member(mapping, "height", label=label),
    )


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


def _load_geometry(
    path: Path = R47_PHYSICAL_GEOMETRY_DATA_PATH,
) -> dict[str, object]:
    return _require_mapping(load_physical_geometry(path), label="geometry document")


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
    raw_real_lcd = geometry.get("real_lcd")
    if raw_real_lcd is not None:
        real_lcd = _require_mapping(raw_real_lcd, label="real_lcd")
        return _Rect(
            left=_require_number(real_lcd.get("left"), label="real_lcd.left"),
            top=_require_number(real_lcd.get("top"), label="real_lcd.top"),
            width=_require_number(real_lcd.get("width"), label="real_lcd.width"),
            height=_require_number(real_lcd.get("height"), label="real_lcd.height"),
        )

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
    android_app_contract = load_android_ui_contract()
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
    lcd_windows_contract = contract_mapping_member(
        chrome_contract,
        "lcd_windows",
        label="android_app_contract.chrome",
    )
    native_lcd_window_contract = contract_mapping_member(
        lcd_windows_contract,
        "native",
        label="android_app_contract.chrome.lcd_windows",
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
    non_softkey_view_height = contract_number_member(
        chrome_contract,
        "non_softkey_view_height",
        label="android_app_contract.chrome",
    )

    logical_native_lcd_window = _rect_from_contract(
        native_lcd_window_contract,
        label="android_app_contract.chrome.lcd_windows.native",
    )
    logical_real_lcd = _reference_lcd_rect(geometry)
    native_lcd_rect = _rounded_rect(logical_native_lcd_window)
    real_lcd_rect = _rounded_rect(logical_real_lcd)

    logical_shell_center_x = logical_width / 2.0
    logical_shell_center_y = logical_height / 2.0
    row_height = 144.0 * logical_scale_y
    row_step = 260.0 * logical_scale_y
    row_gap = row_step - row_height
    softkey_row_top = 1290.0 * logical_scale_y

    return {
        "source": {
            "dataset": _require_string(geometry.get("dataset"), label="dataset"),
            "android_contract_path": str(
                R47_ANDROID_UI_CONTRACT_PATH.relative_to(REPO_ROOT),
            ),
            "geometry_path": str(
                R47_PHYSICAL_GEOMETRY_DATA_PATH.relative_to(REPO_ROOT),
            ),
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
                "mode_rects": {
                    "native": {
                        "role": "native painted shell LCD window",
                        "rect": dict(native_lcd_rect),
                    },
                },
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
            "lcd_windows": {
                "native": dict(native_lcd_rect),
            },
            "real_measured_lcd": dict(real_lcd_rect),
            "keypad": {
                "standard_left": _rounded(134.0 * logical_scale_x),
                "standard_pitch": _rounded(272.0 * logical_scale_x),
                "standard_key_width": _rounded(192.0 * logical_scale_x),
                "matrix_first_visible_left": _rounded(465.0 * logical_scale_x),
                "matrix_pitch": _rounded(331.0 * logical_scale_x),
                "matrix_key_width": _rounded(228.0 * logical_scale_x),
                "enter_width": _rounded(462.0 * logical_scale_x),
                "row_height": _rounded(row_height),
                "row_step": _rounded(row_step),
                "row_gap": _rounded(row_gap),
                "softkey_row_top": _rounded(softkey_row_top),
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
            "native_lcd_window_center_x_delta_vs_shell_center": _rounded(
                _center_x(logical_native_lcd_window) - logical_shell_center_x,
            ),
            "native_lcd_window_center_y_delta_vs_shell_center": _rounded(
                _center_y(logical_native_lcd_window) - logical_shell_center_y,
            ),
            "native_lcd_window_vs_real_lcd_center_x_delta": _rounded(
                _center_x(logical_native_lcd_window) - _center_x(logical_real_lcd),
            ),
            "native_lcd_window_vs_real_lcd_center_y_delta": _rounded(
                _center_y(logical_native_lcd_window) - _center_y(logical_real_lcd),
            ),
            "native_lcd_window_vs_real_lcd_width_delta": _rounded(
                logical_native_lcd_window.width - logical_real_lcd.width,
            ),
            "native_lcd_window_vs_real_lcd_height_delta": _rounded(
                logical_native_lcd_window.height - logical_real_lcd.height,
            ),
            "native_lcd_window_vs_real_lcd_aspect_ratio_delta_pct": _rounded(
                (
                    (
                        _aspect_ratio(logical_native_lcd_window)
                        / _aspect_ratio(logical_real_lcd)
                    )
                    - 1.0
                )
                * 100.0,
            ),
            "native_lcd_window_vs_frame_buffer_aspect_ratio_delta_pct": _rounded(
                (
                    (
                        _aspect_ratio(logical_native_lcd_window)
                        / (
                            contract_number_member(
                                lcd_frame_buffer_contract,
                                "pixel_width",
                                label="android_app_contract.chrome.lcd_frame_buffer",
                            )
                            / contract_number_member(
                                lcd_frame_buffer_contract,
                                "pixel_height",
                                label="android_app_contract.chrome.lcd_frame_buffer",
                            )
                        )
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
