"""Lock Kotlin key-label constants to the Python key-label geometry payload."""

import unittest

from r47_contracts._kotlin_consts import parse_kotlin_const_values_from_paths
from r47_contracts._repo_paths import KOTLIN_R47ZEN_ROOT
from r47_contracts.derive_key_label_geometry import build_key_label_geometry_payload

_KOTLIN_KEY_VIEW_PATH = KOTLIN_R47ZEN_ROOT / "CalculatorKeyView.kt"
_KOTLIN_KEY_RENDER_SPEC_PATH = KOTLIN_R47ZEN_ROOT / "KeyRenderSpec.kt"
_KOTLIN_KEY_RENDER_PAINTER_PATH = KOTLIN_R47ZEN_ROOT / "KeyRenderPainter.kt"
_KOTLIN_SOFTKEY_PAINTER_PATH = KOTLIN_R47ZEN_ROOT / "CalculatorSoftkeyPainter.kt"
_KOTLIN_GEOMETRY_PATH = KOTLIN_R47ZEN_ROOT / "R47Geometry.kt"
_KOTLIN_KEYPAD_POLICY_PATH = KOTLIN_R47ZEN_ROOT / "R47KeypadPolicy.kt"


def _assert_float_equal(actual: float, expected: float, *, places: int = 6) -> None:
    tolerance = 10 ** (-places)
    if abs(actual - expected) > tolerance:
        message = f"Expected {expected} but saw {actual} within {places} places"
        raise AssertionError(message)


def _assert_equal(actual: object, expected: object, *, name: str) -> None:
    if actual != expected:
        message = f"Expected {name} to be {expected!r}, saw {actual!r}"
        raise AssertionError(message)


def _assert_contains(text: str, snippet: str, *, name: str) -> None:
    if snippet not in text:
        message = f"Expected {name} snippet to appear in CalculatorKeyView.kt"
        raise AssertionError(message)


def _require_mapping(value: object, *, name: str) -> dict[str, object]:
    if not isinstance(value, dict):
        message = f"Expected {name} to be a dict, got {type(value).__name__}"
        raise TypeError(message)
    return {
        key if isinstance(key, str) else str(key): nested_value
        for key, nested_value in value.items()
        if isinstance(key, str)
    }


def _require_number(value: object, *, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        message = f"Expected {name} to be numeric, got {type(value).__name__}"
        raise TypeError(message)
    return float(value)


class KeyLabelGeometryContractTest(unittest.TestCase):
    """Verify that Kotlin label and surface constants match the Python payload."""

    @classmethod
    def setUpClass(cls) -> None:
        """Load the shared payload and Kotlin constants once for the module."""
        cls.payload: dict[str, object] = build_key_label_geometry_payload()
        cls.key_view_source = _KOTLIN_KEY_VIEW_PATH.read_text(encoding="utf-8")
        cls.key_render_spec_source = _KOTLIN_KEY_RENDER_SPEC_PATH.read_text(
            encoding="utf-8",
        )
        cls.key_render_painter_source = _KOTLIN_KEY_RENDER_PAINTER_PATH.read_text(
            encoding="utf-8",
        )
        cls.softkey_painter_source = _KOTLIN_SOFTKEY_PAINTER_PATH.read_text(
            encoding="utf-8",
        )
        cls.key_view: dict[str, float] = parse_kotlin_const_values_from_paths(
            [
                _KOTLIN_GEOMETRY_PATH,
                _KOTLIN_KEYPAD_POLICY_PATH,
                _KOTLIN_KEY_VIEW_PATH,
            ],
        )
        cls.geometry: dict[str, float] = parse_kotlin_const_values_from_paths(
            [_KOTLIN_GEOMETRY_PATH],
        )

    def test_non_softkey_view_height_matches_rebased_contract(self) -> None:
        """Keep the non-softkey view height aligned with the rebased contract."""
        non_softkey_view = _require_mapping(
            self.payload["non_softkey_view"],
            name="non_softkey_view",
        )
        _assert_float_equal(
            self.geometry["NON_SOFTKEY_VIEW_HEIGHT"],
            _require_number(
                non_softkey_view["reference_height"],
                name="non_softkey_view.reference_height",
            ),
        )

    def test_label_constants_match_reference_canvas_rebase(self) -> None:
        """Keep the Kotlin label constants aligned with the rebased Python payload."""
        expected = _require_mapping(
            self.payload["label_constants"],
            name="label_constants",
        )
        for name, value in expected.items():
            _assert_float_equal(
                self.key_view[name],
                _require_number(value, name=f"label_constants.{name}"),
            )

    def test_label_layers_explicitly_cover_first_through_fourth_labels(self) -> None:
        """Keep all four Android label tiers explicit in the Python contract."""
        if self.payload.get("contract_domain") != "android_app":
            message = "Expected contract_domain to be 'android_app'"
            raise AssertionError(message)

        label_layers = _require_mapping(
            self.payload["label_layers"],
            name="label_layers",
        )
        first_label = _require_mapping(
            label_layers["first_label_primary"],
            name="label_layers.first_label_primary",
        )
        primary_text_sizes = _require_mapping(
            first_label["text_sizes"],
            name="label_layers.first_label_primary.text_sizes",
        )
        _assert_float_equal(
            self.key_view["DEFAULT_PRIMARY_LEGEND_TEXT_SIZE"],
            _require_number(
                primary_text_sizes["default"],
                name="label_layers.first_label_primary.text_sizes.default",
            ),
        )
        _assert_float_equal(
            self.key_view["NUMERIC_PRIMARY_LEGEND_TEXT_SIZE"],
            _require_number(
                primary_text_sizes["numeric"],
                name="label_layers.first_label_primary.text_sizes.numeric",
            ),
        )
        _assert_float_equal(
            self.key_view["SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE"],
            _require_number(
                primary_text_sizes["shifted"],
                name="label_layers.first_label_primary.text_sizes.shifted",
            ),
        )
        _assert_float_equal(
            self.key_view["PRIMARY_LEGEND_HORIZONTAL_PADDING"],
            _require_number(
                first_label["horizontal_padding"],
                name="label_layers.first_label_primary.horizontal_padding",
            ),
        )

        for layer_name in ("second_label_f", "third_label_g"):
            layer = _require_mapping(
                label_layers[layer_name],
                name=f"label_layers.{layer_name}",
            )
            _assert_float_equal(
                self.key_view["TOP_F_G_LABEL_TEXT_SIZE"],
                _require_number(
                    layer["text_size"],
                    name=f"label_layers.{layer_name}.text_size",
                ),
            )
            _assert_float_equal(
                self.key_view["TOP_F_G_LABEL_HORIZONTAL_GAP"],
                _require_number(
                    layer["shared_group_horizontal_gap"],
                    name=f"label_layers.{layer_name}.shared_group_horizontal_gap",
                ),
            )
            _assert_float_equal(
                self.key_view["TOP_F_G_LABEL_VERTICAL_LIFT"],
                _require_number(
                    layer["vertical_lift"],
                    name=f"label_layers.{layer_name}.vertical_lift",
                ),
            )

        fourth_label = _require_mapping(
            label_layers["fourth_label_right_side"],
            name="label_layers.fourth_label_right_side",
        )
        _assert_float_equal(
            self.key_view["FOURTH_LABEL_TEXT_SIZE"],
            _require_number(
                fourth_label["text_size"],
                name="label_layers.fourth_label_right_side.text_size",
            ),
        )
        _assert_float_equal(
            self.key_view["STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION"],
            _require_number(
                fourth_label["standard_key_strip_width_fraction"],
                name=(
                    "label_layers.fourth_label_right_side."
                    "standard_key_strip_width_fraction"
                ),
            ),
        )
        _assert_float_equal(
            self.key_view["MATRIX_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION"],
            _require_number(
                fourth_label["matrix_key_strip_width_fraction"],
                name=(
                    "label_layers.fourth_label_right_side."
                    "matrix_key_strip_width_fraction"
                ),
            ),
        )

    def test_surface_constants_match_reference_canvas_rebase(self) -> None:
        """Keep the Kotlin key-surface constants aligned with the Python payload."""
        expected = _require_mapping(
            self.payload["surface_constants"],
            name="surface_constants",
        )
        for name, value in expected.items():
            _assert_float_equal(
                self.key_view[name],
                _require_number(value, name=f"surface_constants.{name}"),
            )

    def test_ratio_constants_match_derived_contract(self) -> None:
        """Keep the Kotlin ratio constants aligned with the derived payload."""
        expected = _require_mapping(
            self.payload["ratio_constants"],
            name="ratio_constants",
        )
        for name, value in expected.items():
            _assert_float_equal(
                self.key_view[name],
                _require_number(value, name=f"ratio_constants.{name}"),
            )

    def test_label_layer_anchors_are_explicit(self) -> None:
        """Keep the JSON-backed label-layer anchors explicit and stable."""
        label_layers = _require_mapping(
            self.payload["label_layers"],
            name="label_layers",
        )
        first_label = _require_mapping(
            label_layers["first_label_primary"],
            name="label_layers.first_label_primary",
        )
        second_label = _require_mapping(
            label_layers["second_label_f"],
            name="label_layers.second_label_f",
        )
        third_label = _require_mapping(
            label_layers["third_label_g"],
            name="label_layers.third_label_g",
        )
        fourth_label = _require_mapping(
            label_layers["fourth_label_right_side"],
            name="label_layers.fourth_label_right_side",
        )

        _assert_equal(
            first_label["horizontal_anchor"],
            "main_key_body_center",
            name="label_layers.first_label_primary.horizontal_anchor",
        )
        _assert_equal(
            first_label["vertical_anchor"],
            "main_key_body_center",
            name="label_layers.first_label_primary.vertical_anchor",
        )
        _assert_equal(
            second_label["horizontal_anchor"],
            "shared_top_label_group",
            name="label_layers.second_label_f.horizontal_anchor",
        )
        _assert_equal(
            third_label["horizontal_anchor"],
            "shared_top_label_group",
            name="label_layers.third_label_g.horizontal_anchor",
        )
        _assert_equal(
            fourth_label["horizontal_anchor"],
            "main_key_body_right",
            name="label_layers.fourth_label_right_side.horizontal_anchor",
        )
        _assert_equal(
            fourth_label["vertical_anchor"],
            "main_key_body_top",
            name="label_layers.fourth_label_right_side.vertical_anchor",
        )

    def test_primary_and_top_label_position_formulas_match_contract(self) -> None:
        """Keep the spec-first main-key formulas aligned with the contract."""
        render_spec_shapes = _require_mapping(
            self.payload["render_spec_shapes"],
            name="render_spec_shapes",
        )
        main_key = _require_mapping(
            render_spec_shapes["main_key"],
            name="render_spec_shapes.main_key",
        )
        primary_anchor = _require_mapping(
            main_key["primary_anchor"],
            name="render_spec_shapes.main_key.primary_anchor",
        )
        top_label_group = _require_mapping(
            main_key["top_label_group"],
            name="render_spec_shapes.main_key.top_label_group",
        )

        _assert_equal(
            primary_anchor["horizontal_anchor"],
            "main_key_body_center",
            name="render_spec_shapes.main_key.primary_anchor.horizontal_anchor",
        )
        _assert_equal(
            primary_anchor["vertical_anchor"],
            "main_key_body_center",
            name="render_spec_shapes.main_key.primary_anchor.vertical_anchor",
        )
        _assert_contains(
            self.key_view_source,
            "private fun buildMainKeyRenderSpec(): KeyRenderSpec?",
            name="main-key render-spec builder",
        )
        _assert_contains(
            self.key_view_source,
            "x = bodyBounds.centerX",
            name="primary-label spec X anchor formula",
        )
        _assert_contains(
            self.key_view_source,
            "anchorY = bodyBounds.centerY",
            name="primary-label spec Y anchor formula",
        )

        group_left_formula = (
            "val groupLeft = bodyBounds.centerX - groupWidth / 2f + "
            "topLabelPlacement.centerShift"
        )
        _assert_contains(
            self.key_view_source,
            group_left_formula,
            name="top-label group-left formula",
        )
        _assert_equal(
            top_label_group["group_formula"],
            "body_center_minus_half_group_width_plus_center_shift",
            name="render_spec_shapes.main_key.top_label_group.group_formula",
        )

        top_label_translation = "val topLabelTranslationY = -topFgLabelVerticalLift"
        _assert_contains(
            self.key_view_source,
            top_label_translation,
            name="top-label baseline formula",
        )
        _assert_equal(
            top_label_group["baseline_formula"],
            "negative_vertical_lift_plus_reference_bottom_offset",
            name="render_spec_shapes.main_key.top_label_group.baseline_formula",
        )
        _assert_contains(
            self.key_view_source,
            "TopLabelGroupSpec(",
            name="top-label render-spec type",
        )
        _assert_contains(
            self.key_view_source,
            "R47LabelLayoutPolicy.PRIMARY_LEGEND_HORIZONTAL_PADDING",
            name="primary-label horizontal padding formula",
        )

    def test_main_key_body_layout_matches_contract(self) -> None:
        """Keep the left-anchored main-key button layout explicit in the contract."""
        render_spec_shapes = _require_mapping(
            self.payload["render_spec_shapes"],
            name="render_spec_shapes",
        )
        main_key = _require_mapping(
            render_spec_shapes["main_key"],
            name="render_spec_shapes.main_key",
        )
        body_layout = _require_mapping(
            main_key["body_layout"],
            name="render_spec_shapes.main_key.body_layout",
        )

        _assert_equal(
            body_layout["slot_start_anchor"],
            "parent_start",
            name="render_spec_shapes.main_key.body_layout.slot_start_anchor",
        )
        _assert_equal(
            body_layout["slot_end_anchor"],
            "parent_end",
            name="render_spec_shapes.main_key.body_layout.slot_end_anchor",
        )
        _assert_equal(
            body_layout["width_formula"],
            "one_minus_family_right_strip_fraction",
            name="render_spec_shapes.main_key.body_layout.width_formula",
        )
        _assert_float_equal(
            _require_number(
                body_layout["height_ratio"],
                name="render_spec_shapes.main_key.body_layout.height_ratio",
            ),
            self.key_view["MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW"],
        )
        _assert_float_equal(
            _require_number(
                body_layout["horizontal_bias"],
                name="render_spec_shapes.main_key.body_layout.horizontal_bias",
            ),
            0.0,
        )
        _assert_float_equal(
            _require_number(
                body_layout["vertical_bias"],
                name="render_spec_shapes.main_key.body_layout.vertical_bias",
            ),
            0.0,
        )

        _assert_contains(
            self.key_view_source,
            "btnParams.startToStart = LayoutParams.PARENT_ID",
            name="main-key button start anchor",
        )
        _assert_contains(
            self.key_view_source,
            "btnParams.endToEnd = LayoutParams.PARENT_ID",
            name="main-key button end anchor",
        )
        _assert_contains(
            self.key_view_source,
            "btnParams.horizontalBias = 0f",
            name="main-key initial horizontal bias",
        )
        _assert_contains(
            self.key_view_source,
            "btnParams.matchConstraintPercentHeight = "
            "R47KeySurfacePolicy.MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW",
            name="main-key body height ratio",
        )
        _assert_contains(
            self.key_view_source,
            "buttonParams.matchConstraintPercentWidth = "
            "(1f - letterRatio).coerceIn(0f, 1f)",
            name="main-key body width formula",
        )
        _assert_contains(
            self.key_view_source,
            "buttonParams.horizontalBias = 0f",
            name="main-key configured horizontal bias",
        )

    def test_fourth_label_position_formula_match_contract(self) -> None:
        """Keep the fourth-label anchor formulas aligned with the spec contract."""
        render_spec_shapes = _require_mapping(
            self.payload["render_spec_shapes"],
            name="render_spec_shapes",
        )
        main_key = _require_mapping(
            render_spec_shapes["main_key"],
            name="render_spec_shapes.main_key",
        )
        fourth_label = _require_mapping(
            main_key["fourth_label_anchor"],
            name="render_spec_shapes.main_key.fourth_label_anchor",
        )
        _assert_equal(
            fourth_label["horizontal_anchor"],
            "main_key_body_right_plus_offset",
            name="render_spec_shapes.main_key.fourth_label_anchor.horizontal_anchor",
        )
        _assert_equal(
            fourth_label["vertical_anchor"],
            "main_key_body_top_offset",
            name="render_spec_shapes.main_key.fourth_label_anchor.vertical_anchor",
        )
        _assert_contains(
            self.key_view_source,
            "bodyBounds.right +",
            name="fourth-label horizontal anchor formula",
        )
        _assert_contains(
            self.key_view_source,
            "R47LabelLayoutPolicy.FOURTH_LABEL_X_OFFSET_FROM_MAIN_KEY_BODY_RIGHT",
            name="fourth-label X offset formula",
        )
        _assert_contains(
            self.key_view_source,
            "R47LabelLayoutPolicy.FOURTH_LABEL_Y_OFFSET_FROM_MAIN_KEY_BODY_TOP",
            name="fourth-label Y offset formula",
        )

    def test_render_spec_types_are_explicit(self) -> None:
        """Keep the shared render-spec vocabulary explicit in Kotlin."""
        render_spec_shapes = _require_mapping(
            self.payload["render_spec_shapes"],
            name="render_spec_shapes",
        )
        shared_types = _require_mapping(
            render_spec_shapes["shared_types"],
            name="render_spec_shapes.shared_types",
        )
        for class_name in shared_types.values():
            _assert_contains(
                self.key_render_spec_source,
                f"class {class_name}",
                name=f"shared render-spec type {class_name}",
            )

    def test_shared_painter_stage_remains_explicit(self) -> None:
        """Keep common chrome and line rendering behind the shared painter API."""
        _assert_contains(
            self.key_render_painter_source,
            "internal object KeyRenderPainter",
            name="shared key render painter type",
        )
        _assert_contains(
            self.key_render_painter_source,
            "fun drawChrome(",
            name="shared chrome painter entrypoint",
        )
        _assert_contains(
            self.key_render_painter_source,
            "fun drawLine(",
            name="shared line painter entrypoint",
        )
        _assert_contains(
            self.key_view_source,
            "KeyRenderPainter.drawChrome(",
            name="main-key shared chrome painter usage",
        )
        _assert_contains(
            self.softkey_painter_source,
            "KeyRenderPainter.drawChrome(",
            name="softkey shared chrome painter usage",
        )
        _assert_contains(
            self.softkey_painter_source,
            "KeyRenderPainter.drawLine(canvas, preview, softkeyDecorPaint)",
            name="softkey preview shared line painter usage",
        )
        _assert_contains(
            self.softkey_painter_source,
            "KeyRenderPainter.drawLine(canvas, strike, softkeyDecorPaint)",
            name="softkey strike shared line painter usage",
        )

    def test_softkey_render_spec_shapes_match_contract(self) -> None:
        """Keep the softkey spec builder aligned with the JSON-backed shape data."""
        render_spec_shapes = _require_mapping(
            self.payload["render_spec_shapes"],
            name="render_spec_shapes",
        )
        softkey = _require_mapping(
            render_spec_shapes["softkey"],
            name="render_spec_shapes.softkey",
        )
        value_field = _require_mapping(
            softkey["value_field"],
            name="render_spec_shapes.softkey.value_field",
        )
        overlay = _require_mapping(
            softkey["overlay"],
            name="render_spec_shapes.softkey.overlay",
        )
        preview_line = _require_mapping(
            softkey["preview_line"],
            name="render_spec_shapes.softkey.preview_line",
        )

        _assert_contains(
            self.softkey_painter_source,
            "internal fun buildRenderSpec(",
            name="softkey render-spec builder",
        )
        _assert_contains(
            self.softkey_painter_source,
            "SoftkeyGeometrySpec(",
            name="softkey geometry spec",
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_VALUE_WIDTH_RATIO"],
            _require_number(
                value_field["width_ratio"],
                name="render_spec_shapes.softkey.value_field.width_ratio",
            ),
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_VALUE_RIGHT_INSET"],
            _require_number(
                value_field["right_inset"],
                name="render_spec_shapes.softkey.value_field.right_inset",
            ),
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_VALUE_TOP_INSET"],
            _require_number(
                value_field["top_inset"],
                name="render_spec_shapes.softkey.value_field.top_inset",
            ),
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_VALUE_TEXT_SIZE_RATIO"],
            _require_number(
                value_field["text_size_ratio"],
                name="render_spec_shapes.softkey.value_field.text_size_ratio",
            ),
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_OVERLAY_CENTER_RIGHT_INSET"],
            _require_number(
                overlay["center_right_inset"],
                name="render_spec_shapes.softkey.overlay.center_right_inset",
            ),
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_OVERLAY_CENTER_BOTTOM_INSET"],
            _require_number(
                overlay["center_bottom_inset"],
                name="render_spec_shapes.softkey.overlay.center_bottom_inset",
            ),
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_PREVIEW_LINE_SIDE_INSET"],
            _require_number(
                preview_line["side_inset"],
                name="render_spec_shapes.softkey.preview_line.side_inset",
            ),
        )
        _assert_float_equal(
            self.key_view["SOFTKEY_PREVIEW_LINE_BOTTOM_INSET"],
            _require_number(
                preview_line["bottom_inset"],
                name="render_spec_shapes.softkey.preview_line.bottom_inset",
            ),
        )


if __name__ == "__main__":
    unittest.main()
