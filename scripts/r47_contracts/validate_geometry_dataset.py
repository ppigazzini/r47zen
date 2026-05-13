"""Validate the canonical R47 geometry dataset used by the Python contract suite."""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from dataclasses import dataclass
from itertools import pairwise
from pathlib import Path
from typing import TYPE_CHECKING

from r47_contracts._contract_data import (
    load_android_ui_contract,
    load_contract_document,
)
from r47_contracts._contract_data import (
    mapping_member as contract_mapping_member,
)
from r47_contracts._contract_data import (
    number_member as contract_number_member,
)
from r47_contracts._contract_data import (
    string_member as contract_string_member,
)
from r47_contracts._kotlin_consts import parse_kotlin_const_values_from_paths
from r47_contracts._repo_paths import (
    KOTLIN_R47_ROOT,
    R47_ANDROID_UI_CONTRACT_PATH,
    R47_PHYSICAL_GEOMETRY_DATA_PATH,
)

if TYPE_CHECKING:
    from collections.abc import Iterable, Sequence

_MIN_SEQUENCE_LENGTH = 2
_SINGLETON_SEQUENCE_LENGTH = 1
_MATRIX_VISIBLE_COLUMN_COUNT = 4
_MATRIX_FULL_COLUMN_COUNT = 5
_SUPPORTED_FAMILIES = frozenset({"rows", "standard_columns", "matrix_4x4", "enter"})
_FLOAT_TOLERANCE = 1e-6

_KOTLIN_GEOMETRY_PATH = KOTLIN_R47_ROOT / "R47Geometry.kt"
_KOTLIN_KEYPAD_POLICY_PATH = KOTLIN_R47_ROOT / "R47KeypadPolicy.kt"
_KOTLIN_KEY_VIEW_PATH = KOTLIN_R47_ROOT / "CalculatorKeyView.kt"


class GeometryValidationError(ValueError):
    """Raised when a geometry dataset is structurally invalid."""


@dataclass(frozen=True)
class RawEntry:
    """Represent one raw geometry row from the source dataset."""

    table: str
    label: str
    family: str
    start: int | None
    start_step: int | None
    stop: int | None
    stop_step: int | None
    span: int | None


@dataclass(frozen=True)
class _FamilyAnalysisContext:
    table: str
    family: str
    entries: list[RawEntry]
    ordered: list[RawEntry]
    ordered_with_stop: list[tuple[str, int, int]]
    expected_span: int | None
    expected_start_pitch: int | None
    expected_stop_pitch: int | None
    expected_gap: int | None


@dataclass(frozen=True)
class _SplitAndroidUiContractContext:
    physical_root: dict[str, object]
    android_contract: dict[str, object]
    reference_width: float
    reference_height: float
    based_on: dict[str, object]
    logical_canvas: dict[str, object]
    chrome: dict[str, object]
    key_surface: dict[str, object]
    lcd_windows: dict[str, object]
    native_lcd_window: dict[str, object]
    lcd_frame_buffer: dict[str, object]
    main_key_surface: dict[str, object]
    standard_key_surface: dict[str, object]
    matrix_key_surface: dict[str, object]
    first_label_text_sizes: dict[str, object]
    second_label: dict[str, object]
    third_label: dict[str, object]
    fourth_label: dict[str, object]
    top_label_solver: dict[str, object]
    row_height: int
    row_gap: int
    softkey_row_top: int
    standard_key_width: int
    standard_pitch: int
    matrix_key_width: int
    matrix_pitch: int
    standard_right_strip_width: float
    matrix_right_strip_width: float


def _family_first_start(
    entries: list[RawEntry],
    *,
    table: str,
    family: str,
) -> int:
    starts = [
        entry.start
        for entry in entries
        if entry.table == table and entry.family == family and entry.start is not None
    ]
    if not starts:
        message = f"Missing start positions for {table}/{family}"
        raise GeometryValidationError(message)
    return min(starts)


def _parse_int(value: object) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        message = f"Expected int or null, got {value!r}"
        raise GeometryValidationError(message)
    return int(value)


def _require_mapping(value: object, *, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        message = f"Expected {label} to be an object, got {value!r}"
        raise GeometryValidationError(message)
    return {
        _require_string(key, label=f"{label}.key"): nested_value
        for key, nested_value in value.items()
    }


def _require_list(value: object, *, label: str) -> list[object]:
    if not isinstance(value, list):
        message = f"Expected {label} to be a list, got {value!r}"
        raise GeometryValidationError(message)
    return list(value)


def _require_string(value: object, *, label: str) -> str:
    if not isinstance(value, str) or not value:
        message = f"Expected {label} to be a non-empty string, got {value!r}"
        raise GeometryValidationError(message)
    return value


def load_raw_entries(data_path: Path) -> list[RawEntry]:
    """Load the raw geometry entries from a JSON dataset file."""
    root = load_contract_document(data_path)
    return load_raw_entries_from_document(root)


def load_raw_entries_from_document(root: dict[str, object]) -> list[RawEntry]:
    """Load the raw geometry entries from an already parsed JSON document."""
    tables = _require_list(root.get("tables"), label="tables")

    entries: list[RawEntry] = []
    for raw_table in tables:
        table = _require_mapping(raw_table, label="table")
        table_id = _require_string(table.get("id"), label="table.id")
        raw_entries = _require_list(table.get("entries"), label=f"{table_id}.entries")

        for raw_entry in raw_entries:
            entry = _require_mapping(raw_entry, label=f"{table_id} entry")
            label = _require_string(entry.get("label"), label=f"{table_id}.label")
            family = _require_string(
                entry.get("family"),
                label=f"{table_id}.{label}.family",
            )
            entries.append(
                RawEntry(
                    table=table_id,
                    label=label,
                    family=family,
                    start=_parse_int(entry.get("start")),
                    start_step=_parse_int(entry.get("start_step")),
                    stop=_parse_int(entry.get("stop")),
                    stop_step=_parse_int(entry.get("stop_step")),
                    span=_parse_int(entry.get("span")),
                ),
            )

    return entries


def _sorted_with_positions(entries: Iterable[RawEntry]) -> list[RawEntry]:
    return sorted(
        (entry for entry in entries if entry.start is not None),
        key=lambda item: item.start,
    )


def _diffs(values: list[int]) -> list[int]:
    return [current - previous for previous, current in pairwise(values)]


def _expected_single(values: list[int]) -> int | None:
    if not values:
        return None
    counts: dict[int, int] = defaultdict(int)
    for value in values:
        counts[value] += 1
    return max(counts.items(), key=lambda item: (item[1], -abs(item[0])))[0]


def analyze(entries: list[RawEntry]) -> list[str]:
    """Analyze the raw geometry rows and return any consistency errors."""
    grouped = _group_entries(entries)
    errors = _entry_span_errors(entries)
    for context in _iter_family_contexts(grouped):
        errors.extend(_family_errors(context, grouped))
    return errors


def _group_entries(entries: list[RawEntry]) -> dict[tuple[str, str], list[RawEntry]]:
    grouped: dict[tuple[str, str], list[RawEntry]] = defaultdict(list)
    for entry in entries:
        grouped[(entry.table, entry.family)].append(entry)
    return grouped


def _entry_span_errors(entries: list[RawEntry]) -> list[str]:
    errors: list[str] = []
    for entry in entries:
        if entry.start is None or entry.stop is None or entry.span is None:
            continue
        actual_span = entry.stop - entry.start
        if actual_span != entry.span:
            errors.append(
                "ERROR "
                f"[{entry.table}/{entry.family}] {entry.label}: "
                f"span={entry.span}, computed stop-start={actual_span}",
            )
    return errors


def _iter_family_contexts(
    grouped: dict[tuple[str, str], list[RawEntry]],
) -> list[_FamilyAnalysisContext]:
    contexts: list[_FamilyAnalysisContext] = []
    for (table, family), family_entries in sorted(grouped.items()):
        if family not in _SUPPORTED_FAMILIES:
            continue
        contexts.append(_build_family_context(table, family, family_entries))
    return contexts


def _build_family_context(
    table: str,
    family: str,
    family_entries: list[RawEntry],
) -> _FamilyAnalysisContext:
    ordered = _sorted_with_positions(family_entries)
    ordered_with_stop = _ordered_with_stop(ordered)
    starts = [entry.start for entry in ordered if entry.start is not None]
    stops = [entry.stop for entry in ordered if entry.stop is not None]
    return _FamilyAnalysisContext(
        table=table,
        family=family,
        entries=family_entries,
        ordered=ordered,
        ordered_with_stop=ordered_with_stop,
        expected_span=_expected_single(
            [entry.span for entry in family_entries if entry.span is not None],
        ),
        expected_start_pitch=(
            _expected_single(_diffs(starts))
            if len(starts) >= _MIN_SEQUENCE_LENGTH
            else None
        ),
        expected_stop_pitch=(
            _expected_single(_diffs(stops))
            if len(stops) >= _MIN_SEQUENCE_LENGTH
            else None
        ),
        expected_gap=_expected_gap(ordered_with_stop, len(ordered)),
    )


def _ordered_with_stop(ordered: list[RawEntry]) -> list[tuple[str, int, int]]:
    return [
        (entry.label, start, stop)
        for entry in ordered
        if (start := entry.start) is not None and (stop := entry.stop) is not None
    ]


def _expected_gap(
    ordered_with_stop: list[tuple[str, int, int]],
    ordered_count: int,
) -> int | None:
    if (
        len(ordered_with_stop) < _MIN_SEQUENCE_LENGTH
        or len(ordered_with_stop) != ordered_count
    ):
        return None
    gaps = [
        next_start - current_stop
        for (_, _, current_stop), (_, next_start, _) in pairwise(ordered_with_stop)
    ]
    return _expected_single(gaps)


def _family_errors(
    context: _FamilyAnalysisContext,
    grouped: dict[tuple[str, str], list[RawEntry]],
) -> list[str]:
    errors = _expected_span_errors(context)
    errors.extend(_gap_errors(context))
    if len(context.entries) == _SINGLETON_SEQUENCE_LENGTH:
        errors.extend(_singleton_step_errors(context))
        return errors
    errors.extend(
        _pitch_errors(
            context,
            step_attribute="start_step",
            expected_pitch=context.expected_start_pitch,
        ),
    )
    errors.extend(
        _pitch_errors(
            context,
            step_attribute="stop_step",
            expected_pitch=context.expected_stop_pitch,
        ),
    )
    if context.family == "matrix_4x4":
        errors.extend(_matrix_lattice_errors(context, grouped))
    return errors


def _expected_span_errors(context: _FamilyAnalysisContext) -> list[str]:
    if context.expected_span is None:
        return []
    return [
        "ERROR "
        f"[{context.table}/{context.family}] {entry.label}: span={entry.span}, "
        f"expected {context.expected_span}"
        for entry in context.entries
        if entry.span is not None and entry.span != context.expected_span
    ]


def _gap_errors(context: _FamilyAnalysisContext) -> list[str]:
    if (
        len(context.ordered_with_stop) < _MIN_SEQUENCE_LENGTH
        or len(context.ordered_with_stop) != len(context.ordered)
        or context.expected_gap is None
    ):
        return []
    gaps = [
        next_start - current_stop
        for (_, _, current_stop), (_, next_start, _) in pairwise(
            context.ordered_with_stop,
        )
    ]
    errors: list[str] = []
    for ((left, _, _), (right, _, _)), gap in zip(
        pairwise(context.ordered_with_stop),
        gaps,
        strict=True,
    ):
        if gap != context.expected_gap:
            errors.append(
                "ERROR "
                f"[{context.table}/{context.family}] gap {left}->{right}: gap={gap}, "
                f"expected {context.expected_gap}",
            )
    return errors


def _singleton_step_errors(context: _FamilyAnalysisContext) -> list[str]:
    entry = context.entries[0]
    errors: list[str] = []
    if entry.start_step is not None:
        errors.append(
            "ERROR "
            f"[{context.table}/{context.family}] {entry.label}: unexpected "
            f"start_step={entry.start_step} for singleton geometry",
        )
    if entry.stop_step is not None:
        errors.append(
            "ERROR "
            f"[{context.table}/{context.family}] {entry.label}: unexpected "
            f"stop_step={entry.stop_step} for singleton geometry",
        )
    return errors


def _pitch_errors(
    context: _FamilyAnalysisContext,
    *,
    step_attribute: str,
    expected_pitch: int | None,
) -> list[str]:
    if expected_pitch is None:
        return []
    errors: list[str] = []
    for entry in context.entries:
        step_value = getattr(entry, step_attribute)
        if step_value is None or step_value == expected_pitch:
            continue
        errors.append(
            "ERROR "
            f"[{context.table}/{context.family}] {entry.label}: "
            f"{step_attribute}={step_value}, expected {expected_pitch}",
        )
    return errors


def _matrix_lattice_errors(
    context: _FamilyAnalysisContext,
    grouped: dict[tuple[str, str], list[RawEntry]],
) -> list[str]:
    if (
        len(context.ordered) != _MATRIX_VISIBLE_COLUMN_COUNT
        or context.expected_start_pitch is None
        or context.expected_gap is None
        or context.expected_span is None
    ):
        return []
    standard_entries = grouped.get((context.table, "standard_columns"))
    if not standard_entries:
        return []
    standard_bounds = _ordered_bounds(_sorted_with_positions(standard_entries))
    matrix_bounds = _ordered_bounds(context.ordered)
    if standard_bounds is None or matrix_bounds is None:
        return []
    errors = _matrix_span_errors(
        context.table,
        standard_bounds,
        matrix_bounds,
        context.expected_gap,
        context.expected_span,
    )
    errors.extend(
        _matrix_alignment_errors(
            context.table,
            standard_bounds,
            matrix_bounds,
            context.expected_start_pitch,
        ),
    )
    return errors


def _ordered_bounds(ordered: list[RawEntry]) -> tuple[int, int] | None:
    if not ordered:
        return None
    left = ordered[0].start
    right = ordered[-1].stop
    if left is None or right is None:
        return None
    return left, right


def _matrix_span_errors(
    table: str,
    standard_bounds: tuple[int, int],
    matrix_bounds: tuple[int, int],
    expected_gap: int,
    expected_span: int,
) -> list[str]:
    standard_left, standard_right = standard_bounds
    matrix_left, matrix_right = matrix_bounds
    visible_span = matrix_right - matrix_left
    expected_visible_span = (
        _MATRIX_VISIBLE_COLUMN_COUNT * expected_span
        + (_MATRIX_VISIBLE_COLUMN_COUNT - _SINGLETON_SEQUENCE_LENGTH) * expected_gap
    )
    standard_span = standard_right - standard_left
    expected_full_span = (
        _MATRIX_FULL_COLUMN_COUNT * expected_span
        + (_MATRIX_FULL_COLUMN_COUNT - _SINGLETON_SEQUENCE_LENGTH) * expected_gap
    )

    errors: list[str] = []
    if visible_span != expected_visible_span:
        errors.append(
            "ERROR "
            f"[{table}/matrix_4x4] visible span={visible_span}, expected "
            f"{expected_visible_span} for a 4-column slice",
        )
    if standard_span != expected_full_span:
        errors.append(
            "ERROR "
            f"[{table}/matrix_4x4] virtual 4x5 span={expected_full_span}, "
            f"standard envelope span={standard_span}",
        )
    return errors


def _matrix_alignment_errors(
    table: str,
    standard_bounds: tuple[int, int],
    matrix_bounds: tuple[int, int],
    expected_pitch: int,
) -> list[str]:
    standard_left, standard_right = standard_bounds
    matrix_left, matrix_right = matrix_bounds
    left_aligned = matrix_left == standard_left
    right_aligned = matrix_right == standard_right
    if left_aligned == right_aligned:
        return [
            "ERROR "
            f"[{table}/matrix_4x4] expected the visible 4x4 slice to align "
            "to exactly one side of the virtual 4x5 lattice",
        ]
    if table == "horizontal_main" and not right_aligned:
        return [
            "ERROR "
            f"[{table}/matrix_4x4] expected the main table to omit the "
            "leftmost 4x5 slot and stay right-aligned",
        ]
    if table == "horizontal_symmetry" and not left_aligned:
        return [
            "ERROR "
            f"[{table}/matrix_4x4] expected the symmetry table to omit the "
            "rightmost 4x5 slot and stay left-aligned",
        ]
    if left_aligned:
        return _matrix_edge_errors(
            table=table,
            actual=matrix_right + expected_pitch,
            expected=standard_right,
            side_label="right",
        )
    return _matrix_edge_errors(
        table=table,
        actual=matrix_left - expected_pitch,
        expected=standard_left,
        side_label="left",
    )


def _matrix_edge_errors(
    *,
    table: str,
    actual: int,
    expected: int,
    side_label: str,
) -> list[str]:
    if actual == expected:
        return []
    return [
        "ERROR "
        f"[{table}/matrix_4x4] {side_label}-omitted 4x5 lattice "
        f"{('starts' if side_label == 'left' else 'ends')} at {actual}, "
        f"expected {expected}",
    ]


def _family_metrics(
    entries: list[RawEntry],
    *,
    table: str,
    family: str,
) -> tuple[int, int]:
    matching = [
        entry for entry in entries if entry.table == table and entry.family == family
    ]
    if not matching:
        message = f"Missing {table}/{family} entries"
        raise GeometryValidationError(message)

    span = next((entry.span for entry in matching if entry.span is not None), None)
    pitch = next(
        (entry.start_step for entry in matching if entry.start_step is not None),
        None,
    )
    if span is None or pitch is None:
        message = f"Missing span or pitch for {table}/{family}"
        raise GeometryValidationError(message)
    return span, pitch


def _build_split_android_ui_contract_context(
    physical_root: dict[str, object],
    android_contract: dict[str, object],
    entries: list[RawEntry],
) -> _SplitAndroidUiContractContext:
    reference_frame = contract_mapping_member(
        physical_root,
        "reference_frame",
        label="geometry document",
    )
    based_on = contract_mapping_member(
        android_contract,
        "based_on",
        label="android_ui_contract",
    )
    logical_canvas = contract_mapping_member(
        android_contract,
        "logical_canvas",
        label="android_ui_contract",
    )
    chrome = contract_mapping_member(
        android_contract,
        "chrome",
        label="android_ui_contract",
    )
    key_surface = contract_mapping_member(
        android_contract,
        "key_surface",
        label="android_ui_contract",
    )
    label_layout = contract_mapping_member(
        android_contract,
        "label_layout",
        label="android_ui_contract",
    )
    first_label = contract_mapping_member(
        label_layout,
        "primary_legend",
        label="android_ui_contract.label_layout",
    )
    main_key_surface = contract_mapping_member(
        key_surface,
        "main_key",
        label="android_ui_contract.key_surface",
    )
    standard_key_surface = contract_mapping_member(
        key_surface,
        "standard_key",
        label="android_ui_contract.key_surface",
    )
    matrix_key_surface = contract_mapping_member(
        key_surface,
        "matrix_key",
        label="android_ui_contract.key_surface",
    )
    row_height, row_pitch = _family_metrics(
        entries,
        table="vertical_main",
        family="rows",
    )
    softkey_row_top = _family_first_start(
        entries,
        table="vertical_main",
        family="rows",
    )
    standard_key_width, standard_pitch = _family_metrics(
        entries,
        table="horizontal_main",
        family="standard_columns",
    )
    matrix_key_width, matrix_pitch = _family_metrics(
        entries,
        table="horizontal_main",
        family="matrix_4x4",
    )

    return _SplitAndroidUiContractContext(
        physical_root=physical_root,
        android_contract=android_contract,
        reference_width=contract_number_member(
            reference_frame,
            "width",
            label="reference_frame",
        ),
        reference_height=contract_number_member(
            reference_frame,
            "height",
            label="reference_frame",
        ),
        based_on=based_on,
        logical_canvas=logical_canvas,
        chrome=chrome,
        key_surface=key_surface,
        lcd_windows=contract_mapping_member(
            chrome,
            "lcd_windows",
            label="android_ui_contract.chrome",
        ),
        native_lcd_window=contract_mapping_member(
            contract_mapping_member(
                chrome,
                "lcd_windows",
                label="android_ui_contract.chrome",
            ),
            "native",
            label="android_ui_contract.chrome.lcd_windows",
        ),
        lcd_frame_buffer=contract_mapping_member(
            chrome,
            "lcd_frame_buffer",
            label="android_ui_contract.chrome",
        ),
        main_key_surface=main_key_surface,
        standard_key_surface=standard_key_surface,
        matrix_key_surface=matrix_key_surface,
        first_label_text_sizes=contract_mapping_member(
            first_label,
            "text_sizes",
            label="android_ui_contract.label_layout.primary_legend",
        ),
        second_label=contract_mapping_member(
            label_layout,
            "top_f_legend",
            label="android_ui_contract.label_layout",
        ),
        third_label=contract_mapping_member(
            label_layout,
            "top_g_legend",
            label="android_ui_contract.label_layout",
        ),
        fourth_label=contract_mapping_member(
            label_layout,
            "right_side_letter_legend",
            label="android_ui_contract.label_layout",
        ),
        top_label_solver=contract_mapping_member(
            android_contract,
            "top_label_solver",
            label="android_ui_contract",
        ),
        row_height=row_height,
        row_gap=row_pitch - row_height,
        softkey_row_top=softkey_row_top,
        standard_key_width=standard_key_width,
        standard_pitch=standard_pitch,
        matrix_key_width=matrix_key_width,
        matrix_pitch=matrix_pitch,
        standard_right_strip_width=contract_number_member(
            standard_key_surface,
            "right_strip_width",
            label="android_ui_contract.key_surface.standard_key",
        ),
        matrix_right_strip_width=contract_number_member(
            matrix_key_surface,
            "right_strip_width",
            label="android_ui_contract.key_surface.matrix_key",
        ),
    )


def _split_android_ui_metadata_errors(
    context: _SplitAndroidUiContractContext,
) -> list[str]:
    errors: list[str] = []
    if contract_string_member(
        context.based_on,
        "physical_dataset",
        label="android_ui_contract.based_on",
    ) != contract_string_member(
        context.physical_root,
        "dataset",
        label="geometry document",
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.based_on] physical_dataset must "
                "match the physical dataset name"
            ),
        )

    if contract_number_member(
        context.based_on,
        "physical_version",
        label="android_ui_contract.based_on",
    ) != contract_number_member(
        context.physical_root,
        "version",
        label="geometry document",
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.based_on] physical_version must "
                "match the physical dataset version"
            ),
        )

    if (
        contract_string_member(
            context.android_contract,
            "coordinate_space",
            label="android_ui_contract",
        )
        != "logical_canvas"
    ):
        errors.append(
            "ERROR [android_ui_contract] coordinate_space must be logical_canvas",
        )

    if (
        contract_number_member(
            context.logical_canvas,
            "width",
            label="android_ui_contract.logical_canvas",
        )
        != context.reference_width
        or contract_number_member(
            context.logical_canvas,
            "height",
            label="android_ui_contract.logical_canvas",
        )
        != context.reference_height
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.logical_canvas] logical canvas "
                "must match the physical reference frame"
            ),
        )

    return errors


def _split_android_ui_layout_errors(
    context: _SplitAndroidUiContractContext,
) -> list[str]:
    settings_strip_tap_height = contract_number_member(
        context.chrome,
        "settings_strip_tap_height",
        label="android_ui_contract.chrome",
    )
    lcd_frame_buffer_width = contract_number_member(
        context.lcd_frame_buffer,
        "pixel_width",
        label="android_ui_contract.chrome.lcd_frame_buffer",
    )
    lcd_frame_buffer_height = contract_number_member(
        context.lcd_frame_buffer,
        "pixel_height",
        label="android_ui_contract.chrome.lcd_frame_buffer",
    )
    errors: list[str] = []
    if sorted(context.lcd_windows) != ["native"]:
        errors.append(
            (
                "ERROR [android_ui_contract.chrome.lcd_windows] must expose only "
                "the native LCD window"
            ),
        )

    errors.extend(
        _lcd_window_mode_errors(
            mode_name="native",
            rect=context.native_lcd_window,
            label="android_ui_contract.chrome.lcd_windows.native",
            settings_strip_tap_height=settings_strip_tap_height,
            reference_width=context.reference_width,
        ),
    )

    errors.extend(
        _native_lcd_window_layout_errors(
            context,
            frame_buffer_aspect_ratio=(
                lcd_frame_buffer_width / lcd_frame_buffer_height
            ),
        ),
    )

    if (
        contract_number_member(
            context.main_key_surface,
            "painted_body_height",
            label="android_ui_contract.key_surface.main_key",
        )
        != context.row_height
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.key_surface.main_key] "
                "painted_body_height must match the measured row height"
            ),
        )

    if (
        context.standard_key_width + context.standard_right_strip_width
        != context.standard_pitch
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.key_surface.standard_key] "
                "right_strip_width plus measured key width must equal the "
                "standard pitch"
            ),
        )

    if (
        context.matrix_key_width + context.matrix_right_strip_width
        != context.matrix_pitch
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.key_surface.matrix_key] "
                "right_strip_width plus measured key width must equal the "
                "matrix pitch"
            ),
        )

    if contract_number_member(
        context.second_label,
        "text_size",
        label="android_ui_contract.label_layout.top_f_legend",
    ) != contract_number_member(
        context.third_label,
        "text_size",
        label="android_ui_contract.label_layout.top_g_legend",
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.label_layout] top_f_legend and "
                "top_g_legend must share one text size"
            ),
        )

    if contract_number_member(
        context.second_label,
        "horizontal_gap",
        label="android_ui_contract.label_layout.top_f_legend",
    ) != contract_number_member(
        context.third_label,
        "horizontal_gap",
        label="android_ui_contract.label_layout.top_g_legend",
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.label_layout] top_f_legend and "
                "top_g_legend must share one horizontal gap"
            ),
        )

    if contract_number_member(
        context.second_label,
        "vertical_lift",
        label="android_ui_contract.label_layout.top_f_legend",
    ) != contract_number_member(
        context.third_label,
        "vertical_lift",
        label="android_ui_contract.label_layout.top_g_legend",
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.label_layout] top_f_legend and "
                "top_g_legend must share one vertical lift"
            ),
        )

    return errors


def _lcd_window_mode_errors(
    *,
    mode_name: str,
    rect: dict[str, object],
    label: str,
    settings_strip_tap_height: float,
    reference_width: float,
) -> list[str]:
    errors: list[str] = []
    rect_top = contract_number_member(rect, "top", label=label)
    if rect_top != settings_strip_tap_height:
        errors.append(
            (
                "ERROR [android_ui_contract.chrome] "
                f"lcd_windows.{mode_name}.top must equal "
                "settings_strip_tap_height"
            ),
        )

    lcd_window_left = contract_number_member(rect, "left", label=label)
    lcd_window_width = contract_number_member(rect, "width", label=label)
    centered_width = (2.0 * lcd_window_left) + lcd_window_width
    if abs(centered_width - reference_width) > _FLOAT_TOLERANCE:
        errors.append(
            (
                "ERROR [android_ui_contract.chrome] "
                f"lcd_windows.{mode_name} must stay horizontally centered "
                "in the logical canvas"
            ),
        )
    return errors


def _native_lcd_window_layout_errors(
    context: _SplitAndroidUiContractContext,
    *,
    frame_buffer_aspect_ratio: float,
) -> list[str]:
    native_lcd_window_height = contract_number_member(
        context.native_lcd_window,
        "height",
        label="android_ui_contract.chrome.lcd_windows.native",
    )
    native_lcd_window_width = contract_number_member(
        context.native_lcd_window,
        "width",
        label="android_ui_contract.chrome.lcd_windows.native",
    )
    errors: list[str] = []
    if abs(native_lcd_window_width - round(native_lcd_window_width)) > _FLOAT_TOLERANCE:
        errors.append(
            (
                "ERROR [android_ui_contract.chrome.lcd_windows.native] width must "
                "be an integer so native mode uses an exact centered 5:3 frame"
            ),
        )

    if (
        abs(native_lcd_window_height - round(native_lcd_window_height))
        > _FLOAT_TOLERANCE
    ):
        errors.append(
            (
                "ERROR [android_ui_contract.chrome.lcd_windows.native] height must "
                "be an integer so native mode uses an exact centered 5:3 frame"
            ),
        )

    native_lcd_aspect_ratio = native_lcd_window_width / native_lcd_window_height
    if abs(native_lcd_aspect_ratio - frame_buffer_aspect_ratio) > _FLOAT_TOLERANCE:
        errors.append(
            (
                "ERROR [android_ui_contract.chrome.lcd_windows.native] width and "
                "height must preserve the 400x240 frame-buffer aspect ratio"
            ),
        )
    return errors


def _split_android_ui_constant_errors(
    context: _SplitAndroidUiContractContext,
) -> list[str]:
    geometry_consts = parse_kotlin_const_values_from_paths([_KOTLIN_GEOMETRY_PATH])
    key_view_consts = parse_kotlin_const_values_from_paths(
        [
            _KOTLIN_GEOMETRY_PATH,
            _KOTLIN_KEYPAD_POLICY_PATH,
            _KOTLIN_KEY_VIEW_PATH,
        ],
    )
    errors = _const_mismatch_errors(
        geometry_consts,
        (
            (
                "NATIVE_SHELL_DRAW_CORNER_RADIUS",
                contract_number_member(
                    context.chrome,
                    "native_shell_draw_corner_radius",
                    label="android_ui_contract.chrome",
                ),
            ),
            (
                "TOP_BEZEL_SETTINGS_TAP_HEIGHT",
                contract_number_member(
                    context.chrome,
                    "settings_strip_tap_height",
                    label="android_ui_contract.chrome",
                ),
            ),
            (
                "NON_SOFTKEY_VIEW_HEIGHT",
                contract_number_member(
                    context.chrome,
                    "non_softkey_view_height",
                    label="android_ui_contract.chrome",
                ),
            ),
            (
                "NATIVE_LCD_WINDOW_LEFT",
                contract_number_member(
                    context.native_lcd_window,
                    "left",
                    label="android_ui_contract.chrome.lcd_windows.native",
                ),
            ),
            (
                "NATIVE_LCD_WINDOW_TOP",
                contract_number_member(
                    context.native_lcd_window,
                    "top",
                    label="android_ui_contract.chrome.lcd_windows.native",
                ),
            ),
            (
                "NATIVE_LCD_WINDOW_WIDTH",
                contract_number_member(
                    context.native_lcd_window,
                    "width",
                    label="android_ui_contract.chrome.lcd_windows.native",
                ),
            ),
            (
                "NATIVE_LCD_WINDOW_HEIGHT",
                contract_number_member(
                    context.native_lcd_window,
                    "height",
                    label="android_ui_contract.chrome.lcd_windows.native",
                ),
            ),
            (
                "PIXEL_WIDTH",
                contract_number_member(
                    context.lcd_frame_buffer,
                    "pixel_width",
                    label="android_ui_contract.chrome.lcd_frame_buffer",
                ),
            ),
            (
                "PIXEL_HEIGHT",
                contract_number_member(
                    context.lcd_frame_buffer,
                    "pixel_height",
                    label="android_ui_contract.chrome.lcd_frame_buffer",
                ),
            ),
        ),
        kotlin_label="R47Geometry.kt",
    )
    errors.extend(
        _const_mismatch_errors(
            key_view_consts,
            (
                (
                    "MAIN_KEY_DRAW_CORNER_RADIUS",
                    contract_number_member(
                        context.main_key_surface,
                        "draw_corner_radius",
                        label="android_ui_contract.key_surface.main_key",
                    ),
                ),
                (
                    "SOFTKEY_DRAW_CORNER_RADIUS",
                    contract_number_member(
                        contract_mapping_member(
                            context.key_surface,
                            "softkey",
                            label="android_ui_contract.key_surface",
                        ),
                        "draw_corner_radius",
                        label="android_ui_contract.key_surface.softkey",
                    ),
                ),
                (
                    "MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA",
                    contract_number_member(
                        context.main_key_surface,
                        "painted_body_width_bonus",
                        label="android_ui_contract.key_surface.main_key",
                    ),
                ),
                (
                    "DEFAULT_PRIMARY_LEGEND_TEXT_SIZE",
                    contract_number_member(
                        context.first_label_text_sizes,
                        "default",
                        label=(
                            "android_ui_contract.label_layout.primary_legend.text_sizes"
                        ),
                    ),
                ),
                (
                    "NUMERIC_PRIMARY_LEGEND_TEXT_SIZE",
                    contract_number_member(
                        context.first_label_text_sizes,
                        "numeric",
                        label=(
                            "android_ui_contract.label_layout.primary_legend.text_sizes"
                        ),
                    ),
                ),
                (
                    "SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE",
                    contract_number_member(
                        context.first_label_text_sizes,
                        "shifted",
                        label=(
                            "android_ui_contract.label_layout.primary_legend.text_sizes"
                        ),
                    ),
                ),
                (
                    "TOP_F_G_LABEL_TEXT_SIZE",
                    contract_number_member(
                        context.second_label,
                        "text_size",
                        label="android_ui_contract.label_layout.top_f_legend",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_HORIZONTAL_GAP",
                    contract_number_member(
                        context.second_label,
                        "horizontal_gap",
                        label="android_ui_contract.label_layout.top_f_legend",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_VERTICAL_LIFT",
                    contract_number_member(
                        context.second_label,
                        "vertical_lift",
                        label="android_ui_contract.label_layout.top_f_legend",
                    ),
                ),
                (
                    "FOURTH_LABEL_TEXT_SIZE",
                    contract_number_member(
                        context.fourth_label,
                        "text_size",
                        label=(
                            "android_ui_contract.label_layout.right_side_letter_legend"
                        ),
                    ),
                ),
                (
                    "FOURTH_LABEL_X_OFFSET_FROM_MAIN_KEY_BODY_RIGHT",
                    contract_number_member(
                        context.fourth_label,
                        "x_offset_from_main_key_body_right",
                        label=(
                            "android_ui_contract.label_layout.right_side_letter_legend"
                        ),
                    ),
                ),
                (
                    "FOURTH_LABEL_Y_OFFSET_FROM_MAIN_KEY_BODY_TOP",
                    contract_number_member(
                        context.fourth_label,
                        "y_offset_from_main_key_body_top",
                        label=(
                            "android_ui_contract.label_layout.right_side_letter_legend"
                        ),
                    ),
                ),
                (
                    "TOP_F_G_LABEL_MAX_SHIFT_FRACTION",
                    contract_number_member(
                        context.top_label_solver,
                        "max_shift_fraction",
                        label="android_ui_contract.top_label_solver",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_MIN_SCALE",
                    contract_number_member(
                        context.top_label_solver,
                        "min_scale",
                        label="android_ui_contract.top_label_solver",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_SCALE_STEP",
                    contract_number_member(
                        context.top_label_solver,
                        "scale_step",
                        label="android_ui_contract.top_label_solver",
                    ),
                ),
                (
                    "MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW",
                    contract_number_member(
                        context.main_key_surface,
                        "painted_body_height",
                        label="android_ui_contract.key_surface.main_key",
                    )
                    / contract_number_member(
                        context.chrome,
                        "non_softkey_view_height",
                        label="android_ui_contract.chrome",
                    ),
                ),
                (
                    "STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION",
                    context.standard_right_strip_width / context.standard_pitch,
                ),
                (
                    "MATRIX_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION",
                    context.matrix_right_strip_width / context.matrix_pitch,
                ),
            ),
            kotlin_label="CalculatorKeyView.kt",
        ),
    )
    return errors


def _split_android_ui_contract_errors(
    physical_root: dict[str, object],
    android_contract: dict[str, object],
    entries: list[RawEntry],
) -> list[str]:
    context = _build_split_android_ui_contract_context(
        physical_root,
        android_contract,
        entries,
    )
    errors = _split_android_ui_metadata_errors(context)
    errors.extend(_split_android_ui_layout_errors(context))
    errors.extend(_split_android_ui_constant_errors(context))
    return errors


def _android_app_contract_errors(root: dict[str, object]) -> list[str]:
    errors: list[str] = []
    reference_frame = contract_mapping_member(
        root,
        "reference_frame",
        label="geometry document",
    )
    reference_width = contract_number_member(
        reference_frame,
        "width",
        label="reference_frame",
    )
    android_contract = contract_mapping_member(
        root,
        "android_app_contract",
        label="geometry document",
    )
    chrome = contract_mapping_member(
        android_contract,
        "chrome",
        label="android_app_contract",
    )
    labels = contract_mapping_member(
        android_contract,
        "labels",
        label="android_app_contract",
    )
    lcd_window = contract_mapping_member(
        chrome,
        "lcd_window",
        label="android_app_contract.chrome",
    )
    lcd_frame_buffer = contract_mapping_member(
        chrome,
        "lcd_frame_buffer",
        label="android_app_contract.chrome",
    )
    first_label = contract_mapping_member(
        labels,
        "first_label_primary",
        label="android_app_contract.labels",
    )
    first_label_text_sizes = contract_mapping_member(
        first_label,
        "text_sizes",
        label="android_app_contract.labels.first_label_primary",
    )
    second_label = contract_mapping_member(
        labels,
        "second_label_f",
        label="android_app_contract.labels",
    )
    third_label = contract_mapping_member(
        labels,
        "third_label_g",
        label="android_app_contract.labels",
    )
    fourth_label = contract_mapping_member(
        labels,
        "fourth_label_right_side",
        label="android_app_contract.labels",
    )
    top_label_solver = contract_mapping_member(
        labels,
        "top_label_solver",
        label="android_app_contract.labels",
    )

    if (
        contract_string_member(
            android_contract,
            "coordinate_space",
            label="android_app_contract",
        )
        != "logical_canvas"
    ):
        errors.append(
            "ERROR [android_app_contract] coordinate_space must be logical_canvas",
        )

    if contract_number_member(
        lcd_window,
        "top",
        label="android_app_contract.chrome.lcd_window",
    ) != contract_number_member(
        chrome,
        "settings_strip_tap_height",
        label="android_app_contract.chrome",
    ):
        errors.append(
            (
                "ERROR [android_app_contract.chrome] lcd_window.top must equal "
                "settings_strip_tap_height"
            ),
        )

    lcd_window_left = contract_number_member(
        lcd_window,
        "left",
        label="android_app_contract.chrome.lcd_window",
    )
    lcd_window_width = contract_number_member(
        lcd_window,
        "width",
        label="android_app_contract.chrome.lcd_window",
    )
    if (
        abs((2.0 * lcd_window_left) + lcd_window_width - reference_width)
        > _FLOAT_TOLERANCE
    ):
        errors.append(
            (
                "ERROR [android_app_contract.chrome] lcd_window must stay "
                "horizontally centered in the logical canvas"
            ),
        )

    if contract_number_member(
        second_label,
        "text_size",
        label="android_app_contract.labels.second_label_f",
    ) != contract_number_member(
        third_label,
        "text_size",
        label="android_app_contract.labels.third_label_g",
    ):
        errors.append(
            (
                "ERROR [android_app_contract.labels] second_label_f and "
                "third_label_g must share one text size"
            ),
        )

    if contract_number_member(
        second_label,
        "horizontal_gap",
        label="android_app_contract.labels.second_label_f",
    ) != contract_number_member(
        third_label,
        "horizontal_gap",
        label="android_app_contract.labels.third_label_g",
    ):
        errors.append(
            (
                "ERROR [android_app_contract.labels] second_label_f and "
                "third_label_g must share one horizontal gap"
            ),
        )

    if contract_number_member(
        second_label,
        "vertical_lift",
        label="android_app_contract.labels.second_label_f",
    ) != contract_number_member(
        third_label,
        "vertical_lift",
        label="android_app_contract.labels.third_label_g",
    ):
        errors.append(
            (
                "ERROR [android_app_contract.labels] second_label_f and "
                "third_label_g must share one vertical lift"
            ),
        )

    geometry_consts = parse_kotlin_const_values_from_paths([_KOTLIN_GEOMETRY_PATH])
    key_view_consts = parse_kotlin_const_values_from_paths(
        [
            _KOTLIN_GEOMETRY_PATH,
            _KOTLIN_KEYPAD_POLICY_PATH,
            _KOTLIN_KEY_VIEW_PATH,
        ],
    )
    errors.extend(
        _const_mismatch_errors(
            geometry_consts,
            (
                (
                    "NATIVE_SHELL_DRAW_CORNER_RADIUS",
                    contract_number_member(
                        chrome,
                        "native_shell_draw_corner_radius",
                        label="android_app_contract.chrome",
                    ),
                ),
                (
                    "TOP_BEZEL_SETTINGS_TAP_HEIGHT",
                    contract_number_member(
                        chrome,
                        "settings_strip_tap_height",
                        label="android_app_contract.chrome",
                    ),
                ),
                (
                    "NON_SOFTKEY_VIEW_HEIGHT",
                    contract_number_member(
                        chrome,
                        "non_softkey_view_height",
                        label="android_app_contract.chrome",
                    ),
                ),
                (
                    "LCD_WINDOW_LEFT",
                    contract_number_member(
                        lcd_window,
                        "left",
                        label="android_app_contract.chrome.lcd_window",
                    ),
                ),
                (
                    "LCD_WINDOW_TOP",
                    contract_number_member(
                        lcd_window,
                        "top",
                        label="android_app_contract.chrome.lcd_window",
                    ),
                ),
                (
                    "LCD_WINDOW_WIDTH",
                    contract_number_member(
                        lcd_window,
                        "width",
                        label="android_app_contract.chrome.lcd_window",
                    ),
                ),
                (
                    "LCD_WINDOW_HEIGHT",
                    contract_number_member(
                        lcd_window,
                        "height",
                        label="android_app_contract.chrome.lcd_window",
                    ),
                ),
                (
                    "PIXEL_WIDTH",
                    contract_number_member(
                        lcd_frame_buffer,
                        "pixel_width",
                        label="android_app_contract.chrome.lcd_frame_buffer",
                    ),
                ),
                (
                    "PIXEL_HEIGHT",
                    contract_number_member(
                        lcd_frame_buffer,
                        "pixel_height",
                        label="android_app_contract.chrome.lcd_frame_buffer",
                    ),
                ),
            ),
            kotlin_label="R47Geometry.kt",
        ),
    )
    errors.extend(
        _const_mismatch_errors(
            key_view_consts,
            (
                (
                    "MAIN_KEY_DRAW_CORNER_RADIUS",
                    contract_number_member(
                        labels,
                        "main_key_draw_corner_radius",
                        label="android_app_contract.labels",
                    ),
                ),
                (
                    "SOFTKEY_DRAW_CORNER_RADIUS",
                    contract_number_member(
                        labels,
                        "softkey_draw_corner_radius",
                        label="android_app_contract.labels",
                    ),
                ),
                (
                    "MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA",
                    contract_number_member(
                        labels,
                        "main_key_body_optical_width_delta",
                        label="android_app_contract.labels",
                    ),
                ),
                (
                    "DEFAULT_PRIMARY_LEGEND_TEXT_SIZE",
                    contract_number_member(
                        first_label_text_sizes,
                        "default",
                        label="android_app_contract.labels.first_label_primary.text_sizes",
                    ),
                ),
                (
                    "NUMERIC_PRIMARY_LEGEND_TEXT_SIZE",
                    contract_number_member(
                        first_label_text_sizes,
                        "numeric",
                        label="android_app_contract.labels.first_label_primary.text_sizes",
                    ),
                ),
                (
                    "SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE",
                    contract_number_member(
                        first_label_text_sizes,
                        "shifted",
                        label="android_app_contract.labels.first_label_primary.text_sizes",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_TEXT_SIZE",
                    contract_number_member(
                        second_label,
                        "text_size",
                        label="android_app_contract.labels.second_label_f",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_HORIZONTAL_GAP",
                    contract_number_member(
                        second_label,
                        "horizontal_gap",
                        label="android_app_contract.labels.second_label_f",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_VERTICAL_LIFT",
                    contract_number_member(
                        second_label,
                        "vertical_lift",
                        label="android_app_contract.labels.second_label_f",
                    ),
                ),
                (
                    "FOURTH_LABEL_TEXT_SIZE",
                    contract_number_member(
                        fourth_label,
                        "text_size",
                        label="android_app_contract.labels.fourth_label_right_side",
                    ),
                ),
                (
                    "FOURTH_LABEL_X_OFFSET_FROM_MAIN_KEY_BODY_RIGHT",
                    contract_number_member(
                        fourth_label,
                        "x_offset_from_main_key_body_right",
                        label="android_app_contract.labels.fourth_label_right_side",
                    ),
                ),
                (
                    "FOURTH_LABEL_Y_OFFSET_FROM_MAIN_KEY_BODY_TOP",
                    contract_number_member(
                        fourth_label,
                        "y_offset_from_main_key_body_top",
                        label="android_app_contract.labels.fourth_label_right_side",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_MAX_SHIFT_FRACTION",
                    contract_number_member(
                        top_label_solver,
                        "max_shift_fraction",
                        label="android_app_contract.labels.top_label_solver",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_MIN_SCALE",
                    contract_number_member(
                        top_label_solver,
                        "min_scale",
                        label="android_app_contract.labels.top_label_solver",
                    ),
                ),
                (
                    "TOP_F_G_LABEL_SCALE_STEP",
                    contract_number_member(
                        top_label_solver,
                        "scale_step",
                        label="android_app_contract.labels.top_label_solver",
                    ),
                ),
                (
                    "MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW",
                    contract_number_member(
                        labels,
                        "main_key_body_height_fraction_of_view",
                        label="android_app_contract.labels",
                    ),
                ),
                (
                    "STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION",
                    contract_number_member(
                        labels,
                        "standard_key_fourth_label_strip_width_fraction",
                        label="android_app_contract.labels",
                    ),
                ),
                (
                    "MATRIX_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION",
                    contract_number_member(
                        labels,
                        "matrix_key_fourth_label_strip_width_fraction",
                        label="android_app_contract.labels",
                    ),
                ),
            ),
            kotlin_label="CalculatorKeyView.kt",
        ),
    )
    return errors


def _const_mismatch_errors(
    kotlin_values: dict[str, float],
    expected_items: tuple[tuple[str, float], ...],
    *,
    kotlin_label: str,
) -> list[str]:
    errors: list[str] = []
    for constant_name, expected_value in expected_items:
        actual_value = kotlin_values.get(constant_name)
        if actual_value is None:
            errors.append(
                f"ERROR [{kotlin_label}] missing Kotlin constant {constant_name}",
            )
            continue
        if abs(actual_value - expected_value) > _FLOAT_TOLERANCE:
            errors.append(
                "ERROR "
                f"[{kotlin_label}] {constant_name}={actual_value}, "
                f"expected {expected_value}",
            )
    return errors


def build_summary(entries: list[RawEntry]) -> list[str]:
    """Build the human-readable summary printed before any errors."""
    families = sorted({entry.family for entry in entries})
    return [
        "Geometry consistency report",
        "",
        f"Parsed entries: {len(entries)}",
        f"Families seen: {', '.join(families)}",
        "",
    ]


def run_cli(
    *,
    argv: Sequence[str] | None = None,
    description: str,
    data_help: str,
    default_data_path: Path | None,
) -> int:
    """Run the geometry-validator CLI with an optional default dataset path."""
    parser = argparse.ArgumentParser(description=description)
    if default_data_path is None:
        parser.add_argument(
            "data",
            type=Path,
            help=data_help,
        )
    else:
        parser.add_argument(
            "data",
            nargs="?",
            default=default_data_path,
            type=Path,
            help=data_help,
        )
    args = parser.parse_args(argv)

    root = load_contract_document(args.data)
    entries = load_raw_entries_from_document(root)
    errors = analyze(entries)
    if "android_app_contract" in root:
        errors.extend(_android_app_contract_errors(root))
    elif args.data.resolve() == R47_PHYSICAL_GEOMETRY_DATA_PATH.resolve():
        errors.extend(
            _split_android_ui_contract_errors(
                root,
                load_android_ui_contract(R47_ANDROID_UI_CONTRACT_PATH),
                entries,
            ),
        )

    _write_lines(build_summary(entries))

    if errors:
        _write_lines(["Detected data issues:"])
        _write_lines([f"- {line}" for line in errors])
        return 1

    _write_lines(["No geometry consistency errors found."])
    return 0


def _write_lines(lines: Iterable[str]) -> None:
    for line in lines:
        sys.stdout.write(f"{line}\n")


def main(argv: Sequence[str] | None = None) -> int:
    """Validate the canonical checked-in R47 geometry dataset."""
    return run_cli(
        argv=argv,
        description=(
            "Validate the canonical R47 keypad geometry data recorded in "
            "scripts/r47_contracts/data/r47_physical_geometry.json"
        ),
        data_help="Path to the geometry data JSON file",
        default_data_path=R47_PHYSICAL_GEOMETRY_DATA_PATH,
    )


if __name__ == "__main__":
    raise SystemExit(main())
