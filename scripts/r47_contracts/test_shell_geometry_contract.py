"""Lock the Kotlin shell geometry constants to the Python shell contract."""

import re
import unittest

from r47_contracts._contract_data import load_android_ui_contract
from r47_contracts._kotlin_consts import (
    parse_kotlin_const_values,
    parse_kotlin_const_values_from_paths,
)
from r47_contracts._repo_paths import KOTLIN_R47ZEN_ROOT
from r47_contracts.derive_shell_geometry import build_shell_geometry_payload

_KOTLIN_GEOMETRY_PATH = KOTLIN_R47ZEN_ROOT / "R47Geometry.kt"
_KOTLIN_SETTINGS_MENU_GLYPH_PATH = KOTLIN_R47ZEN_ROOT / "SettingsMenuGlyph.kt"
_KOTLIN_REPLICA_OVERLAY_PATH = KOTLIN_R47ZEN_ROOT / "ReplicaOverlay.kt"
_ARGB_COLOR_PATTERN = re.compile(r"^#[0-9A-Fa-f]{8}$")
_SIGNED_INT32_THRESHOLD = 0x80000000
_UINT32_RANGE = 0x100000000


def _assert_float_equal(actual: float, expected: float, *, places: int = 6) -> None:
    tolerance = 10 ** (-places)
    if abs(actual - expected) > tolerance:
        message = f"Expected {expected} but saw {actual} within {places} places"
        raise AssertionError(message)


def _assert_equal(actual: object, expected: object) -> None:
    if actual != expected:
        message = f"Expected {expected!r} but saw {actual!r}"
        raise AssertionError(message)


def _assert_true(*, condition: bool, message: str) -> None:
    if not condition:
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


def _argb_color_member(mapping: dict[str, object], key: str, *, label: str) -> int:
    raw_value = _require_string(mapping[key], label=f"{label}.{key}")
    if _ARGB_COLOR_PATTERN.fullmatch(raw_value) is None:
        message = f"Expected {label}.{key} to be #AARRGGBB, got {raw_value!r}"
        raise TypeError(message)

    parsed = int(raw_value[1:], 16)
    if parsed >= _SIGNED_INT32_THRESHOLD:
        parsed -= _UINT32_RANGE
    return parsed


class ShellGeometryContractTest(unittest.TestCase):
    """Verify that Kotlin shell constants still match the Python contract payload."""

    @classmethod
    def setUpClass(cls) -> None:
        """Load the shared Python payload and Kotlin constants once for the module."""
        cls.payload: dict[str, object] = build_shell_geometry_payload()
        cls.android_contract: dict[str, object] = load_android_ui_contract()
        cls.kotlin: dict[str, float] = parse_kotlin_const_values(
            _KOTLIN_GEOMETRY_PATH,
        )
        cls.visual_kotlin: dict[str, float] = parse_kotlin_const_values_from_paths(
            [
                _KOTLIN_SETTINGS_MENU_GLYPH_PATH,
                _KOTLIN_REPLICA_OVERLAY_PATH,
            ],
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
        lcd_windows = _mapping_member(
            logical_canvas,
            "lcd_windows",
            label="logical_canvas",
        )
        real_measured_lcd = _mapping_member(
            logical_canvas,
            "real_measured_lcd",
            label="logical_canvas",
        )
        mode_rects = _mapping_member(
            android_app,
            "mode_rects",
            label="android_app",
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
        _assert_equal(sorted(mode_rects), ["native"])
        _assert_equal(sorted(lcd_windows), ["native"])
        _assert_equal(
            _mapping_member(
                _mapping_member(mode_rects, "native", label="mode_rects"),
                "rect",
                label="android_app.mode_rects.native",
            ),
            _mapping_member(lcd_windows, "native", label="logical_canvas.lcd_windows"),
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
        """Lock the borderless Android shell, trim, and LCD constants to the payload."""
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
        lcd_windows = _mapping_member(
            logical_canvas,
            "lcd_windows",
            label="logical_canvas",
        )
        native_lcd_window = _mapping_member(
            lcd_windows,
            "native",
            label="logical_canvas.lcd_windows",
        )
        main_menu_button = _mapping_member(
            logical_canvas,
            "main_menu_button",
            label="logical_canvas",
        )
        _assert_float_equal(
            _number_member(
                logical_canvas,
                "native_shell_draw_corner_radius",
                label="logical_canvas",
            ),
            0.0,
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
            self.kotlin["NATIVE_LCD_WINDOW_LEFT"],
            _number_member(native_lcd_window, "left", label="native_lcd_window"),
        )
        _assert_float_equal(
            self.kotlin["NATIVE_LCD_WINDOW_TOP"],
            _number_member(native_lcd_window, "top", label="native_lcd_window"),
        )
        _assert_float_equal(
            self.kotlin["NATIVE_LCD_WINDOW_WIDTH"],
            _number_member(native_lcd_window, "width", label="native_lcd_window"),
        )
        _assert_float_equal(
            self.kotlin["NATIVE_LCD_WINDOW_HEIGHT"],
            _number_member(native_lcd_window, "height", label="native_lcd_window"),
        )
        _assert_float_equal(
            self.kotlin["MAIN_MENU_BUTTON_LEFT"],
            _number_member(main_menu_button, "left", label="main_menu_button"),
        )
        _assert_float_equal(
            self.kotlin["MAIN_MENU_BUTTON_TOP"],
            _number_member(main_menu_button, "top", label="main_menu_button"),
        )
        _assert_float_equal(
            self.kotlin["MAIN_MENU_BUTTON_WIDTH"],
            _number_member(main_menu_button, "width", label="main_menu_button"),
        )
        _assert_float_equal(
            self.kotlin["MAIN_MENU_BUTTON_HEIGHT"],
            _number_member(main_menu_button, "height", label="main_menu_button"),
        )
        for removed_name in (
            "IMAGE_LCD_WINDOW_LEFT",
            "IMAGE_LCD_WINDOW_TOP",
            "IMAGE_LCD_WINDOW_WIDTH",
            "IMAGE_LCD_WINDOW_HEIGHT",
        ):
            _assert_true(
                condition=removed_name not in self.kotlin,
                message=f"Did not expect {removed_name} in R47Geometry.kt",
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
            ("SOFTKEY_TOUCH_ROW_TOP", "softkey_touch_row_top"),
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

    def test_native_lcd_restores_frame_buffer_aspect_ratio(self) -> None:
        """Keep the native LCD window centered on exact integer 400x240 bounds."""
        logical_canvas = _mapping_member(
            self.payload,
            "logical_canvas",
            label="payload",
        )
        checks = _mapping_member(self.payload, "checks", label="payload")
        lcd_windows = _mapping_member(
            logical_canvas,
            "lcd_windows",
            label="logical_canvas",
        )
        native_lcd_window = _mapping_member(
            lcd_windows,
            "native",
            label="logical_canvas.lcd_windows",
        )
        _assert_float_equal(
            _number_member(
                checks,
                "native_lcd_window_vs_frame_buffer_aspect_ratio_delta_pct",
                label="checks",
            ),
            0.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "native_lcd_window_bottom_delta_vs_softkey_touch_row_top",
                label="checks",
            ),
            0.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "native_lcd_window_center_x_delta_vs_shell_center",
                label="checks",
            ),
            0.0,
        )
        native_height = _number_member(
            native_lcd_window,
            "height",
            label="native_lcd_window",
        )
        native_width = _number_member(
            native_lcd_window,
            "width",
            label="native_lcd_window",
        )
        _assert_float_equal(native_width, 1650.0)
        _assert_float_equal(native_height, 990.0)
        _assert_float_equal(
            _number_member(native_lcd_window, "left", label="native_lcd_window"),
            85.0,
        )
        _assert_float_equal(
            _number_member(native_lcd_window, "top", label="native_lcd_window"),
            242.0,
        )
        native_bottom_edge = (
            _number_member(
                native_lcd_window,
                "top",
                label="native_lcd_window",
            )
            + native_height
        )
        _assert_float_equal(
            native_bottom_edge,
            self.kotlin["SOFTKEY_TOUCH_ROW_TOP"],
        )
        _assert_float_equal(
            native_bottom_edge - 1.0,
            self.kotlin["SOFTKEY_TOUCH_ROW_TOP"] - 1.0,
        )
        _assert_float_equal(
            _number_member(native_lcd_window, "width", label="native_lcd_window"),
            round(native_width),
        )
        _assert_float_equal(
            _number_member(native_lcd_window, "height", label="native_lcd_window"),
            round(native_height),
        )

    def test_main_menu_button_stays_top_right_aligned_to_the_lcd(self) -> None:
        """Keep the visible shell menu button bounded to the top-right LCD corner."""
        logical_canvas = _mapping_member(
            self.payload,
            "logical_canvas",
            label="payload",
        )
        checks = _mapping_member(self.payload, "checks", label="payload")
        lcd_windows = _mapping_member(
            logical_canvas,
            "lcd_windows",
            label="logical_canvas",
        )
        native_lcd_window = _mapping_member(
            lcd_windows,
            "native",
            label="logical_canvas.lcd_windows",
        )
        main_menu_button = _mapping_member(
            logical_canvas,
            "main_menu_button",
            label="logical_canvas",
        )

        _assert_float_equal(
            _number_member(main_menu_button, "top", label="main_menu_button"),
            0.0,
        )
        _assert_float_equal(
            _number_member(main_menu_button, "height", label="main_menu_button"),
            _number_member(
                logical_canvas,
                "top_bezel_settings_tap_height",
                label="logical_canvas",
            ),
        )
        _assert_float_equal(
            _number_member(
                checks,
                "main_menu_button_right_delta_vs_native_lcd_right",
                label="checks",
            ),
            0.0,
        )
        _assert_float_equal(
            _number_member(
                checks,
                "main_menu_button_bottom_delta_vs_native_lcd_top",
                label="checks",
            ),
            0.0,
        )
        _assert_true(
            condition=_number_member(main_menu_button, "left", label="main_menu_button")
            >= _number_member(native_lcd_window, "left", label="native_lcd_window"),
            message="Expected main_menu_button.left to stay inside the LCD span",
        )

    def test_settings_menu_glyph_policy_matches_contract(self) -> None:
        """Lock the top-right glyph metrics used by shell and onboarding."""
        overlay_visual_policy = _mapping_member(
            self.android_contract,
            "overlay_visual_policy",
            label="android_contract",
        )
        settings_menu_glyph = _mapping_member(
            overlay_visual_policy,
            "settings_menu_glyph",
            label="overlay_visual_policy",
        )
        main_menu = _mapping_member(
            settings_menu_glyph,
            "main_menu",
            label="settings_menu_glyph",
        )
        onboarding_hint = _mapping_member(
            settings_menu_glyph,
            "onboarding_hint",
            label="settings_menu_glyph",
        )

        _assert_float_equal(
            self.visual_kotlin["TAB_WIDTH_TO_HEIGHT_RATIO"],
            _number_member(
                settings_menu_glyph,
                "tab_width_to_height_ratio",
                label="settings_menu_glyph",
            ),
        )
        _assert_float_equal(
            self.visual_kotlin["MAIN_MENU_TAB_HEIGHT_DP"],
            _number_member(main_menu, "tab_height_dp", label="main_menu"),
        )
        _assert_float_equal(
            self.visual_kotlin["MAIN_MENU_GAP_DP"],
            _number_member(main_menu, "gap_dp", label="main_menu"),
        )
        _assert_float_equal(
            self.visual_kotlin["MAIN_MENU_BOTTOM_INSET_DP"],
            _number_member(main_menu, "bottom_inset_dp", label="main_menu"),
        )
        _assert_float_equal(
            self.visual_kotlin["ONBOARDING_TAB_HEIGHT_DP"],
            _number_member(onboarding_hint, "tab_height_dp", label="onboarding_hint"),
        )
        _assert_float_equal(
            self.visual_kotlin["ONBOARDING_GAP_DP"],
            _number_member(onboarding_hint, "gap_dp", label="onboarding_hint"),
        )

    def test_settings_discovery_hint_policy_matches_contract(self) -> None:
        """Keep the discovery card sizing, palette, and pulse policy aligned."""
        overlay_visual_policy = _mapping_member(
            self.android_contract,
            "overlay_visual_policy",
            label="android_contract",
        )
        settings_discovery_hint = _mapping_member(
            overlay_visual_policy,
            "settings_discovery_hint",
            label="overlay_visual_policy",
        )
        colors = _mapping_member(
            settings_discovery_hint,
            "colors",
            label="settings_discovery_hint",
        )
        card = _mapping_member(
            settings_discovery_hint,
            "card",
            label="settings_discovery_hint",
        )
        stroke = _mapping_member(
            settings_discovery_hint,
            "stroke",
            label="settings_discovery_hint",
        )
        text = _mapping_member(
            settings_discovery_hint,
            "text",
            label="settings_discovery_hint",
        )
        fill = _mapping_member(
            settings_discovery_hint,
            "fill",
            label="settings_discovery_hint",
        )
        pulse = _mapping_member(
            settings_discovery_hint,
            "pulse",
            label="settings_discovery_hint",
        )

        for kotlin_name, color_key in (
            ("SURFACE_COLOR_ARGB", "surface_argb"),
            ("ON_SURFACE_COLOR_ARGB", "on_surface_argb"),
            ("STROKE_COLOR_ARGB", "stroke_argb"),
            ("MENU_ORANGE_FALLBACK_COLOR_ARGB", "menu_orange_fallback_argb"),
            ("MENU_BLUE_FALLBACK_COLOR_ARGB", "menu_blue_fallback_argb"),
        ):
            _assert_equal(
                int(self.visual_kotlin[kotlin_name]),
                _argb_color_member(
                    colors,
                    color_key,
                    label="settings_discovery_hint.colors",
                ),
            )

        for kotlin_name, payload_key in (
            ("CARD_OUTER_MARGIN_DP", "outer_margin_dp"),
            ("CARD_MIN_WIDTH_DP", "min_width_dp"),
            ("CARD_MAX_WIDTH_DP", "max_width_dp"),
            ("CARD_WIDTH_RATIO", "width_ratio"),
            ("CARD_HORIZONTAL_PADDING_DP", "horizontal_padding_dp"),
            ("CARD_VERTICAL_PADDING_DP", "vertical_padding_dp"),
            ("CARD_CORNER_RADIUS_DP", "corner_radius_dp"),
            ("CARD_LINE_SPACING_DP", "line_spacing_dp"),
            ("CARD_GLYPH_TEXT_GAP_DP", "glyph_text_gap_dp"),
        ):
            _assert_float_equal(
                self.visual_kotlin[kotlin_name],
                _number_member(card, payload_key, label="settings_discovery_hint.card"),
            )

        for kotlin_name, payload_key in (
            ("CARD_STROKE_WIDTH_DP", "width_dp"),
            ("CARD_STROKE_EXTRA_WIDTH_DP", "extra_width_dp"),
            ("CARD_STROKE_ALPHA_BASE", "alpha_base"),
            ("CARD_STROKE_ALPHA_DELTA", "alpha_delta"),
        ):
            _assert_float_equal(
                self.visual_kotlin[kotlin_name],
                _number_member(
                    stroke,
                    payload_key,
                    label="settings_discovery_hint.stroke",
                ),
            )

        for kotlin_name, payload_key, source, source_label in (
            ("INFO_TEXT_SIZE_DP", "size_dp", text, "settings_discovery_hint.text"),
            ("FILL_ALPHA_BASE", "alpha_base", fill, "settings_discovery_hint.fill"),
            ("FILL_ALPHA_DELTA", "alpha_delta", fill, "settings_discovery_hint.fill"),
            ("PULSE_PERIOD_MS", "period_ms", pulse, "settings_discovery_hint.pulse"),
        ):
            _assert_float_equal(
                self.visual_kotlin[kotlin_name],
                _number_member(source, payload_key, label=source_label),
            )

    def test_developer_performance_hud_policy_matches_contract(self) -> None:
        """Lock the HUD sizing and shadow values used by the overlay paint."""
        overlay_visual_policy = _mapping_member(
            self.android_contract,
            "overlay_visual_policy",
            label="android_contract",
        )
        developer_performance_hud = _mapping_member(
            overlay_visual_policy,
            "developer_performance_hud",
            label="overlay_visual_policy",
        )
        text = _mapping_member(
            developer_performance_hud,
            "text",
            label="developer_performance_hud",
        )
        shadow = _mapping_member(
            developer_performance_hud,
            "shadow",
            label="developer_performance_hud",
        )

        for kotlin_name, payload_key in (
            ("TEXT_SIZE_DP", "size_dp"),
            ("MIN_AVAILABLE_HEIGHT_DP", "min_available_height_dp"),
            ("TEXT_HEIGHT_RATIO", "height_ratio"),
            ("MIN_TEXT_SIZE_DP", "min_size_dp"),
            ("MAX_TEXT_SIZE_DP", "max_size_dp"),
            ("MIN_LABEL_WIDTH_DP", "min_label_width_dp"),
            ("MAX_LABEL_HORIZONTAL_MARGIN_DP", "max_label_horizontal_margin_dp"),
            ("BASELINE_BOTTOM_INSET_DP", "baseline_bottom_inset_dp"),
            ("LEADING_INSET_DP", "leading_inset_dp"),
        ):
            _assert_float_equal(
                self.visual_kotlin[kotlin_name],
                _number_member(
                    text,
                    payload_key,
                    label="developer_performance_hud.text",
                ),
            )

        _assert_float_equal(
            self.visual_kotlin["SHADOW_RADIUS_DP"],
            _number_member(
                shadow,
                "radius_dp",
                label="developer_performance_hud.shadow",
            ),
        )
        _assert_equal(
            int(self.visual_kotlin["SHADOW_COLOR_ARGB"]),
            _argb_color_member(
                shadow,
                "argb",
                label="developer_performance_hud.shadow",
            ),
        )

    def test_touch_zone_debug_visual_policy_matches_android_contract(self) -> None:
        """Keep the debug touch-zone overlay width and alpha explicitly contracted."""
        overlay_visual_policy = _mapping_member(
            self.android_contract,
            "overlay_visual_policy",
            label="android_contract",
        )
        touch_zone_debug = _mapping_member(
            overlay_visual_policy,
            "touch_zone_debug",
            label="overlay_visual_policy",
        )

        _assert_float_equal(
            self.visual_kotlin["ZONE_STROKE_WIDTH_DP"],
            _number_member(
                touch_zone_debug,
                "stroke_width_dp",
                label="touch_zone_debug",
            ),
        )
        _assert_float_equal(
            self.visual_kotlin["STROKE_ALPHA"],
            _number_member(touch_zone_debug, "stroke_alpha", label="touch_zone_debug"),
        )


if __name__ == "__main__":
    unittest.main()
