"""Derive the touch-grid payload from the canonical R47 geometry dataset."""

from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass
from itertools import pairwise

from r47_contracts._repo_paths import R47_GEOMETRY_DATA_PATH, REPO_ROOT

_UPPER_ROW_IDS = ["row_1", "row_2", "row_3", "row_4"]
_LOWER_ROW_IDS = ["row_5", "row_6", "row_7", "row_8"]
_STANDARD_COLUMN_IDS = [
    "column_1",
    "column_2",
    "column_3",
    "column_4",
    "column_5",
    "column_6",
]
_VISIBLE_MATRIX_COLUMN_IDS = [
    "matrix_4x4_1",
    "matrix_4x4_2",
    "matrix_4x4_3",
    "matrix_4x4_4",
]
_OUTER_MATRIX_SYMMETRY_ID = "matrix_4x4_4"
_ENTER_ENTRY_ID = "enter_left"
_MIN_BOUNDARY_CENTER_COUNT = 2


class TouchGridContractError(ValueError):
    """Raised when the geometry dataset cannot drive touch-grid derivation."""

    @classmethod
    def invalid_data(
        cls,
        label: str,
        expected: str,
        actual: object,
    ) -> TouchGridContractError:
        """Build an error for invalid structured geometry data."""
        message = f"Expected {label} to be {expected}, got {actual!r}"
        return cls(message)

    @classmethod
    def missing_boundaries(cls, label: str) -> TouchGridContractError:
        """Build an error for a centerline sequence that is too short."""
        message = f"Need at least two centers to derive {label} boundaries"
        return cls(message)


@dataclass(frozen=True)
class TouchCell:
    """Describe a single logical keypad cell in the derived touch grid."""

    code: int
    start_column: int
    column_span: int = 1


@dataclass(frozen=True)
class TouchZone:
    """Describe the logical touch rectangle for a single keypad cell."""

    code: int
    x: float
    y: float
    width: float
    height: float


def _midpoint(first: float, second: float) -> float:
    return (first + second) / 2.0


def _deltas(values: list[float]) -> list[float]:
    return [second - first for first, second in pairwise(values)]


def _centerline_boundaries(centers: list[float], *, label: str) -> list[float]:
    if len(centers) < _MIN_BOUNDARY_CENTER_COUNT:
        raise TouchGridContractError.missing_boundaries(label)

    boundaries = [0.0] * (len(centers) + 1)
    boundaries[0] = centers[0] - (centers[1] - centers[0]) / 2.0
    for index in range(len(centers) - 1):
        boundaries[index + 1] = _midpoint(centers[index], centers[index + 1])
    boundaries[-1] = centers[-1] + (centers[-1] - centers[-2]) / 2.0
    return boundaries


def _build_zones(
    row_boundaries: list[float],
    column_boundaries: list[float],
    row_cells: list[list[TouchCell]],
) -> list[TouchZone]:
    zones: list[TouchZone] = []
    for row_index, cells in enumerate(row_cells):
        top = row_boundaries[row_index]
        bottom = row_boundaries[row_index + 1]
        for cell in cells:
            left = column_boundaries[cell.start_column]
            right = column_boundaries[cell.start_column + cell.column_span]
            zones.append(
                TouchZone(
                    code=cell.code,
                    x=left,
                    y=top,
                    width=right - left,
                    height=bottom - top,
                ),
            )
    return zones


def _rounded(values: list[float]) -> list[float]:
    return [round(value, 6) for value in values]


def _rounded_value(value: float) -> float:
    return round(value, 6)


def _require_mapping(value: object, *, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise TouchGridContractError.invalid_data(label, "a JSON object", value)
    return {
        _require_string(key, label=f"{label}.key"): nested_value
        for key, nested_value in value.items()
    }


def _require_list(value: object, *, label: str) -> list[object]:
    if not isinstance(value, list):
        raise TouchGridContractError.invalid_data(label, "a JSON list", value)
    return list(value)


def _require_number(value: object, *, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise TouchGridContractError.invalid_data(label, "a number", value)
    return float(value)


def _require_string(value: object, *, label: str) -> str:
    if not isinstance(value, str):
        raise TouchGridContractError.invalid_data(label, "a string", value)
    return value


def _require_scalar(value: object, *, label: str) -> str | int | float:
    if isinstance(value, bool) or not isinstance(value, str | int | float):
        raise TouchGridContractError.invalid_data(label, "a scalar value", value)
    return value


def _load_geometry() -> dict[str, object]:
    with R47_GEOMETRY_DATA_PATH.open("r", encoding="utf-8") as handle:
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


def _center_from_entry(entry: dict[str, object]) -> float:
    start = _require_number(entry.get("start"), label="entry.start")
    stop = _require_number(entry.get("stop"), label="entry.stop")
    return _midpoint(start, stop)


def _check_uniform_spacing(centers: list[float]) -> dict[str, float | list[float]]:
    spacing = _deltas(centers)
    if not spacing:
        return {
            "spacing": [],
            "max_delta_from_first": 0.0,
        }
    first = spacing[0]
    return {
        "spacing": _rounded(spacing),
        "max_delta_from_first": _rounded_value(
            max(abs(value - first) for value in spacing),
        ),
    }


def build_touch_grid_payload() -> dict[str, object]:
    """Build the logical touch-grid payload from the canonical geometry dataset."""
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
    tables = _index_tables(geometry)

    vertical_main = tables["vertical_main"]
    horizontal_main = tables["horizontal_main"]
    horizontal_symmetry = tables["horizontal_symmetry"]

    upper_row_entries = [vertical_main[row_id] for row_id in _UPPER_ROW_IDS]
    lower_row_entries = [vertical_main[row_id] for row_id in _LOWER_ROW_IDS]
    upper_column_entries = [
        horizontal_main[column_id] for column_id in _STANDARD_COLUMN_IDS
    ]
    visible_matrix_entries = [
        horizontal_main[column_id] for column_id in _VISIBLE_MATRIX_COLUMN_IDS
    ]

    upper_row_centers = [_center_from_entry(entry) for entry in upper_row_entries]
    lower_row_centers = [_center_from_entry(entry) for entry in lower_row_entries]
    upper_column_centers = [_center_from_entry(entry) for entry in upper_column_entries]
    visible_matrix_centers = [
        _center_from_entry(entry) for entry in visible_matrix_entries
    ]

    visible_matrix_pitch = visible_matrix_centers[1] - visible_matrix_centers[0]
    extrapolated_outer_center = visible_matrix_centers[0] - visible_matrix_pitch
    lower_column_centers = [extrapolated_outer_center, *visible_matrix_centers]

    upper_column_boundaries = _centerline_boundaries(
        upper_column_centers,
        label="upper-column",
    )
    lower_column_boundaries = _centerline_boundaries(
        lower_column_centers,
        label="lower-column",
    )
    upper_row_boundaries = _centerline_boundaries(
        upper_row_centers,
        label="upper-row",
    )
    lower_row_boundaries = _centerline_boundaries(
        lower_row_centers,
        label="lower-row",
    )

    symmetry_outer_center = _center_from_entry(
        horizontal_symmetry[_OUTER_MATRIX_SYMMETRY_ID],
    )
    enter_center = _center_from_entry(horizontal_main[_ENTER_ENTRY_ID])
    merged_enter_center = _midpoint(
        upper_column_boundaries[0],
        upper_column_boundaries[2],
    )

    upper_rows = [
        [TouchCell(code=38 + index, start_column=index) for index in range(6)],
        [TouchCell(code=1 + index, start_column=index) for index in range(6)],
        [TouchCell(code=7 + index, start_column=index) for index in range(6)],
        [
            TouchCell(code=13, start_column=0, column_span=2),
            TouchCell(code=14, start_column=2),
            TouchCell(code=15, start_column=3),
            TouchCell(code=16, start_column=4),
            TouchCell(code=17, start_column=5),
        ],
    ]
    lower_rows = [
        [
            TouchCell(code=18 + row * 5 + column, start_column=column)
            for column in range(5)
        ]
        for row in range(4)
    ]

    logical_zones = _build_zones(
        upper_row_boundaries,
        upper_column_boundaries,
        upper_rows,
    )
    logical_zones.extend(
        _build_zones(lower_row_boundaries, lower_column_boundaries, lower_rows),
    )

    return {
        "source": {
            "dataset": _require_string(geometry.get("dataset"), label="dataset"),
            "geometry_path": str(R47_GEOMETRY_DATA_PATH.relative_to(REPO_ROOT)),
            "reference_height": reference_height,
            "reference_width": reference_width,
            "version": _require_scalar(geometry.get("version"), label="version"),
        },
        "logical_canvas": {
            "source": "reference_frame",
            "width": reference_width,
            "height": reference_height,
        },
        "checks": {
            "upper_column_spacing": _check_uniform_spacing(upper_column_centers),
            "upper_row_spacing": _check_uniform_spacing(upper_row_centers),
            "visible_matrix_spacing": _check_uniform_spacing(visible_matrix_centers),
            "lower_outer_center_delta_vs_symmetry": _rounded_value(
                extrapolated_outer_center - symmetry_outer_center,
            ),
            "merged_enter_center_delta": _rounded_value(
                merged_enter_center - enter_center,
            ),
        },
        "logical_canvas_geometry": {
            "upper": {
                "column_centers": _rounded(upper_column_centers),
                "row_boundaries": _rounded(upper_row_boundaries),
                "row_centers": _rounded(upper_row_centers),
                "column_boundaries": _rounded(upper_column_boundaries),
            },
            "lower": {
                "column_centers": _rounded(lower_column_centers),
                "row_boundaries": _rounded(lower_row_boundaries),
                "row_centers": _rounded(lower_row_centers),
                "column_boundaries": _rounded(lower_column_boundaries),
            },
            "zones": [asdict(zone) for zone in logical_zones],
        },
    }


def main() -> int:
    """Write the touch-grid payload to standard output as formatted JSON."""
    json.dump(build_touch_grid_payload(), sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
