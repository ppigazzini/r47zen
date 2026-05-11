"""Load the Android key visual-policy contract from JSON plus font metadata."""

from __future__ import annotations

import json
import sys
from typing import TYPE_CHECKING

from fontTools.ttLib import TTFont

from r47_contracts._contract_data import (
    load_android_app_contract,
    mapping_member,
    number_member,
    string_member,
)
from r47_contracts._repo_paths import REPO_ROOT
from r47_contracts.runtime_fonts import resolve_runtime_font_path

if TYPE_CHECKING:
    from fontTools.ttLib.tables._h_e_a_d import table__h_e_a_d

_STANDARD_FONT_PATH = resolve_runtime_font_path("C47__StandardFont.ttf")
_FOURTH_LABEL_REFERENCE_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"


class KeyVisualPolicyError(ValueError):
    """Raised when the font metrics needed for visual-policy derivation are invalid."""

    @classmethod
    def missing_int_attribute(
        cls,
        attribute: str,
        value: object,
    ) -> KeyVisualPolicyError:
        """Build an error for a font table attribute that is not an int."""
        message = f"Font table attribute {attribute!r} must be an int, got {value!r}"
        return cls(message)

    @classmethod
    def missing_unicode_cmap(cls, font_name: str) -> KeyVisualPolicyError:
        """Build an error for a font that lacks a Unicode cmap."""
        message = f"{font_name} does not expose a Unicode cmap"
        return cls(message)

    @classmethod
    def missing_uppercase_advances(cls, font_name: str) -> KeyVisualPolicyError:
        """Build an error for a font that lacks uppercase advance metrics."""
        message = f"{font_name} did not contain uppercase advances"
        return cls(message)


def _required_int_attribute(table: table__h_e_a_d, attribute: str) -> int:
    value = getattr(table, attribute, None)
    if not isinstance(value, int):
        raise KeyVisualPolicyError.missing_int_attribute(attribute, value)
    return value


def _load_font_metrics() -> dict[str, float | str]:
    font = TTFont(_STANDARD_FONT_PATH)
    head = font["head"]
    cmap = font.getBestCmap()
    if cmap is None:
        raise KeyVisualPolicyError.missing_unicode_cmap(_STANDARD_FONT_PATH.name)
    metrics = font["hmtx"].metrics

    uppercase_advances = [
        metric[0]
        for glyph in _FOURTH_LABEL_REFERENCE_GLYPHS
        if (glyph_name := cmap.get(ord(glyph))) is not None
        and (metric := metrics.get(glyph_name)) is not None
    ]
    if not uppercase_advances:
        raise KeyVisualPolicyError.missing_uppercase_advances(
            _STANDARD_FONT_PATH.name,
        )

    units_per_em = _required_int_attribute(head, "unitsPerEm")
    y_max = _required_int_attribute(head, "yMax")
    y_min = _required_int_attribute(head, "yMin")
    average_uppercase_advance = sum(uppercase_advances) / len(uppercase_advances)

    return {
        "font_path": str(_STANDARD_FONT_PATH.relative_to(REPO_ROOT)),
        "units_per_em": float(units_per_em),
        "average_uppercase_advance": float(average_uppercase_advance),
        "font_bbox_height": float(y_max - y_min),
    }


def build_key_visual_policy_payload() -> dict[str, object]:
    """Build the visual-policy payload used by the Kotlin contract tests."""
    font_metrics = _load_font_metrics()
    android_app_contract = load_android_app_contract()
    labels_contract = mapping_member(
        android_app_contract,
        "labels",
        label="android_app_contract",
    )
    fourth_label_contract = mapping_member(
        labels_contract,
        "fourth_label_right_side",
        label="android_app_contract.labels",
    )

    return {
        "contract_domain": "android_app",
        "font_metrics": font_metrics,
        "fourth_label_contract": {
            "horizontal_anchor": string_member(
                fourth_label_contract,
                "horizontal_anchor",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
            "vertical_anchor": string_member(
                fourth_label_contract,
                "vertical_anchor",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
            "x_offset_from_main_key_body_right": number_member(
                fourth_label_contract,
                "x_offset_from_main_key_body_right",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
            "y_offset_from_main_key_body_top": number_member(
                fourth_label_contract,
                "y_offset_from_main_key_body_top",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
        },
        "visual_policy_constants": {
            "MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA": number_member(
                labels_contract,
                "main_key_body_optical_width_delta",
                label="android_app_contract.labels",
            ),
            "FOURTH_LABEL_X_OFFSET_FROM_MAIN_KEY_BODY_RIGHT": number_member(
                fourth_label_contract,
                "x_offset_from_main_key_body_right",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
            "FOURTH_LABEL_Y_OFFSET_FROM_MAIN_KEY_BODY_TOP": number_member(
                fourth_label_contract,
                "y_offset_from_main_key_body_top",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
        },
    }


def main() -> int:
    """Write the visual-policy payload to standard output as formatted JSON."""
    json.dump(
        build_key_visual_policy_payload(),
        sys.stdout,
        indent=2,
        sort_keys=True,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
