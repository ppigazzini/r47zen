"""Load the canonical R47 contract JSON and hold the shape each input must have.

The two hand-maintained inputs, the measured physical geometry and the Android
UI contract, are checked against a valgebra schema as they load, so a missing
key, a wrong type, or an unknown field fails once, with its path, instead of
wherever a deriver first touches it. The derived goldens load unchecked on
purpose: each is compared whole-document against a fresh re-derivation by its
own contract test, which is a stronger claim than any shape.
"""

from __future__ import annotations

import json
from typing import TYPE_CHECKING, Annotated, Final, Literal, NotRequired

import annotated_types as at
from typing_extensions import TypedDict
from valgebra import Regex, ValidationError, Validator

from r47_contracts._repo_paths import (
    R47_ANDROID_UI_CONTRACT_PATH,
    R47_KEY_FONT_POLICY_CONTRACT_PATH,
    R47_KEYBOARD_LAYOUT_CONTRACT_PATH,
    R47_PHYSICAL_GEOMETRY_DATA_PATH,
)

if TYPE_CHECKING:
    from collections.abc import Mapping
    from pathlib import Path


class ContractDataError(ValueError):
    """Raised when a canonical contract JSON does not have its declared shape."""


def _is_not_bool(value: object) -> bool:
    return not isinstance(value, bool)


# JSON `true` is an `int` to Python; a measurement is never a flag.
type Integer = Annotated[int, at.Predicate(_is_not_bool)]
type Positive = Annotated[int, at.Gt(0), at.Predicate(_is_not_bool)]
type Number = Annotated[int | float, at.Predicate(_is_not_bool)]
type Name = Annotated[str, at.MinLen(1)]
type Argb = Annotated[str, Regex(r"#[0-9A-Fa-f]{8}")]


class GeometryEntry(TypedDict, closed=True):
    """One measured guide of the physical dataset, in reference-image pixels."""

    id: Name
    label: Name
    family: Name
    start: Integer
    start_step: Integer | None
    stop: Integer
    stop_step: Integer | None
    span: Integer
    index: NotRequired[Integer]
    variant: NotRequired[Name]


class GeometryTable(TypedDict, closed=True):
    """One axis of guides; derivers address it by `id`, then by entry `id`."""

    id: Name
    label: Name
    axis: Literal["vertical", "horizontal"]
    span_kind: Literal["height", "width"]
    entries: Annotated[list[GeometryEntry], at.MinLen(1)]


class ReferenceFrame(TypedDict, closed=True):
    """The reference image the guides were measured on; it is the logical canvas."""

    label: Name
    width: Positive
    height: Positive


class MeasuredRect(TypedDict, closed=True):
    """A rectangle measured on the reference image."""

    left: Integer
    top: Integer
    width: Positive
    height: Positive


class PhysicalGeometry(TypedDict, closed=True):
    """The shape of `data/r47_physical_geometry.json`."""

    dataset: Name
    version: Positive
    description: str
    reference_frame: ReferenceFrame
    real_lcd: MeasuredRect
    tables: Annotated[list[GeometryTable], at.MinLen(1)]


PHYSICAL_GEOMETRY: Final = Validator(PhysicalGeometry)

# The UI contract is written as valgebra's own closed dict-literal spelling
# rather than a TypedDict: every reader takes it apart with the `*_member`
# accessors below, so a static record type would buy nothing, and the literal
# reads like the JSON it governs. A dict literal admits no key it does not name.
_RECT: Final = {"left": Number, "top": Number, "width": Number, "height": Number}
_LEGEND_ANCHORS: Final = {"horizontal_anchor": Name, "vertical_anchor": Name}
_TOP_LEGEND: Final = {
    **_LEGEND_ANCHORS,
    "text_size": Number,
    "horizontal_gap": Number,
    "vertical_lift": Number,
}
ANDROID_UI_CONTRACT: Final = Validator(
    {
        "dataset": Name,
        "version": Positive,
        "description": str,
        "coordinate_space": Name,
        "based_on": {"physical_dataset": Name, "physical_version": Positive},
        "logical_canvas": {"width": Number, "height": Number},
        "chrome": {
            "native_shell_draw_corner_radius": Number,
            "scaled_mode_fit_trim": {
                "left": Number,
                "top": Number,
                "right": Number,
                "bottom": Number,
            },
            "settings_strip_tap_height": Number,
            "main_menu_button": _RECT,
            "lcd_windows": {"native": _RECT},
            "lcd_frame_buffer": {"pixel_width": Number, "pixel_height": Number},
            "non_softkey_view_height": Number,
        },
        "overlay_visual_policy": {
            "settings_menu_glyph": {
                "tab_width_to_height_ratio": Number,
                "main_menu": {
                    "tab_height_dp": Number,
                    "gap_dp": Number,
                    "bottom_inset_dp": Number,
                },
                "onboarding_hint": {"tab_height_dp": Number, "gap_dp": Number},
            },
            "settings_discovery_hint": {
                "colors": {
                    "surface_argb": Argb,
                    "on_surface_argb": Argb,
                    "stroke_argb": Argb,
                    "menu_orange_fallback_argb": Argb,
                    "menu_blue_fallback_argb": Argb,
                },
                "card": {
                    "outer_margin_dp": Number,
                    "min_width_dp": Number,
                    "max_width_dp": Number,
                    "width_ratio": Number,
                    "horizontal_padding_dp": Number,
                    "vertical_padding_dp": Number,
                    "corner_radius_dp": Number,
                    "line_spacing_dp": Number,
                    "glyph_text_gap_dp": Number,
                },
                "stroke": {
                    "width_dp": Number,
                    "extra_width_dp": Number,
                    "alpha_base": Number,
                    "alpha_delta": Number,
                },
                "text": {"size_dp": Number},
                "fill": {"alpha_base": Number, "alpha_delta": Number},
                "pulse": {"period_ms": Number},
            },
            "developer_performance_hud": {
                "text": {
                    "size_dp": Number,
                    "min_available_height_dp": Number,
                    "height_ratio": Number,
                    "min_size_dp": Number,
                    "max_size_dp": Number,
                    "min_label_width_dp": Number,
                    "max_label_horizontal_margin_dp": Number,
                    "baseline_bottom_inset_dp": Number,
                    "leading_inset_dp": Number,
                },
                "shadow": {"radius_dp": Number, "argb": Argb},
            },
            "touch_zone_debug": {"stroke_width_dp": Number, "stroke_alpha": Number},
        },
        "key_surface": {
            "main_key": {
                "draw_corner_radius": Number,
                "painted_body_width_bonus": Number,
                "painted_body_height": Number,
                "slot_horizontal_bias": Number,
                "slot_vertical_bias": Number,
            },
            "softkey": {
                "draw_corner_radius": Number,
                "preview_line_side_inset": Number,
                "preview_line_bottom_inset": Number,
                "value_text_size_ratio": Number,
                "value_width_ratio": Number,
                "value_right_inset": Number,
                "value_top_inset": Number,
                "overlay_center_right_inset": Number,
                "overlay_center_bottom_inset": Number,
            },
            "standard_key": {"right_strip_width": Number},
            "matrix_key": {"right_strip_width": Number},
        },
        "label_layout": {
            "primary_legend": {
                **_LEGEND_ANCHORS,
                "horizontal_padding": Number,
                "text_sizes": {"default": Number, "numeric": Number, "shifted": Number},
            },
            "top_f_legend": _TOP_LEGEND,
            "top_g_legend": _TOP_LEGEND,
            "right_side_letter_legend": {
                **_LEGEND_ANCHORS,
                "text_size": Number,
                "x_offset_from_main_key_body_right": Number,
                "y_offset_from_main_key_body_top": Number,
            },
        },
        "top_label_solver": {
            "max_shift_fraction": Number,
            "min_scale": Number,
            "scale_step": Number,
        },
    },
)


def _load[T](schema: Validator[T], path: Path) -> T:
    try:
        return schema.load(path.read_bytes())
    except ValidationError as error:
        message = f"{path}: {error}"
        raise ContractDataError(message) from error


def load_physical_geometry(
    path: Path = R47_PHYSICAL_GEOMETRY_DATA_PATH,
) -> PhysicalGeometry:
    """Load the measured R47 physical geometry, checked against its schema."""
    return _load(PHYSICAL_GEOMETRY, path)


def load_android_ui_contract(
    path: Path = R47_ANDROID_UI_CONTRACT_PATH,
) -> dict[str, object]:
    """Load the Android UI geometry and policy document, checked against its schema."""
    return require_mapping(_load(ANDROID_UI_CONTRACT, path), label="contract document")


def index_geometry_tables(
    geometry: PhysicalGeometry,
) -> dict[str, dict[str, GeometryEntry]]:
    """Index the guide tables by table id, then by entry id."""
    return {
        table["id"]: {entry["id"]: entry for entry in table["entries"]}
        for table in geometry["tables"]
    }


def load_contract_document(path: Path) -> dict[str, object]:
    """Load a contract JSON object without a schema.

    The geometry validator CLI takes an arbitrary dataset path, including
    historical exports whose shape predates the split UI contract, so it reads
    through here and checks the shape itself.
    """
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    return require_mapping(payload, label="contract document")


def load_key_font_policy_contract(
    path: Path = R47_KEY_FONT_POLICY_CONTRACT_PATH,
) -> dict[str, object]:
    """Load the keypad font-policy golden; its test re-derives it whole."""
    return load_contract_document(path)


def load_keyboard_layout_contract(
    path: Path = R47_KEYBOARD_LAYOUT_CONTRACT_PATH,
) -> dict[str, object]:
    """Load the keyboard layout golden; its test re-derives it whole."""
    return load_contract_document(path)


# Narrowing accessors for a document already checked at load. They give a
# reader a typed member without a schema class per reader; a failure here means
# the schema above and the reader disagree about the document, not that the
# file is malformed.


def require_mapping(value: object, *, label: str) -> dict[str, object]:
    """Return a JSON object or raise a contract-data error."""
    if not isinstance(value, dict):
        message = f"Expected {label} to be an object, got {value!r}"
        raise ContractDataError(message)
    return {
        require_string(key, label=f"{label}.key"): nested_value
        for key, nested_value in value.items()
    }


def require_string(value: object, *, label: str) -> str:
    """Return a non-empty string or raise a contract-data error."""
    if not isinstance(value, str) or not value:
        message = f"Expected {label} to be a non-empty string, got {value!r}"
        raise ContractDataError(message)
    return value


def require_number(value: object, *, label: str) -> float:
    """Return a numeric JSON value as float or raise a contract-data error."""
    if isinstance(value, bool) or not isinstance(value, int | float):
        message = f"Expected {label} to be numeric, got {value!r}"
        raise ContractDataError(message)
    return float(value)


def mapping_member(
    mapping: Mapping[str, object],
    key: str,
    *,
    label: str,
) -> dict[str, object]:
    """Return a required mapping member from a loaded contract mapping."""
    return require_mapping(mapping.get(key), label=f"{label}.{key}")


def string_member(mapping: Mapping[str, object], key: str, *, label: str) -> str:
    """Return a required string member from a loaded contract mapping."""
    return require_string(mapping.get(key), label=f"{label}.{key}")


def number_member(mapping: Mapping[str, object], key: str, *, label: str) -> float:
    """Return a required numeric member from a loaded contract mapping."""
    return require_number(mapping.get(key), label=f"{label}.{key}")
