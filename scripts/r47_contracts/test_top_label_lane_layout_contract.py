"""Exercise the packaged top-label lane solver against contract fixtures."""

from __future__ import annotations

import json
import unittest
from math import isfinite
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Sequence

from r47_contracts._repo_paths import KEYPAD_FIXTURES_ROOT
from r47_contracts.derive_top_label_lane_layout import (
    REFERENCE_LANE_GEOMETRY,
    LaneGroupInput,
    LaneGroupPlacement,
    LaneLayoutPolicy,
    _LaneGroupState,
    _resolve_positions,
    build_reference_lane_geometry,
    build_top_label_lane_layout_payload,
    make_lane_group_from_labels,
    make_lane_group_from_width,
    solve_lane,
)

_FLOAT_TOLERANCE = 1e-6
_STANDARD_ROW_KEY_COUNT = 6
_COMPACT_ROW_KEY_COUNT = 5


def _assert_true(condition: object) -> None:
    if not condition:
        raise AssertionError


def _assert_almost_equal(actual: float, expected: float, *, places: int = 6) -> None:
    tolerance = 10 ** (-places)
    _assert_true(abs(actual - expected) <= tolerance)


def _require_mapping(value: object, *, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        message = f"Expected {label} to be a mapping, got {value!r}"
        raise TypeError(message)
    return {
        key: nested_value for key, nested_value in value.items() if isinstance(key, str)
    }


def _require_list(value: object, *, label: str) -> list[object]:
    if not isinstance(value, list):
        message = f"Expected {label} to be a list, got {value!r}"
        raise TypeError(message)
    return list(value)


def _require_mapping_list(value: object, *, label: str) -> list[dict[str, object]]:
    raw_items = _require_list(value, label=label)
    return [
        _require_mapping(item, label=f"{label}[{index}]")
        for index, item in enumerate(raw_items)
    ]


def _require_number(value: object, *, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        message = f"Expected {label} to be numeric, got {value!r}"
        raise TypeError(message)
    return float(value)


def _number_member(mapping: dict[str, object], key: str, *, label: str) -> float:
    return _require_number(mapping[key], label=f"{label}.{key}")


def _seeded_uniform_values(
    *,
    seed: int,
    count: int,
    low: float,
    high: float,
) -> list[float]:
    state = seed
    values: list[float] = []
    modulus = 2**31
    for _ in range(count):
        state = (1103515245 * state + 12345) % modulus
        ratio = state / float(modulus - 1)
        values.append(low + (high - low) * ratio)
    return values


class TopLabelLaneLayoutTest(unittest.TestCase):
    """Verify the packaged top-label lane solver against contract fixtures."""

    @classmethod
    def setUpClass(cls) -> None:
        """Load shared fixtures for the test class."""
        cls.policy = LaneLayoutPolicy.default()
        cls.corridor_extension = cls.policy.intra_group_gap * 5.0

    def _assert_gap_at_least(
        self,
        placements: Sequence[LaneGroupPlacement],
        lane_groups: Sequence[LaneGroupInput],
        index: int,
        required_gap: float,
    ) -> None:
        left_placement = placements[index]
        right_placement = placements[index + 1]
        left_group = lane_groups[index]
        right_group = lane_groups[index + 1]
        left_width = (
            left_group.f_text_width * left_placement.f_scale
            + left_group.gap_width
            + left_group.g_text_width * left_placement.g_scale
        )
        right_width = (
            right_group.f_text_width * right_placement.f_scale
            + right_group.gap_width
            + right_group.g_text_width * right_placement.g_scale
        )
        left_center = left_group.preferred_center_x + left_placement.center_shift
        right_center = right_group.preferred_center_x + right_placement.center_shift
        gap = (right_center - right_width / 2.0) - (left_center + left_width / 2.0)
        _assert_true(gap + _FLOAT_TOLERANCE >= required_gap)

    def _assert_neighbor_border_gap_respected(
        self,
        placements: Sequence[LaneGroupPlacement],
        lane_groups: Sequence[LaneGroupInput],
        index: int,
    ) -> None:
        left_placement = placements[index]
        right_placement = placements[index + 1]
        left_group = lane_groups[index]
        right_group = lane_groups[index + 1]
        left_width = (
            left_group.f_text_width * left_placement.f_scale
            + left_group.gap_width
            + left_group.g_text_width * left_placement.g_scale
        )
        right_width = (
            right_group.f_text_width * right_placement.f_scale
            + right_group.gap_width
            + right_group.g_text_width * right_placement.g_scale
        )
        left_center = left_group.preferred_center_x + left_placement.center_shift
        right_center = right_group.preferred_center_x + right_placement.center_shift
        left_right = left_center + left_width / 2.0
        right_left = right_center - right_width / 2.0
        right_neighbor_left_border = (
            right_group.preferred_center_x
            - right_group.body_width / 2.0
            + self.corridor_extension
        )
        left_neighbor_right_border = (
            left_group.preferred_center_x
            + left_group.body_width / 2.0
            - self.corridor_extension
        )
        _assert_true(left_right <= right_neighbor_left_border + _FLOAT_TOLERANCE)
        _assert_true(right_left + _FLOAT_TOLERANCE >= left_neighbor_right_border)

    def _assert_lane_resolved(
        self,
        placements: Sequence[LaneGroupPlacement],
        lane_groups: Sequence[LaneGroupInput],
    ) -> None:
        for index in range(len(lane_groups) - 1):
            self._assert_gap_at_least(
                placements,
                lane_groups,
                index,
                self.policy.inter_group_gap,
            )
            self._assert_neighbor_border_gap_respected(
                placements,
                lane_groups,
                index,
            )

    def _assert_screen_bounds_respected(
        self,
        placements: Sequence[LaneGroupPlacement],
        lane_groups: Sequence[LaneGroupInput],
    ) -> None:
        for placement, group in zip(placements, lane_groups, strict=True):
            total_width = (
                group.f_text_width * placement.f_scale
                + group.gap_width
                + group.g_text_width * placement.g_scale
            )
            center_x = group.preferred_center_x + placement.center_shift
            left = center_x - total_width / 2.0
            right = center_x + total_width / 2.0
            if isfinite(group.min_left_edge):
                _assert_true(left + _FLOAT_TOLERANCE >= group.min_left_edge)
            if isfinite(group.max_right_edge):
                _assert_true(right <= group.max_right_edge + _FLOAT_TOLERANCE)

    def _assert_group_scale_difference_at_most_one_step(
        self,
        placements: Sequence[LaneGroupPlacement],
        lane_groups: Sequence[LaneGroupInput],
    ) -> None:
        for placement, group in zip(placements, lane_groups, strict=True):
            if group.g_text_width <= _FLOAT_TOLERANCE:
                continue
            _assert_true(
                abs(placement.f_scale - placement.g_scale)
                <= self.policy.scale_step + _FLOAT_TOLERANCE,
            )

    def test_reference_lane_geometry_matches_expected_small_row_spacing(self) -> None:
        """Reference lane geometry matches expected small row spacing."""
        geometry = build_reference_lane_geometry(self.policy)
        row = geometry["SMALL_ROW_1"]
        _assert_true([slot["code"] for slot in row] == [1, 2, 3, 4, 5, 6])
        _assert_almost_equal(row[0]["preferred_center_x"], 231.5, places=6)
        _assert_almost_equal(
            row[1]["preferred_center_x"] - row[0]["preferred_center_x"],
            272.0,
            places=6,
        )

    def test_payload_exposes_three_stress_row_examples(self) -> None:
        """Payload exposes three stress row examples."""
        payload = build_top_label_lane_layout_payload()
        examples = _require_mapping(
            payload["example_stress_r47_rows"],
            label="example_stress_r47_rows",
        )

        function_row = _require_mapping(
            examples["function_row_6_keys_6_groups"],
            label="function_row_6_keys_6_groups",
        )
        function_slots = _require_mapping_list(
            function_row["slot_geometry"],
            label="function_row.slot_geometry",
        )
        function_inputs = _require_list(
            function_row["inputs"],
            label="function_row.inputs",
        )
        _assert_true(function_row["lane"] == "SMALL_ROW_1")
        _assert_true(len(function_slots) == _STANDARD_ROW_KEY_COUNT)
        _assert_true(len(function_inputs) == _STANDARD_ROW_KEY_COUNT)
        _assert_true(
            [
                _number_member(slot, "code", label="function_slot")
                for slot in function_slots
            ]
            == [1, 2, 3, 4, 5, 6],
        )
        _assert_true(
            all(
                abs(_number_member(slot, "body_width", label="function_slot") - 192.0)
                <= _FLOAT_TOLERANCE
                for slot in function_slots
            ),
        )
        _assert_almost_equal(
            _number_member(function_slots[0], "left", label="function_slot"),
            134.0,
            places=6,
        )
        _assert_almost_equal(
            _number_member(
                function_slots[1],
                "preferred_center_x",
                label="function_slot",
            )
            - _number_member(
                function_slots[0],
                "preferred_center_x",
                label="function_slot",
            ),
            272.0,
            places=6,
        )

        enter_row = _require_mapping(
            examples["enter_row_5_keys_5_groups"],
            label="enter_row_5_keys_5_groups",
        )
        enter_slots = _require_mapping_list(
            enter_row["slot_geometry"],
            label="enter_row.slot_geometry",
        )
        enter_inputs = _require_list(enter_row["inputs"], label="enter_row.inputs")
        _assert_true(enter_row["lane"] == "ENTER_ROW")
        _assert_true(len(enter_slots) == _COMPACT_ROW_KEY_COUNT)
        _assert_true(len(enter_inputs) == _COMPACT_ROW_KEY_COUNT)
        _assert_true(
            [_number_member(slot, "code", label="enter_slot") for slot in enter_slots]
            == [13, 14, 15, 16, 17],
        )
        _assert_true(
            _number_member(enter_slots[0], "body_width", label="enter_slot")
            > _number_member(enter_slots[1], "body_width", label="enter_slot"),
        )
        _assert_almost_equal(
            _number_member(enter_slots[0], "body_width", label="enter_slot"),
            462.0,
            places=6,
        )
        _assert_almost_equal(
            _number_member(enter_slots[1], "body_width", label="enter_slot"),
            192.0,
            places=6,
        )
        _assert_almost_equal(
            _number_member(enter_slots[1], "left", label="enter_slot"),
            678.0,
            places=6,
        )

        numeric_row = _require_mapping(
            examples["numeric_row_5_keys_5_groups"],
            label="numeric_row_5_keys_5_groups",
        )
        numeric_slots = _require_mapping_list(
            numeric_row["slot_geometry"],
            label="numeric_row.slot_geometry",
        )
        numeric_inputs = _require_list(
            numeric_row["inputs"],
            label="numeric_row.inputs",
        )
        _assert_true(numeric_row["lane"] == "MATRIX_ROW_1")
        _assert_true(len(numeric_slots) == _COMPACT_ROW_KEY_COUNT)
        _assert_true(len(numeric_inputs) == _COMPACT_ROW_KEY_COUNT)
        _assert_true(
            [
                _number_member(slot, "code", label="numeric_slot")
                for slot in numeric_slots
            ]
            == [18, 19, 20, 21, 22],
        )
        _assert_true(
            _number_member(numeric_slots[0], "body_width", label="numeric_slot")
            < _number_member(numeric_slots[1], "body_width", label="numeric_slot"),
        )
        _assert_almost_equal(
            _number_member(numeric_slots[0], "body_width", label="numeric_slot"),
            192.0,
            places=6,
        )
        _assert_almost_equal(
            _number_member(numeric_slots[1], "body_width", label="numeric_slot"),
            228.0,
            places=6,
        )
        _assert_almost_equal(
            _number_member(numeric_slots[1], "left", label="numeric_slot"),
            465.0,
            places=6,
        )
        _assert_almost_equal(
            _number_member(numeric_slots[2], "left", label="numeric_slot")
            - _number_member(numeric_slots[1], "left", label="numeric_slot"),
            331.0,
            places=6,
        )

    def test_default_keypad_fixture_preserves_centered_defaults(self) -> None:
        """Default keypad fixture preserves centered defaults."""
        fixture_path = KEYPAD_FIXTURES_ROOT / "default-keypad.json"
        fixture = json.loads(fixture_path.read_text())
        labels = fixture["labels"]
        labels_per_key = 5
        logical_width = 1820.0

        for slots in REFERENCE_LANE_GEOMETRY.values():
            lane_groups = []
            for index, slot in enumerate(slots):
                base = (slot.code - 1) * labels_per_key
                lane_groups.append(
                    make_lane_group_from_labels(
                        slot=slot,
                        f_label=labels[base + 1],
                        g_label=labels[base + 2],
                        policy=self.policy,
                        min_left_edge=0.0 if index == 0 else float("-inf"),
                        max_right_edge=logical_width
                        if index == len(slots) - 1
                        else float("inf"),
                    ),
                )

            placements = solve_lane(lane_groups, self.policy)

            for placement in placements:
                _assert_almost_equal(placement.center_shift, 0.0, places=6)
                _assert_true(placement.stagger_level == 0)
                _assert_almost_equal(placement.f_scale, 1.0, places=6)
                _assert_almost_equal(placement.g_scale, 1.0, places=6)

    def test_no_conflict_preserves_centered_defaults(self) -> None:
        """No conflict preserves centered defaults."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:3]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                total_width=180.0,
                policy=self.policy,
            )
            for slot in slots
        ]

        placements = solve_lane(lane_groups, self.policy)
        for placement in placements:
            _assert_almost_equal(placement.center_shift, 0.0, places=6)
            _assert_true(placement.stagger_level == 0)
            _assert_almost_equal(placement.f_scale, 1.0, places=6)
            _assert_almost_equal(placement.g_scale, 1.0, places=6)

    def test_outer_groups_stay_inside_smartphone_screen_edges(self) -> None:
        """Outer groups stay inside smartphone screen edges."""
        lane_groups = [
            make_lane_group_from_width(
                code=1,
                preferred_center_x=100.0,
                body_width=120.0,
                policy=self.policy,
                total_width=220.0,
                min_left_edge=0.0,
            ),
            make_lane_group_from_width(
                code=2,
                preferred_center_x=420.0,
                body_width=120.0,
                policy=self.policy,
                total_width=220.0,
                max_right_edge=520.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)

        self._assert_lane_resolved(placements, lane_groups)
        self._assert_screen_bounds_respected(placements, lane_groups)
        _assert_true(placements[0].center_shift > 0.0)
        _assert_true(placements[1].center_shift < 0.0)

    def test_centered_groups_allow_five_gap_corridor_when_intergap_respected(
        self,
    ) -> None:
        """Centered groups allow a five-gap corridor when intergap is respected."""
        lane_groups = [
            make_lane_group_from_width(
                code=1,
                preferred_center_x=100.0,
                body_width=240.0,
                policy=self.policy,
                total_width=200.0,
            ),
            make_lane_group_from_width(
                code=2,
                preferred_center_x=300.0,
                body_width=260.0,
                policy=self.policy,
                total_width=160.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)

        _assert_almost_equal(placements[0].center_shift, 0.0, places=6)
        _assert_almost_equal(placements[1].center_shift, 0.0, places=6)
        self._assert_gap_at_least(
            placements,
            lane_groups,
            0,
            self.policy.inter_group_gap,
        )
        self._assert_neighbor_border_gap_respected(placements, lane_groups, 0)

    def test_nudge_resolves_mild_neighbor_conflict_without_stagger(self) -> None:
        """Nudge resolves mild neighbor conflict without stagger."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:2]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                total_width=300.0,
                policy=self.policy,
            )
            for slot in slots
        ]

        placements = solve_lane(lane_groups, self.policy)
        _assert_true(all(abs(placement.center_shift) > 0.0 for placement in placements))
        _assert_true([placement.stagger_level for placement in placements] == [0, 0])
        _assert_true([placement.f_scale for placement in placements] == [1.0, 1.0])
        _assert_true([placement.g_scale for placement in placements] == [1.0, 1.0])
        self._assert_gap_at_least(
            placements,
            lane_groups,
            0,
            self.policy.inter_group_gap,
        )
        self._assert_neighbor_border_gap_respected(placements, lane_groups, 0)

    def test_local_offender_translation_can_fix_without_moving_neighbor(self) -> None:
        """Local offender translation can fix without moving neighbor."""
        lane_groups = [
            make_lane_group_from_width(
                code=1,
                preferred_center_x=100.0,
                body_width=240.0,
                policy=self.policy,
                f_text_width=120.0,
                g_text_width=120.0,
            ),
            make_lane_group_from_width(
                code=2,
                preferred_center_x=300.0,
                body_width=260.0,
                policy=self.policy,
                f_text_width=40.0,
                g_text_width=20.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)
        _assert_true(placements[0].center_shift < 0.0)
        _assert_almost_equal(placements[1].center_shift, 0.0, places=6)
        _assert_true([placement.f_scale for placement in placements] == [1.0, 1.0])
        _assert_true([placement.g_scale for placement in placements] == [1.0, 1.0])
        self._assert_gap_at_least(
            placements,
            lane_groups,
            0,
            self.policy.inter_group_gap,
        )
        self._assert_neighbor_border_gap_respected(placements, lane_groups, 0)

    def test_neighbor_corridor_with_five_gap_extension_is_enforced(self) -> None:
        """Neighbor corridor with five gap extension is enforced."""
        lane_groups = [
            make_lane_group_from_width(
                code=1,
                preferred_center_x=100.0,
                body_width=240.0,
                policy=self.policy,
                f_text_width=120.0,
                g_text_width=120.0,
            ),
            make_lane_group_from_width(
                code=2,
                preferred_center_x=300.0,
                body_width=260.0,
                policy=self.policy,
                f_text_width=40.0,
                g_text_width=20.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)
        _assert_true(placements[0].center_shift < 0.0)
        _assert_almost_equal(placements[1].center_shift, 0.0, places=6)
        self._assert_neighbor_border_gap_respected(placements, lane_groups, 0)

    def test_scaled_local_offender_does_not_drag_distant_groups_off_center(
        self,
    ) -> None:
        """Scaled local offender does not drag distant groups off center."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:6]
        widths = [288.11, 172.8, 214.38, 240.01, 317.95, 221.12]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                policy=self.policy,
                total_width=width,
            )
            for slot, width in zip(slots, widths, strict=True)
        ]

        placements = solve_lane(lane_groups, self.policy)

        for index in (0, 1, 2, 3, 5):
            _assert_almost_equal(placements[index].center_shift, 0.0, places=6)

        _assert_true(abs(placements[4].center_shift) > 0.0)
        _assert_true(placements[4].f_scale < 1.0 or placements[4].g_scale < 1.0)

        for index in range(len(lane_groups) - 1):
            self._assert_gap_at_least(
                placements,
                lane_groups,
                index,
                self.policy.inter_group_gap,
            )
            self._assert_neighbor_border_gap_respected(placements, lane_groups, index)

    def test_scale_down_resolves_when_nudge_budget_runs_out(self) -> None:
        """Scale down resolves when nudge budget runs out."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:2]
        lane_groups = [
            make_lane_group_from_width(
                code=slots[0].code,
                preferred_center_x=slots[0].preferred_center_x,
                body_width=slots[0].body_width,
                policy=self.policy,
                f_text_width=130.0,
                g_text_width=168.0,
            ),
            make_lane_group_from_width(
                code=slots[1].code,
                preferred_center_x=slots[1].preferred_center_x,
                body_width=slots[1].body_width,
                policy=self.policy,
                f_text_width=130.0,
                g_text_width=178.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)
        _assert_true([placement.stagger_level for placement in placements] == [0, 0])
        _assert_almost_equal(placements[0].f_scale, 1.0, places=6)
        _assert_almost_equal(placements[0].g_scale, 1.0, places=6)
        _assert_almost_equal(placements[1].f_scale, 1.0, places=6)
        _assert_almost_equal(
            placements[1].g_scale,
            1.0 - self.policy.scale_step,
            places=6,
        )
        _assert_true(
            abs(placements[0].center_shift)
            <= lane_groups[0].max_shift + _FLOAT_TOLERANCE,
        )
        _assert_true(
            abs(placements[1].center_shift)
            <= lane_groups[1].max_shift + _FLOAT_TOLERANCE,
        )
        self._assert_gap_at_least(
            placements,
            lane_groups,
            0,
            self.policy.inter_group_gap,
        )
        self._assert_neighbor_border_gap_respected(placements, lane_groups, 0)
        self._assert_group_scale_difference_at_most_one_step(placements, lane_groups)

    def test_scale_down_only_hits_the_most_offending_later_label(self) -> None:
        """Scale down only hits the most offending later label."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:2]
        lane_groups = [
            make_lane_group_from_width(
                code=slots[0].code,
                preferred_center_x=slots[0].preferred_center_x,
                body_width=slots[0].body_width,
                policy=self.policy,
                f_text_width=140.0,
                g_text_width=158.0,
            ),
            make_lane_group_from_width(
                code=slots[1].code,
                preferred_center_x=slots[1].preferred_center_x,
                body_width=slots[1].body_width,
                policy=self.policy,
                f_text_width=140.0,
                g_text_width=166.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)
        _assert_true([placement.stagger_level for placement in placements] == [0, 0])
        _assert_almost_equal(placements[0].f_scale, 1.0, places=6)
        _assert_almost_equal(placements[0].g_scale, 1.0, places=6)
        _assert_almost_equal(placements[1].f_scale, 1.0, places=6)
        _assert_almost_equal(
            placements[1].g_scale,
            1.0 - self.policy.scale_step,
            places=6,
        )
        _assert_true(placements[1].g_scale + _FLOAT_TOLERANCE >= self.policy.min_scale)
        self._assert_gap_at_least(
            placements,
            lane_groups,
            0,
            self.policy.inter_group_gap,
        )
        self._assert_neighbor_border_gap_respected(placements, lane_groups, 0)
        self._assert_group_scale_difference_at_most_one_step(placements, lane_groups)

    def test_second_scale_step_hits_other_label_on_same_group(self) -> None:
        """Second scale step hits other label on same group."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:2]
        lane_groups = [
            make_lane_group_from_width(
                code=slots[0].code,
                preferred_center_x=slots[0].preferred_center_x,
                body_width=slots[0].body_width,
                policy=self.policy,
                f_text_width=140.0,
                g_text_width=158.0,
            ),
            make_lane_group_from_width(
                code=slots[1].code,
                preferred_center_x=slots[1].preferred_center_x,
                body_width=slots[1].body_width,
                policy=self.policy,
                f_text_width=140.0,
                g_text_width=172.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)
        _assert_true([placement.stagger_level for placement in placements] == [0, 0])
        _assert_almost_equal(placements[0].f_scale, 1.0, places=6)
        _assert_almost_equal(placements[0].g_scale, 1.0, places=6)
        _assert_almost_equal(
            placements[1].f_scale,
            1.0 - self.policy.scale_step,
            places=6,
        )
        _assert_almost_equal(
            placements[1].g_scale,
            1.0 - self.policy.scale_step,
            places=6,
        )
        self._assert_gap_at_least(
            placements,
            lane_groups,
            0,
            self.policy.inter_group_gap,
        )
        self._assert_neighbor_border_gap_respected(placements, lane_groups, 0)
        self._assert_group_scale_difference_at_most_one_step(placements, lane_groups)

    def test_fully_scaled_group_can_force_colliding_neighbor_to_scale(self) -> None:
        """Fully scaled group can force colliding neighbor to scale."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:3]
        widths = [334.1299043744574, 240.1154789731775, 317.5454830764003]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                policy=self.policy,
                total_width=width,
            )
            for slot, width in zip(slots, widths, strict=True)
        ]

        placements = solve_lane(lane_groups, self.policy)

        _assert_almost_equal(placements[2].f_scale, self.policy.min_scale, places=6)
        _assert_almost_equal(placements[2].g_scale, self.policy.min_scale, places=6)
        _assert_true(placements[0].f_scale < 1.0)
        _assert_true(placements[0].g_scale < 1.0)
        _assert_almost_equal(placements[1].f_scale, 1.0, places=6)
        _assert_almost_equal(placements[1].g_scale, 1.0, places=6)
        _assert_true(abs(placements[0].center_shift) > 0.0)

        for index in range(len(lane_groups) - 1):
            self._assert_gap_at_least(
                placements,
                lane_groups,
                index,
                self.policy.inter_group_gap,
            )
            self._assert_neighbor_border_gap_respected(placements, lane_groups, index)

    def test_pathological_case_tries_translation_before_extra_scaledown(self) -> None:
        """Pathological case tries translation before extra scaledown."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:2]
        lane_groups = [
            make_lane_group_from_width(
                code=slots[0].code,
                preferred_center_x=slots[0].preferred_center_x,
                body_width=slots[0].body_width,
                policy=self.policy,
                f_text_width=160.0,
                g_text_width=210.0,
            ),
            make_lane_group_from_width(
                code=slots[1].code,
                preferred_center_x=slots[1].preferred_center_x,
                body_width=slots[1].body_width,
                policy=self.policy,
                f_text_width=220.0,
                g_text_width=280.0,
            ),
        ]

        placements = solve_lane(lane_groups, self.policy)
        self._assert_lane_resolved(placements, lane_groups)
        self._assert_group_scale_difference_at_most_one_step(placements, lane_groups)
        _assert_true(
            any(
                abs(placement.center_shift) > group.max_shift + _FLOAT_TOLERANCE
                for placement, group in zip(placements, lane_groups, strict=True)
            )
            or any(
                placement.f_scale < self.policy.min_scale - _FLOAT_TOLERANCE
                or placement.g_scale < self.policy.min_scale - _FLOAT_TOLERANCE
                for placement in placements
            ),
        )

    def test_dense_six_group_row_avoids_overlap(self) -> None:
        """Dense six group row avoids overlap."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:6]
        widths = [
            377.0977451783499,
            403.61949568145076,
            420.34708833495137,
            315.7074055862847,
            302.4294243154402,
            364.1772966412449,
        ]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                policy=self.policy,
                total_width=width,
            )
            for slot, width in zip(slots, widths, strict=True)
        ]

        placements = solve_lane(lane_groups, self.policy)

        self._assert_lane_resolved(placements, lane_groups)
        _assert_true(any(abs(placement.center_shift) > 0.0 for placement in placements))
        self._assert_group_scale_difference_at_most_one_step(placements, lane_groups)

    def test_dense_five_group_row_avoids_overlap(self) -> None:
        """Dense five group row avoids overlap."""
        slots = REFERENCE_LANE_GEOMETRY["ENTER_ROW"][:5]
        widths = [
            355.50468853857313,
            369.6998049978352,
            490.8235093497424,
            377.73904504965674,
            322.9182501317339,
        ]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                policy=self.policy,
                total_width=width,
            )
            for slot, width in zip(slots, widths, strict=True)
        ]

        placements = solve_lane(lane_groups, self.policy)

        self._assert_lane_resolved(placements, lane_groups)
        _assert_true(any(abs(placement.center_shift) > 0.0 for placement in placements))
        self._assert_group_scale_difference_at_most_one_step(placements, lane_groups)

    def test_dense_row_group_scales_never_differ_by_more_than_one_step(self) -> None:
        """Dense row group scales never differ by more than one step."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:6]
        widths = [
            484.0945594183823,
            409.7175524750849,
            497.7579250663201,
            364.8805576280922,
            362.02007813974353,
            511.26802240600284,
        ]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                policy=self.policy,
                total_width=width,
            )
            for slot, width in zip(slots, widths, strict=True)
        ]

        placements = solve_lane(lane_groups, self.policy)

        self._assert_lane_resolved(placements, lane_groups)
        self._assert_group_scale_difference_at_most_one_step(placements, lane_groups)

    def test_dense_six_group_seeded_sweep_has_no_overlap(self) -> None:
        """Dense six group seeded sweep has no overlap."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:6]

        for _ in range(64):
            widths = _seeded_uniform_values(
                seed=20260509 + _,
                count=len(slots),
                low=220.0,
                high=430.0,
            )
            lane_groups = [
                make_lane_group_from_width(
                    code=slot.code,
                    preferred_center_x=slot.preferred_center_x,
                    body_width=slot.body_width,
                    policy=self.policy,
                    total_width=width,
                )
                for slot, width in zip(slots, widths, strict=True)
            ]
            placements = solve_lane(lane_groups, self.policy)
            self._assert_lane_resolved(placements, lane_groups)
            self._assert_group_scale_difference_at_most_one_step(
                placements,
                lane_groups,
            )

    def test_dense_five_group_seeded_sweep_has_no_overlap(self) -> None:
        """Dense five group seeded sweep has no overlap."""
        slots = REFERENCE_LANE_GEOMETRY["ENTER_ROW"][:5]

        for _ in range(64):
            widths = _seeded_uniform_values(
                seed=20260509 + 77 + _,
                count=len(slots),
                low=260.0,
                high=520.0,
            )
            lane_groups = [
                make_lane_group_from_width(
                    code=slot.code,
                    preferred_center_x=slot.preferred_center_x,
                    body_width=slot.body_width,
                    policy=self.policy,
                    total_width=width,
                )
                for slot, width in zip(slots, widths, strict=True)
            ]
            placements = solve_lane(lane_groups, self.policy)
            self._assert_lane_resolved(placements, lane_groups)
            self._assert_group_scale_difference_at_most_one_step(
                placements,
                lane_groups,
            )

    def test_resolve_positions_keeps_existing_horizontal_nudges(self) -> None:
        """Resolve positions keeps existing horizontal nudges."""
        slots = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:2]
        lane_groups = [
            make_lane_group_from_width(
                code=slot.code,
                preferred_center_x=slot.preferred_center_x,
                body_width=slot.body_width,
                total_width=300.0,
                policy=self.policy,
            )
            for slot in slots
        ]

        states = [_LaneGroupState(group=group) for group in lane_groups]
        states[0].center_x = (
            lane_groups[0].preferred_center_x - lane_groups[0].max_shift
        )
        states[1].center_x = (
            lane_groups[1].preferred_center_x + lane_groups[1].max_shift
        )
        before = [state.center_x for state in states]

        conflicts = _resolve_positions(states, self.policy)

        _assert_true(conflicts == [])
        _assert_almost_equal(states[0].center_x, before[0], places=6)
        _assert_almost_equal(states[1].center_x, before[1], places=6)


if __name__ == "__main__":
    unittest.main()
