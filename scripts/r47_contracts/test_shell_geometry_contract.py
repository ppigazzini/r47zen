"""Lock the Kotlin shell geometry constants to the Python shell contract."""

import unittest

from r47_contracts._kotlin_consts import parse_kotlin_const_values
from r47_contracts._repo_paths import KOTLIN_R47_ROOT
from r47_contracts.derive_shell_geometry import build_shell_geometry_payload

_KOTLIN_GEOMETRY_PATH = KOTLIN_R47_ROOT / "R47Geometry.kt"


def _assert_float_equal(actual: float, expected: float, *, places: int = 6) -> None:
    tolerance = 10 ** (-places)
    if abs(actual - expected) > tolerance:
        message = f"Expected {expected} but saw {actual} within {places} places"
        raise AssertionError(message)


def _assert_equal(actual: object, expected: object) -> None:
    if actual != expected:
        message = f"Expected {expected!r} but saw {actual!r}"
        raise AssertionError(message)


def _require_string(value: object, *, label: str) -> str:
    if not isinstance(value, str):
        message = f"Expected {label} to be a string, got {value!r}"
        raise TypeError(message)
    return value


def _require_mapping(value: object, *, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        message = f"Expected {label} to be a mapping, got {value!r}"
        raise TypeError(message)
    return {
        _require_string(key, label=f"{label}.key"): nested_value
        for key, nested_value in value.items()
    }


def _require_number(value: object, *, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        message = f"Expected {label} to be numeric, got {value!r}"
        raise TypeError(message)
    return float(value)


def _mapping_member(
    mapping: dict[str, object],
    key: str,
    *,
    label: str,
) -> dict[str, object]:
    return _require_mapping(mapping[key], label=f"{label}.{key}")


def _number_member(mapping: dict[str, object], key: str, *, label: str) -> float:
    return _require_number(mapping[key], label=f"{label}.{key}")


class ShellGeometryContractTest(unittest.TestCase):
    """Verify that Kotlin shell constants still match the Python contract payload."""

    @classmethod
    def setUpClass(cls) -> None:
        """Load the shared Python payload and Kotlin constants once for the module."""
        cls.payload: dict[str, object] = build_shell_geometry_payload()
        cls.kotlin: dict[str, float] = parse_kotlin_const_values(
            _KOTLIN_GEOMETRY_PATH,
        )

    def test_drawable_sizes_match_density_buckets(self) -> None:
        """Match the checked-in shell assets to the expected Android density sizes."""
        expected = {
            "mdpi": (360, 673),
            "hdpi": (540, 1010),
            "xhdpi": (720, 1346),
            "xxhdpi": (1080, 2019),
            "xxxhdpi": (1440, 2692),
        }
        drawable_assets = _mapping_member(
            self.payload,
            "drawable_assets",
            label="payload",
        )
        for drawable_name in ("r47_texture", "r47_background"):
            density_map = _mapping_member(
                drawable_assets,
                drawable_name,
                label="drawable_assets",
            )
            for density, (width, height) in expected.items():
                asset = _mapping_member(
                    density_map,
                    density,
                    label=f"drawable_assets.{drawable_name}",
                )
                _assert_equal(_number_member(asset, "width", label="asset"), width)
                _assert_equal(
                    _number_member(asset, "height", label="asset"),
                    height,
                )

    def test_logical_canvas_matches_reference_frame(self) -> None:
        """Keep the live logical canvas identical to the measured reference frame."""
        logical_canvas = _mapping_member(
            self.payload,
            "logical_canvas",
            label="payload",
        )
        source = _mapping_member(self.payload, "source", label="payload")
        _assert_float_equal(
            self.kotlin["LOGICAL_CANVAS_WIDTH"],
            _number_member(logical_canvas, "width", label="logical_canvas"),
        )
        _assert_float_equal(
            self.kotlin["LOGICAL_CANVAS_HEIGHT"],
            _number_member(logical_canvas, "height", label="logical_canvas"),
        )
        _assert_float_equal(
            _number_member(logical_canvas, "width", label="logical_canvas"),
            _number_member(source, "reference_width", label="source"),
        )
        _assert_float_equal(
            _number_member(logical_canvas, "height", label="logical_canvas"),
            _number_member(source, "reference_height", label="source"),
        )

    def test_payload_distinguishes_real_r47_lcd_from_android_lcd(self) -> None:
        """Keep the physical-R47 LCD separate from the Android LCD contract."""
        lcd_contract = _mapping_member(self.payload, "lcd_contract", label="payload")
        real_calculator = _mapping_member(
            lcd_contract,
            "real_calculator",
            label="lcd_contract",
        )
        android_app = _mapping_member(lcd_contract, "android_app", label="lcd_contract")
        logical_canvas = _mapping_member(
            self.payload,
            "logical_canvas",
            label="payload",
        )
        real_measured_lcd = _mapping_member(
            logical_canvas,
            "real_measured_lcd",
            label="logical_canvas",
        )
        lcd_window = _mapping_member(
            logical_canvas,
            "lcd_window",
            label="logical_canvas",
        )

        _assert_equal(
            _require_string(
                real_calculator["coordinate_space"],
                label="real_calculator.coordinate_space",
            ),
            "reference image / logical canvas",
        )
        _assert_equal(
            _require_string(
                android_app["coordinate_space"],
                label="android_app.coordinate_space",
            ),
            "logical canvas",
        )
        _assert_equal(
            _mapping_member(real_calculator, "rect", label="real_calculator"),
            real_measured_lcd,
        )
        _assert_equal(
            _mapping_member(android_app, "rect", label="android_app"),
            lcd_window,
        )
        _assert_float_equal(
            _number_member(
                android_app,
                "settings_strip_tap_height",
                label="android_app",
            ),
            self.kotlin["TOP_BEZEL_SETTINGS_TAP_HEIGHT"],
        )

    def test_shell_constants_match_python_contract(self) -> None:
        """Lock the Android shell, trim, and LCD constants to the derived payload."""
        logical_canvas = _mapping_member(
            self.payload,
            "logical_canvas",
            label="payload",
        )
        scaled_mode_fit_trim = _mapping_member(
            logical_canvas,
            "scaled_mode_fit_trim",
            label="logical_canvas",
        )
        lcd_window = _mapping_member(
            logical_canvas,
            "lcd_window",
            label="logical_canvas",
        )
        _assert_float_equal(
            self.kotlin["NATIVE_SHELL_DRAW_CORNER_RADIUS"],
            _number_member(
                logical_canvas,
                "native_shell_draw_corner_radius",
                label="logical_canvas",
            ),
        )
        _assert_float_equal(
            self.kotlin["SCALED_MODE_FIT_TRIM_LEFT"],
            _number_member(
                scaled_mode_fit_trim,
                "left",
                label="scaled_mode_fit_trim",
            ),
        )
        _assert_float_equal(
            self.kotlin["SCALED_MODE_FIT_TRIM_TOP"],
            _number_member(
                scaled_mode_fit_trim,
                "top",
                label="scaled_mode_fit_trim",
            ),
        )
        _assert_float_equal(
            self.kotlin["SCALED_MODE_FIT_TRIM_RIGHT"],
            _number_member(
                scaled_mode_fit_trim,
                "right",
                label="scaled_mode_fit_trim",
            ),
        )
        _assert_float_equal(
            self.kotlin["SCALED_MODE_FIT_TRIM_BOTTOM"],
            _number_member(
                scaled_mode_fit_trim,
                "bottom",
                label="scaled_mode_fit_trim",
            ),
        )
        _assert_float_equal(
            self.kotlin["TOP_BEZEL_SETTINGS_TAP_HEIGHT"],
            _number_member(
                logical_canvas,
                "top_bezel_settings_tap_height",
                label="logical_canvas",
            ),
        )
        _assert_float_equal(
            self.kotlin["LCD_WINDOW_LEFT"],
            _number_member(lcd_window, "left", label="lcd_window"),
        )
        _assert_float_equal(
            self.kotlin["LCD_WINDOW_TOP"],
            _number_member(lcd_window, "top", label="lcd_window"),
        )
        _assert_float_equal(
            self.kotlin["LCD_WINDOW_WIDTH"],
            _number_member(lcd_window, "width", label="lcd_window"),
        )
        _assert_float_equal(
            self.kotlin["LCD_WINDOW_HEIGHT"],
            _number_member(lcd_window, "height", label="lcd_window"),
        )

    def test_keypad_constants_match_python_contract(self) -> None:
        """Verify that the keypad placement constants still match the shell payload."""
        logical_canvas = _mapping_member(
            self.payload,
            "logical_canvas",
            label="payload",
        )
        keypad = _mapping_member(logical_canvas, "keypad", label="logical_canvas")
        for kotlin_name, payload_name in (
            ("STANDARD_LEFT", "standard_left"),
            ("STANDARD_PITCH", "standard_pitch"),
            ("STANDARD_KEY_WIDTH", "standard_key_width"),
            ("MATRIX_FIRST_VISIBLE_LEFT", "matrix_first_visible_left"),
            ("MATRIX_PITCH", "matrix_pitch"),
            ("MATRIX_KEY_WIDTH", "matrix_key_width"),
            ("ENTER_WIDTH", "enter_width"),
            ("ROW_HEIGHT", "row_height"),
            ("ROW_STEP", "row_step"),
            ("SOFTKEY_ROW_TOP", "softkey_row_top"),
            ("FIRST_SMALL_ROW_TOP", "first_small_row_top"),
            ("ENTER_ROW_TOP", "enter_row_top"),
            ("FIRST_LARGE_ROW_TOP", "first_large_row_top"),
            ("NON_SOFTKEY_VIEW_HEIGHT", "non_softkey_view_height"),
        ):
            _assert_float_equal(
                self.kotlin[kotlin_name],
                _number_member(keypad, payload_name, label="keypad"),
            )

    def test_android_lcd_bias_matches_contract(self) -> None:
        """Keep the Android LCD bias aligned with the measured-LCD replay contract."""
        checks = _mapping_member(self.payload, "checks", label="payload")
        _assert_float_equal(
            _number_member(
                checks,
                "lcd_window_center_x_delta_vs_shell_center",
                label="checks",
            ),
            0.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "lcd_window_center_y_delta_vs_shell_center",
                label="checks",
            ),
            -1021.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "lcd_window_vs_real_lcd_center_x_delta",
                label="checks",
            ),
            0.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "lcd_window_vs_real_lcd_center_y_delta",
                label="checks",
            ),
            12.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "lcd_window_vs_real_lcd_width_delta",
                label="checks",
            ),
            230.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "lcd_window_vs_real_lcd_height_delta",
                label="checks",
            ),
            54.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "lcd_window_vs_real_lcd_aspect_ratio_delta_pct",
                label="checks",
            ),
            9.269993,
        )


if __name__ == "__main__":
    unittest.main()
