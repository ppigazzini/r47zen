"""Load the Android key-label geometry contract from the canonical JSON."""

from __future__ import annotations

import json
import sys

from r47_contracts._contract_data import (
    load_android_ui_contract,
    load_physical_geometry,
    mapping_member,
    number_member,
    string_member,
)
from r47_contracts._repo_paths import (
    R47_ANDROID_UI_CONTRACT_PATH,
    R47_PHYSICAL_GEOMETRY_DATA_PATH,
    REPO_ROOT,
)


def _require_mapping(value: object, *, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        message = f"Expected {label} to be a mapping, got {value!r}"
        raise TypeError(message)
    return {key: nested for key, nested in value.items() if isinstance(key, str)}


def _require_list(value: object, *, label: str) -> list[object]:
    if not isinstance(value, list):
        message = f"Expected {label} to be a list, got {value!r}"
        raise TypeError(message)
    return list(value)


def _family_pitch(geometry: dict[str, object], *, table_id: str, family: str) -> float:
    tables = _require_list(geometry.get("tables"), label="geometry document.tables")
    for raw_table in tables:
        table = _require_mapping(raw_table, label="geometry table")
        if table.get("id") != table_id:
            continue
        entries = _require_list(table.get("entries"), label=f"{table_id}.entries")
        for raw_entry in entries:
            entry = _require_mapping(raw_entry, label=f"{table_id} entry")
            if entry.get("family") != family:
                continue
            start_step = entry.get("start_step")
            if isinstance(start_step, int | float) and not isinstance(start_step, bool):
                return float(start_step)
    message = f"Expected a pitch for {table_id}/{family}"
    raise ValueError(message)


def build_key_label_geometry_payload() -> dict[str, object]:
    """Build the key-label geometry payload used by the Kotlin contract tests."""
    geometry = load_physical_geometry()
    reference_frame = mapping_member(
        geometry,
        "reference_frame",
        label="geometry document",
    )
    android_app_contract = load_android_ui_contract()
    chrome_contract = mapping_member(
        android_app_contract,
        "chrome",
        label="android_ui_contract",
    )
    key_surface_contract = mapping_member(
        android_app_contract,
        "key_surface",
        label="android_ui_contract",
    )
    labels_contract = mapping_member(
        android_app_contract,
        "label_layout",
        label="android_ui_contract",
    )
    first_label = mapping_member(
        labels_contract,
        "primary_legend",
        label="android_ui_contract.label_layout",
    )
    first_label_text_sizes = mapping_member(
        first_label,
        "text_sizes",
        label="android_ui_contract.label_layout.primary_legend",
    )
    second_label = mapping_member(
        labels_contract,
        "top_f_legend",
        label="android_ui_contract.label_layout",
    )
    third_label = mapping_member(
        labels_contract,
        "top_g_legend",
        label="android_ui_contract.label_layout",
    )
    fourth_label = mapping_member(
        labels_contract,
        "right_side_letter_legend",
        label="android_ui_contract.label_layout",
    )
    top_label_solver = mapping_member(
        android_app_contract,
        "top_label_solver",
        label="android_ui_contract",
    )
    main_key_surface = mapping_member(
        key_surface_contract,
        "main_key",
        label="android_ui_contract.key_surface",
    )
    softkey_surface = mapping_member(
        key_surface_contract,
        "softkey",
        label="android_ui_contract.key_surface",
    )
    standard_key_surface = mapping_member(
        key_surface_contract,
        "standard_key",
        label="android_ui_contract.key_surface",
    )
    matrix_key_surface = mapping_member(
        key_surface_contract,
        "matrix_key",
        label="android_ui_contract.key_surface",
    )

    reference_canvas = {
        "width": number_member(reference_frame, "width", label="reference_frame"),
        "height": number_member(reference_frame, "height", label="reference_frame"),
    }
    non_softkey_view_height = number_member(
        chrome_contract,
        "non_softkey_view_height",
        label="android_ui_contract.chrome",
    )
    standard_pitch = _family_pitch(
        geometry,
        table_id="horizontal_main",
        family="standard_columns",
    )
    matrix_pitch = _family_pitch(
        geometry,
        table_id="horizontal_main",
        family="matrix_4x4",
    )
    label_constants = {
        "DEFAULT_PRIMARY_LEGEND_TEXT_SIZE": number_member(
            first_label_text_sizes,
            "default",
            label="android_ui_contract.label_layout.primary_legend.text_sizes",
        ),
        "NUMERIC_PRIMARY_LEGEND_TEXT_SIZE": number_member(
            first_label_text_sizes,
            "numeric",
            label="android_ui_contract.label_layout.primary_legend.text_sizes",
        ),
        "SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE": number_member(
            first_label_text_sizes,
            "shifted",
            label="android_ui_contract.label_layout.primary_legend.text_sizes",
        ),
        "PRIMARY_LEGEND_HORIZONTAL_PADDING": number_member(
            first_label,
            "horizontal_padding",
            label="android_ui_contract.label_layout.primary_legend",
        ),
        "TOP_F_G_LABEL_TEXT_SIZE": number_member(
            second_label,
            "text_size",
            label="android_ui_contract.label_layout.top_f_legend",
        ),
        "FOURTH_LABEL_TEXT_SIZE": number_member(
            fourth_label,
            "text_size",
            label="android_ui_contract.label_layout.right_side_letter_legend",
        ),
        "TOP_F_G_LABEL_HORIZONTAL_GAP": number_member(
            second_label,
            "horizontal_gap",
            label="android_ui_contract.label_layout.top_f_legend",
        ),
        "TOP_F_G_LABEL_VERTICAL_LIFT": number_member(
            second_label,
            "vertical_lift",
            label="android_ui_contract.label_layout.top_f_legend",
        ),
    }
    ratio_constants = {
        "MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW": number_member(
            main_key_surface,
            "painted_body_height",
            label="android_ui_contract.key_surface.main_key",
        )
        / non_softkey_view_height,
        "STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION": number_member(
            standard_key_surface,
            "right_strip_width",
            label="android_ui_contract.key_surface.standard_key",
        )
        / standard_pitch,
        "MATRIX_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION": number_member(
            matrix_key_surface,
            "right_strip_width",
            label="android_ui_contract.key_surface.matrix_key",
        )
        / matrix_pitch,
    }
    label_layers = {
        "first_label_primary": {
            "horizontal_padding": label_constants["PRIMARY_LEGEND_HORIZONTAL_PADDING"],
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
                label="android_ui_contract.label_layout.top_f_legend",
            ),
            "vertical_anchor": string_member(
                second_label,
                "vertical_anchor",
                label="android_ui_contract.label_layout.top_f_legend",
            ),
        },
        "third_label_g": {
            "text_size": label_constants["TOP_F_G_LABEL_TEXT_SIZE"],
            "shared_group_horizontal_gap": number_member(
                third_label,
                "horizontal_gap",
                label="android_ui_contract.label_layout.top_g_legend",
            ),
            "vertical_lift": number_member(
                third_label,
                "vertical_lift",
                label="android_ui_contract.label_layout.top_g_legend",
            ),
            "horizontal_anchor": string_member(
                third_label,
                "horizontal_anchor",
                label="android_ui_contract.label_layout.top_g_legend",
            ),
            "vertical_anchor": string_member(
                third_label,
                "vertical_anchor",
                label="android_ui_contract.label_layout.top_g_legend",
            ),
        },
        "fourth_label_right_side": {
            "text_size": label_constants["FOURTH_LABEL_TEXT_SIZE"],
            "x_offset_from_main_key_body_right": number_member(
                fourth_label,
                "x_offset_from_main_key_body_right",
                label="android_ui_contract.label_layout.right_side_letter_legend",
            ),
            "y_offset_from_main_key_body_top": number_member(
                fourth_label,
                "y_offset_from_main_key_body_top",
                label="android_ui_contract.label_layout.right_side_letter_legend",
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
                label="android_ui_contract.label_layout.right_side_letter_legend",
            ),
            "vertical_anchor": string_member(
                fourth_label,
                "vertical_anchor",
                label="android_ui_contract.label_layout.right_side_letter_legend",
            ),
        },
    }

    return {
        "contract_domain": "android_app",
        "source": {
            "android_contract_path": str(
                R47_ANDROID_UI_CONTRACT_PATH.relative_to(REPO_ROOT),
            ),
            "contract_section": "label_layout",
            "geometry_path": str(
                R47_PHYSICAL_GEOMETRY_DATA_PATH.relative_to(REPO_ROOT),
            ),
        },
        "reference_canvas": reference_canvas,
        "non_softkey_view": {
            "reference_height": non_softkey_view_height,
        },
        "label_constants": label_constants,
        "label_layers": label_layers,
        "surface_constants": {
            "MAIN_KEY_DRAW_CORNER_RADIUS": number_member(
                main_key_surface,
                "draw_corner_radius",
                label="android_ui_contract.key_surface.main_key",
            ),
            "SOFTKEY_DRAW_CORNER_RADIUS": number_member(
                softkey_surface,
                "draw_corner_radius",
                label="android_ui_contract.key_surface.softkey",
            ),
        },
        "ratio_constants": ratio_constants,
        "top_label_solver": {
            "max_shift_fraction": number_member(
                top_label_solver,
                "max_shift_fraction",
                label="android_ui_contract.top_label_solver",
            ),
            "min_scale": number_member(
                top_label_solver,
                "min_scale",
                label="android_ui_contract.top_label_solver",
            ),
            "scale_step": number_member(
                top_label_solver,
                "scale_step",
                label="android_ui_contract.top_label_solver",
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
