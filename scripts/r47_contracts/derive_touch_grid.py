"""Derive the touch-grid payload from the canonical R47 physical dataset."""

from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass
from itertools import pairwise
from typing import TYPE_CHECKING

from r47_contracts._contract_data import index_geometry_tables, load_physical_geometry
from r47_contracts._repo_paths import R47_PHYSICAL_GEOMETRY_DATA_PATH, REPO_ROOT

if TYPE_CHECKING:
    from r47_contracts._contract_data import GeometryEntry, PhysicalGeometry

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


def _center_from_entry(entry: GeometryEntry) -> float:
    return _midpoint(entry["start"], entry["stop"])


def softkey_touch_row_top(geometry: PhysicalGeometry) -> float:
    """Return the top of the softkey touch row as the payload publishes it.

    It is the first upper-row boundary, rounded like every published boundary,
    so the shell contract that aligns to it reads the same number a consumer of
    the payload would.
    """
    vertical_main = index_geometry_tables(geometry)["vertical_main"]
    centers = [_center_from_entry(vertical_main[row_id]) for row_id in _UPPER_ROW_IDS]
    return _rounded_value(_centerline_boundaries(centers, label="upper-row")[0])


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
    geometry = load_physical_geometry()
    reference_width = float(geometry["reference_frame"]["width"])
    reference_height = float(geometry["reference_frame"]["height"])
    tables = index_geometry_tables(geometry)

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
            "dataset": geometry["dataset"],
            "geometry_path": str(
                R47_PHYSICAL_GEOMETRY_DATA_PATH.relative_to(REPO_ROOT),
            ),
            "reference_height": reference_height,
            "reference_width": reference_width,
            "version": geometry["version"],
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
