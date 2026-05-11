"""Load the Android key-label geometry contract from the canonical JSON."""

from __future__ import annotations

import json
import sys

from r47_contracts._contract_data import (
    load_android_app_contract,
    load_contract_document,
    mapping_member,
    number_member,
    string_member,
)
from r47_contracts._repo_paths import R47_GEOMETRY_DATA_PATH, REPO_ROOT


def build_key_label_geometry_payload() -> dict[str, object]:
    """Build the key-label geometry payload used by the Kotlin contract tests."""
    geometry = load_contract_document()
    reference_frame = mapping_member(
        geometry,
        "reference_frame",
        label="geometry document",
    )
    android_app_contract = load_android_app_contract()
    chrome_contract = mapping_member(
        android_app_contract,
        "chrome",
        label="android_app_contract",
    )
    labels_contract = mapping_member(
        android_app_contract,
        "labels",
        label="android_app_contract",
    )
    first_label = mapping_member(
        labels_contract,
        "first_label_primary",
        label="android_app_contract.labels",
    )
    first_label_text_sizes = mapping_member(
        first_label,
        "text_sizes",
        label="android_app_contract.labels.first_label_primary",
    )
    second_label = mapping_member(
        labels_contract,
        "second_label_f",
        label="android_app_contract.labels",
    )
    third_label = mapping_member(
        labels_contract,
        "third_label_g",
        label="android_app_contract.labels",
    )
    fourth_label = mapping_member(
        labels_contract,
        "fourth_label_right_side",
        label="android_app_contract.labels",
    )
    top_label_solver = mapping_member(
        labels_contract,
        "top_label_solver",
        label="android_app_contract.labels",
    )

    reference_canvas = {
        "width": number_member(reference_frame, "width", label="reference_frame"),
        "height": number_member(reference_frame, "height", label="reference_frame"),
    }
    non_softkey_view_height = number_member(
        chrome_contract,
        "non_softkey_view_height",
        label="android_app_contract.chrome",
    )
    label_constants = {
        "DEFAULT_PRIMARY_LEGEND_TEXT_SIZE": number_member(
            first_label_text_sizes,
            "default",
            label="android_app_contract.labels.first_label_primary.text_sizes",
        ),
        "NUMERIC_PRIMARY_LEGEND_TEXT_SIZE": number_member(
            first_label_text_sizes,
            "numeric",
            label="android_app_contract.labels.first_label_primary.text_sizes",
        ),
        "SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE": number_member(
            first_label_text_sizes,
            "shifted",
            label="android_app_contract.labels.first_label_primary.text_sizes",
        ),
        "TOP_F_G_LABEL_TEXT_SIZE": number_member(
            second_label,
            "text_size",
            label="android_app_contract.labels.second_label_f",
        ),
        "FOURTH_LABEL_TEXT_SIZE": number_member(
            fourth_label,
            "text_size",
            label="android_app_contract.labels.fourth_label_right_side",
        ),
        "TOP_F_G_LABEL_HORIZONTAL_GAP": number_member(
            second_label,
            "horizontal_gap",
            label="android_app_contract.labels.second_label_f",
        ),
        "TOP_F_G_LABEL_VERTICAL_LIFT": number_member(
            second_label,
            "vertical_lift",
            label="android_app_contract.labels.second_label_f",
        ),
    }
    ratio_constants = {
        "MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW": number_member(
            labels_contract,
            "main_key_body_height_fraction_of_view",
            label="android_app_contract.labels",
        ),
        "STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION": number_member(
            labels_contract,
            "standard_key_fourth_label_strip_width_fraction",
            label="android_app_contract.labels",
        ),
        "MATRIX_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION": number_member(
            labels_contract,
            "matrix_key_fourth_label_strip_width_fraction",
            label="android_app_contract.labels",
        ),
    }
    label_layers = {
        "first_label_primary": {
            "text_sizes": {
                "default": label_constants["DEFAULT_PRIMARY_LEGEND_TEXT_SIZE"],
                "numeric": label_constants["NUMERIC_PRIMARY_LEGEND_TEXT_SIZE"],
                "shifted": label_constants["SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE"],
            },
            "horizontal_anchor": string_member(
                first_label,
                "horizontal_anchor",
                label="android_app_contract.labels.first_label_primary",
            ),
            "vertical_anchor": string_member(
                first_label,
                "vertical_anchor",
                label="android_app_contract.labels.first_label_primary",
            ),
        },
        "second_label_f": {
            "text_size": label_constants["TOP_F_G_LABEL_TEXT_SIZE"],
            "shared_group_horizontal_gap": label_constants[
                "TOP_F_G_LABEL_HORIZONTAL_GAP"
            ],
            "vertical_lift": label_constants["TOP_F_G_LABEL_VERTICAL_LIFT"],
            "horizontal_anchor": string_member(
                second_label,
                "horizontal_anchor",
                label="android_app_contract.labels.second_label_f",
            ),
            "vertical_anchor": string_member(
                second_label,
                "vertical_anchor",
                label="android_app_contract.labels.second_label_f",
            ),
        },
        "third_label_g": {
            "text_size": label_constants["TOP_F_G_LABEL_TEXT_SIZE"],
            "shared_group_horizontal_gap": number_member(
                third_label,
                "horizontal_gap",
                label="android_app_contract.labels.third_label_g",
            ),
            "vertical_lift": number_member(
                third_label,
                "vertical_lift",
                label="android_app_contract.labels.third_label_g",
            ),
            "horizontal_anchor": string_member(
                third_label,
                "horizontal_anchor",
                label="android_app_contract.labels.third_label_g",
            ),
            "vertical_anchor": string_member(
                third_label,
                "vertical_anchor",
                label="android_app_contract.labels.third_label_g",
            ),
        },
        "fourth_label_right_side": {
            "text_size": label_constants["FOURTH_LABEL_TEXT_SIZE"],
            "x_offset_from_main_key_body_right": number_member(
                fourth_label,
                "x_offset_from_main_key_body_right",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
            "y_offset_from_main_key_body_top": number_member(
                fourth_label,
                "y_offset_from_main_key_body_top",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
            "standard_key_strip_width_fraction": ratio_constants[
                "STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION"
            ],
            "matrix_key_strip_width_fraction": ratio_constants[
                "MATRIX_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION"
            ],
            "horizontal_anchor": string_member(
                fourth_label,
                "horizontal_anchor",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
            "vertical_anchor": string_member(
                fourth_label,
                "vertical_anchor",
                label="android_app_contract.labels.fourth_label_right_side",
            ),
        },
    }

    return {
        "contract_domain": "android_app",
        "source": {
            "geometry_path": str(R47_GEOMETRY_DATA_PATH.relative_to(REPO_ROOT)),
            "contract_section": "android_app_contract.labels",
        },
        "reference_canvas": reference_canvas,
        "non_softkey_view": {
            "reference_height": non_softkey_view_height,
        },
        "label_constants": label_constants,
        "label_layers": label_layers,
        "surface_constants": {
            "MAIN_KEY_DRAW_CORNER_RADIUS": number_member(
                labels_contract,
                "main_key_draw_corner_radius",
                label="android_app_contract.labels",
            ),
            "SOFTKEY_DRAW_CORNER_RADIUS": number_member(
                labels_contract,
                "softkey_draw_corner_radius",
                label="android_app_contract.labels",
            ),
        },
        "ratio_constants": ratio_constants,
        "top_label_solver": {
            "max_shift_fraction": number_member(
                top_label_solver,
                "max_shift_fraction",
                label="android_app_contract.labels.top_label_solver",
            ),
            "min_scale": number_member(
                top_label_solver,
                "min_scale",
                label="android_app_contract.labels.top_label_solver",
            ),
            "scale_step": number_member(
                top_label_solver,
                "scale_step",
                label="android_app_contract.labels.top_label_solver",
            ),
        },
    }


def main() -> int:
    """Write the key-label geometry payload to standard output as formatted JSON."""
    json.dump(
        build_key_label_geometry_payload(),
        sys.stdout,
        indent=2,
        sort_keys=True,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
