"""Derive top-label lane placements and stress payloads for the R47 keypad."""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from functools import lru_cache
from math import isfinite
from typing import Required, TypedDict, Unpack

from fontTools.ttLib import TTFont

from r47_contracts.derive_key_label_geometry import build_key_label_geometry_payload
from r47_contracts.runtime_fonts import resolve_runtime_font_path

STANDARD_FONT_PATH = resolve_runtime_font_path("C47__StandardFont.ttf")

REFERENCE_STANDARD_LEFT = 134.0
REFERENCE_STANDARD_PITCH = 272.0
REFERENCE_STANDARD_KEY_WIDTH = 192.0
REFERENCE_MATRIX_FIRST_VISIBLE_LEFT = 465.0
REFERENCE_MATRIX_PITCH = 331.0
REFERENCE_MATRIX_KEY_WIDTH = 228.0
REFERENCE_ENTER_WIDTH = 462.0
MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA = 6.0

EPSILON = 1e-6
MIN_SOLVER_STATE_COUNT = 2
MAX_RELAXATION_PASSES = 8
PATHOLOGICAL_SCALE_FLOOR = 0.0
CORRIDOR_EXTENSION_INTRA_GAPS = 5.0


class TopLabelLaneLayoutError(ValueError):
    """Raised when the top-label solver inputs are invalid."""

    @classmethod
    def invalid_font_attribute(cls, attribute: str) -> TopLabelLaneLayoutError:
        """Build an error for a missing integer font-table attribute."""
        message = f"Font table attribute {attribute!r} must be an int"
        return cls(message)

    @classmethod
    def missing_unicode_cmap(cls, font_name: str) -> TopLabelLaneLayoutError:
        """Build an error for a font that does not expose a Unicode cmap."""
        message = f"{font_name} does not expose a Unicode cmap"
        return cls(message)

    @classmethod
    def missing_glyph_advances(cls, font_name: str) -> TopLabelLaneLayoutError:
        """Build an error for a font that exposes no glyph advances."""
        message = f"{font_name} did not expose glyph advances"
        return cls(message)

    @classmethod
    def mismatched_row_example(cls, lane_name: str) -> TopLabelLaneLayoutError:
        """Build an error for a stress-row label fixture that mismatches slots."""
        message = f"row example labels for {lane_name} do not match the real slot count"
        return cls(message)

    @classmethod
    def missing_total_width(cls) -> TopLabelLaneLayoutError:
        """Build an error for width inputs missing a total width."""
        message = "total_width is required when explicit label widths are omitted"
        return cls(message)


class _LaneSlotGeometryPayload(TypedDict):
    code: int
    lane: str
    left: float
    body_width: float
    preferred_center_x: float
    max_shift: float


class _LaneGroupInputPayload(TypedDict):
    code: int
    preferred_center_x: float
    body_width: float
    f_text_width: float
    g_text_width: float
    text_width: float
    gap_width: float
    max_shift: float
    min_left_edge: float
    max_right_edge: float
    total_width: float
    longest_label: str


class _LaneGroupPlacementPayload(TypedDict):
    code: int
    center_shift: float
    center_shift_fraction: float
    stagger_level: int
    f_scale: float
    g_scale: float


class _LaneGroupFromWidthArgs(TypedDict, total=False):
    code: Required[int]
    preferred_center_x: Required[float]
    body_width: Required[float]
    policy: Required[LaneLayoutPolicy]
    total_width: float | None
    dual_label_group: bool
    f_text_width: float | None
    g_text_width: float | None
    min_left_edge: float
    max_right_edge: float


class _LaneGroupFromLabelsArgs(TypedDict, total=False):
    slot: Required[LaneSlotGeometry]
    f_label: Required[str]
    g_label: Required[str]
    policy: Required[LaneLayoutPolicy]
    min_left_edge: float
    max_right_edge: float


def _required_int_attribute(table: object, attribute: str) -> int:
    value = getattr(table, attribute, None)
    if not isinstance(value, int):
        raise TopLabelLaneLayoutError.invalid_font_attribute(attribute)
    return value


def _required_mapping(value: object, *, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        message = f"Expected {label} to be a mapping, got {value!r}"
        raise TypeError(message)
    return {
        key: nested_value for key, nested_value in value.items() if isinstance(key, str)
    }


def _required_number(value: object, *, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        message = f"Expected {label} to be numeric, got {value!r}"
        raise TypeError(message)
    return float(value)


@dataclass(frozen=True)
class LaneLayoutPolicy:
    """Policy knobs that control top-label spacing, scaling, and staggering."""

    top_label_text_size: float
    intra_group_gap: float
    inter_group_gap: float
    staggered_neighbor_gap: float
    max_shift_fraction_of_body_width: float = 0.15
    stagger_lift_text_size_ratio: float = 0.75
    min_scale: float = 0.82
    scale_step: float = 0.06

    @classmethod
    def default(cls) -> LaneLayoutPolicy:
        """Build the default solver policy from the key-label geometry contract."""
        payload = build_key_label_geometry_payload()
        label_constants = _required_mapping(
            payload["label_constants"],
            label="label_constants",
        )
        top_label_solver = _required_mapping(
            payload["top_label_solver"],
            label="top_label_solver",
        )
        intra_group_gap = _required_number(
            label_constants["TOP_F_G_LABEL_HORIZONTAL_GAP"],
            label="TOP_F_G_LABEL_HORIZONTAL_GAP",
        )
        return cls(
            top_label_text_size=_required_number(
                label_constants["TOP_F_G_LABEL_TEXT_SIZE"],
                label="TOP_F_G_LABEL_TEXT_SIZE",
            ),
            intra_group_gap=intra_group_gap,
            inter_group_gap=intra_group_gap * 2.0,
            staggered_neighbor_gap=intra_group_gap,
            max_shift_fraction_of_body_width=_required_number(
                top_label_solver["max_shift_fraction"],
                label="top_label_solver.max_shift_fraction",
            ),
            min_scale=_required_number(
                top_label_solver["min_scale"],
                label="top_label_solver.min_scale",
            ),
            scale_step=_required_number(
                top_label_solver["scale_step"],
                label="top_label_solver.scale_step",
            ),
        )

    def as_dict(self) -> dict[str, float]:
        """Serialize the policy values into a JSON-friendly mapping."""
        return {
            "top_label_text_size": self.top_label_text_size,
            "intra_group_gap": self.intra_group_gap,
            "inter_group_gap": self.inter_group_gap,
            "staggered_neighbor_gap": self.staggered_neighbor_gap,
            "max_shift_fraction_of_body_width": self.max_shift_fraction_of_body_width,
            "stagger_lift_text_size_ratio": self.stagger_lift_text_size_ratio,
            "min_scale": self.min_scale,
            "scale_step": self.scale_step,
        }


@dataclass(frozen=True)
class LaneSlotGeometry:
    """Describe the horizontal body geometry for one keypad slot in a lane."""

    code: int
    lane: str
    left: float
    body_width: float

    @property
    def preferred_center_x(self) -> float:
        """Return the visually tuned horizontal center used by the solver."""
        return (
            self.left + self.body_width / 2.0 + MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA / 4.0
        )

    def as_dict(self, policy: LaneLayoutPolicy) -> _LaneSlotGeometryPayload:
        """Serialize the slot geometry into a JSON-friendly mapping."""
        return {
            "code": self.code,
            "lane": self.lane,
            "left": self.left,
            "body_width": self.body_width,
            "preferred_center_x": self.preferred_center_x,
            "max_shift": self.body_width * policy.max_shift_fraction_of_body_width,
        }


@dataclass(frozen=True)
class LaneGroupInput:
    """Describe one solver input group for the F/G top-label lane."""

    code: int
    preferred_center_x: float
    body_width: float
    f_text_width: float
    g_text_width: float
    gap_width: float
    max_shift: float
    min_left_edge: float = float("-inf")
    max_right_edge: float = float("inf")

    @property
    def has_g_label(self) -> bool:
        """Return whether the group contains a meaningful G label."""
        return self.g_text_width > EPSILON and self.gap_width > EPSILON

    @property
    def text_width(self) -> float:
        """Return the total label width without the intra-group gap."""
        return self.f_text_width + self.g_text_width

    @property
    def total_width(self) -> float:
        """Return the full group width including any intra-group gap."""
        return self.f_text_width + self.gap_width + self.g_text_width

    @property
    def longest_label(self) -> str:
        """Return the dominant label side used for scaling decisions."""
        if not self.has_g_label or self.f_text_width > self.g_text_width:
            return "f"
        return "g"

    def as_dict(self) -> _LaneGroupInputPayload:
        """Serialize the lane-group input into a JSON-friendly mapping."""
        return {
            "code": self.code,
            "preferred_center_x": self.preferred_center_x,
            "body_width": self.body_width,
            "f_text_width": self.f_text_width,
            "g_text_width": self.g_text_width,
            "text_width": self.text_width,
            "gap_width": self.gap_width,
            "max_shift": self.max_shift,
            "min_left_edge": self.min_left_edge,
            "max_right_edge": self.max_right_edge,
            "total_width": self.total_width,
            "longest_label": self.longest_label,
        }


@dataclass(frozen=True)
class LaneGroupPlacement:
    """Describe the final placement and scale chosen for one label group."""

    code: int
    center_shift: float
    center_shift_fraction: float
    stagger_level: int
    f_scale: float
    g_scale: float

    def as_dict(self) -> _LaneGroupPlacementPayload:
        """Serialize the final placement into a JSON-friendly mapping."""
        return {
            "code": self.code,
            "center_shift": self.center_shift,
            "center_shift_fraction": self.center_shift_fraction,
            "stagger_level": self.stagger_level,
            "f_scale": self.f_scale,
            "g_scale": self.g_scale,
        }


@dataclass
class _LaneGroupState:
    group: LaneGroupInput
    stagger_level: int = 0
    f_scale: float = 1.0
    g_scale: float = 1.0
    center_x: float = 0.0

    def __post_init__(self) -> None:
        self.center_x = self.group.preferred_center_x

    @property
    def min_center_x(self) -> float:
        return self.group.preferred_center_x - self.group.max_shift

    @property
    def max_center_x(self) -> float:
        return self.group.preferred_center_x + self.group.max_shift

    @property
    def total_width(self) -> float:
        return (
            self.scaled_f_text_width + self.group.gap_width + self.scaled_g_text_width
        )

    @property
    def scaled_f_text_width(self) -> float:
        return self.group.f_text_width * self.f_scale

    @property
    def scaled_g_text_width(self) -> float:
        return self.group.g_text_width * self.g_scale

    @property
    def left(self) -> float:
        return self.center_x - self.total_width / 2.0

    @property
    def right(self) -> float:
        return self.center_x + self.total_width / 2.0


@dataclass(frozen=True)
class _PairConflict:
    left_index: int
    right_index: int
    shortage: float
    gap_shortage: float
    left_neighbor_overrun: float
    right_neighbor_overrun: float
    left_screen_overrun: float
    right_screen_overrun: float


REFERENCE_LANE_GEOMETRY = {
    "SMALL_ROW_1": [
        LaneSlotGeometry(
            code=1,
            lane="SMALL_ROW_1",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 0.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=2,
            lane="SMALL_ROW_1",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 1.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=3,
            lane="SMALL_ROW_1",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 2.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=4,
            lane="SMALL_ROW_1",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 3.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=5,
            lane="SMALL_ROW_1",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 4.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=6,
            lane="SMALL_ROW_1",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 5.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
    ],
    "SMALL_ROW_2": [
        LaneSlotGeometry(
            code=7,
            lane="SMALL_ROW_2",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 0.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=8,
            lane="SMALL_ROW_2",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 1.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=9,
            lane="SMALL_ROW_2",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 2.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=10,
            lane="SMALL_ROW_2",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 3.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=11,
            lane="SMALL_ROW_2",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 4.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=12,
            lane="SMALL_ROW_2",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 5.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
    ],
    "ENTER_ROW": [
        LaneSlotGeometry(
            code=13,
            lane="ENTER_ROW",
            left=REFERENCE_STANDARD_LEFT,
            body_width=REFERENCE_ENTER_WIDTH,
        ),
        LaneSlotGeometry(
            code=14,
            lane="ENTER_ROW",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 2.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=15,
            lane="ENTER_ROW",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 3.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=16,
            lane="ENTER_ROW",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 4.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=17,
            lane="ENTER_ROW",
            left=REFERENCE_STANDARD_LEFT + REFERENCE_STANDARD_PITCH * 5.0,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
    ],
    "MATRIX_ROW_1": [
        LaneSlotGeometry(
            code=18,
            lane="MATRIX_ROW_1",
            left=REFERENCE_STANDARD_LEFT,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=19,
            lane="MATRIX_ROW_1",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 0.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=20,
            lane="MATRIX_ROW_1",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 1.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=21,
            lane="MATRIX_ROW_1",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 2.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=22,
            lane="MATRIX_ROW_1",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 3.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
    ],
    "MATRIX_ROW_2": [
        LaneSlotGeometry(
            code=23,
            lane="MATRIX_ROW_2",
            left=REFERENCE_STANDARD_LEFT,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=24,
            lane="MATRIX_ROW_2",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 0.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=25,
            lane="MATRIX_ROW_2",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 1.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=26,
            lane="MATRIX_ROW_2",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 2.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=27,
            lane="MATRIX_ROW_2",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 3.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
    ],
    "MATRIX_ROW_3": [
        LaneSlotGeometry(
            code=28,
            lane="MATRIX_ROW_3",
            left=REFERENCE_STANDARD_LEFT,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=29,
            lane="MATRIX_ROW_3",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 0.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=30,
            lane="MATRIX_ROW_3",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 1.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=31,
            lane="MATRIX_ROW_3",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 2.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=32,
            lane="MATRIX_ROW_3",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 3.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
    ],
    "MATRIX_ROW_4": [
        LaneSlotGeometry(
            code=33,
            lane="MATRIX_ROW_4",
            left=REFERENCE_STANDARD_LEFT,
            body_width=REFERENCE_STANDARD_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=34,
            lane="MATRIX_ROW_4",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 0.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=35,
            lane="MATRIX_ROW_4",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 1.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=36,
            lane="MATRIX_ROW_4",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 2.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
        LaneSlotGeometry(
            code=37,
            lane="MATRIX_ROW_4",
            left=REFERENCE_MATRIX_FIRST_VISIBLE_LEFT + REFERENCE_MATRIX_PITCH * 3.0,
            body_width=REFERENCE_MATRIX_KEY_WIDTH,
        ),
    ],
}


# These rows are long-label stress examples for the solver. They are not the
# exported default keypad fixture used at app startup.
EXAMPLE_STRESS_R47_ROW_LABELS: dict[str, list[tuple[str, str]]] = {
    "SMALL_ROW_1": [
        ("ASSIGN", "MYALPHA"),
        ("MyMATX", "SOLVE"),
        ("CUSTOM", "VARS"),
        ("PROGRAM", "FLAGS"),
        ("MEMORY", "MODES"),
        ("CONFIG", "STATUS"),
    ],
    "ENTER_ROW": [
        ("ENTER", "EVAL"),
        ("STACK", "LIFT"),
        ("XEQ", "LABEL"),
        ("STORE", "RECALL"),
        ("SHIFT", "MODES"),
    ],
    "MATRIX_ROW_1": [
        ("MATH", "TOOLS"),
        ("MATRIX", "SOLVE"),
        ("VECTOR", "DOT"),
        ("COMPLEX", "ROOT"),
        ("STAT", "REGS"),
    ],
}


@lru_cache(maxsize=1)
def _font_width_context() -> tuple[float, dict[int, float], float]:
    font = TTFont(STANDARD_FONT_PATH)
    head = font["head"]
    cmap = font.getBestCmap()
    if cmap is None:
        raise TopLabelLaneLayoutError.missing_unicode_cmap(STANDARD_FONT_PATH.name)
    metrics = font["hmtx"].metrics
    advances = {
        codepoint: float(metric[0])
        for codepoint, glyph_name in cmap.items()
        if (metric := metrics.get(glyph_name)) is not None
    }
    if not advances:
        raise TopLabelLaneLayoutError.missing_glyph_advances(
            STANDARD_FONT_PATH.name,
        )
    average_advance = sum(advances.values()) / len(advances)
    units_per_em = _required_int_attribute(head, "unitsPerEm")
    return float(units_per_em), advances, float(average_advance)


def measure_text_width(text: str, font_size: float) -> float:
    """Measure the rendered width of a top-label string at the requested size."""
    if not text:
        return 0.0
    units_per_em, advances, average_advance = _font_width_context()
    total_advance = sum(advances.get(ord(char), average_advance) for char in text)
    return total_advance / units_per_em * font_size


def build_reference_lane_geometry(
    policy: LaneLayoutPolicy | None = None,
) -> dict[str, list[_LaneSlotGeometryPayload]]:
    """Serialize the reference lane geometry used by the top-label solver."""
    resolved_policy = policy or LaneLayoutPolicy.default()
    return {
        lane: [slot.as_dict(resolved_policy) for slot in slots]
        for lane, slots in REFERENCE_LANE_GEOMETRY.items()
    }


def build_stress_r47_row_example(
    lane_name: str,
    policy: LaneLayoutPolicy | None = None,
) -> dict[str, object]:
    """Build one stress-row fixture showing solver behavior on real R47 slots."""
    resolved_policy = policy or LaneLayoutPolicy.default()
    slots = REFERENCE_LANE_GEOMETRY[lane_name]
    label_pairs = EXAMPLE_STRESS_R47_ROW_LABELS[lane_name]
    if len(slots) != len(label_pairs):
        raise TopLabelLaneLayoutError.mismatched_row_example(lane_name)

    groups = [
        make_lane_group_from_labels(
            slot=slot,
            f_label=f_label,
            g_label=g_label,
            policy=resolved_policy,
        )
        for slot, (f_label, g_label) in zip(slots, label_pairs, strict=True)
    ]
    placements = solve_lane(groups, resolved_policy)
    return {
        "lane": lane_name,
        "slot_geometry": [slot.as_dict(resolved_policy) for slot in slots],
        "inputs": [group.as_dict() for group in groups],
        "placements": [placement.as_dict() for placement in placements],
    }


def make_lane_group_from_width(
    **kwargs: Unpack[_LaneGroupFromWidthArgs],
) -> LaneGroupInput:
    """Build a lane-group input from explicit width measurements."""
    code = kwargs["code"]
    preferred_center_x = kwargs["preferred_center_x"]
    body_width = kwargs["body_width"]
    policy = kwargs["policy"]
    total_width = kwargs.get("total_width")
    dual_label_group = kwargs.get("dual_label_group", True)
    f_text_width = kwargs.get("f_text_width")
    g_text_width = kwargs.get("g_text_width")
    min_left_edge = kwargs.get("min_left_edge", float("-inf"))
    max_right_edge = kwargs.get("max_right_edge", float("inf"))

    if f_text_width is None and g_text_width is None:
        if total_width is None:
            raise TopLabelLaneLayoutError.missing_total_width()
        gap_width = policy.intra_group_gap if dual_label_group else 0.0
        text_width = max(0.0, total_width - gap_width)
        if dual_label_group:
            f_text_width = text_width / 2.0
            g_text_width = text_width - f_text_width
        else:
            f_text_width = text_width
            g_text_width = 0.0
    else:
        f_text_width = max(0.0, f_text_width or 0.0)
        g_text_width = max(0.0, g_text_width or 0.0)
        gap_width = policy.intra_group_gap if g_text_width > EPSILON else 0.0

    return LaneGroupInput(
        code=code,
        preferred_center_x=preferred_center_x,
        body_width=body_width,
        f_text_width=f_text_width,
        g_text_width=g_text_width,
        gap_width=gap_width,
        max_shift=body_width * policy.max_shift_fraction_of_body_width,
        min_left_edge=min_left_edge,
        max_right_edge=max_right_edge,
    )


def make_lane_group_from_labels(
    **kwargs: Unpack[_LaneGroupFromLabelsArgs],
) -> LaneGroupInput:
    """Build a lane-group input by measuring real label strings."""
    slot = kwargs["slot"]
    f_label = kwargs["f_label"]
    g_label = kwargs["g_label"]
    policy = kwargs["policy"]
    min_left_edge = kwargs.get("min_left_edge", float("-inf"))
    max_right_edge = kwargs.get("max_right_edge", float("inf"))
    has_f_label = bool(f_label.strip())
    has_g_label = has_f_label and bool(g_label.strip())
    f_text_width = measure_text_width(f_label, policy.top_label_text_size)
    g_text_width = measure_text_width(g_label, policy.top_label_text_size)
    gap_width = policy.intra_group_gap if has_g_label else 0.0
    return LaneGroupInput(
        code=slot.code,
        preferred_center_x=slot.preferred_center_x,
        body_width=slot.body_width,
        f_text_width=f_text_width,
        g_text_width=g_text_width,
        gap_width=gap_width,
        max_shift=slot.body_width * policy.max_shift_fraction_of_body_width,
        min_left_edge=min_left_edge,
        max_right_edge=max_right_edge,
    )


def _required_gap(
    _left: _LaneGroupState,
    _right: _LaneGroupState,
    policy: LaneLayoutPolicy,
) -> float:
    return policy.inter_group_gap


def _reset_positions(states: list[_LaneGroupState]) -> None:
    for state in states:
        state.center_x = state.group.preferred_center_x


def _clone_states(states: list[_LaneGroupState]) -> list[_LaneGroupState]:
    clones: list[_LaneGroupState] = []
    for state in states:
        clone = _LaneGroupState(
            group=state.group,
            stagger_level=state.stagger_level,
            f_scale=state.f_scale,
            g_scale=state.g_scale,
        )
        clone.center_x = state.center_x
        clones.append(clone)
    return clones


def _restore_states(
    target: list[_LaneGroupState],
    source: list[_LaneGroupState],
) -> None:
    for target_state, source_state in zip(target, source, strict=True):
        target_state.stagger_level = source_state.stagger_level
        target_state.f_scale = source_state.f_scale
        target_state.g_scale = source_state.g_scale
        target_state.center_x = source_state.center_x


def _neighbor_corridor_limits(
    left: _LaneGroupState,
    right: _LaneGroupState,
    policy: LaneLayoutPolicy,
) -> tuple[float, float]:
    corridor_extension = policy.intra_group_gap * CORRIDOR_EXTENSION_INTRA_GAPS
    raw_left_limit = right.group.preferred_center_x - right.group.body_width / 2.0
    raw_right_limit = left.group.preferred_center_x + left.group.body_width / 2.0
    neighbor_left_limit = raw_left_limit + corridor_extension
    neighbor_right_limit = raw_right_limit - corridor_extension
    return neighbor_left_limit, neighbor_right_limit


def _build_conflict(
    states: list[_LaneGroupState],
    index: int,
    policy: LaneLayoutPolicy,
) -> _PairConflict | None:
    left = states[index]
    right = states[index + 1]
    left_limit, right_limit = _neighbor_corridor_limits(left, right, policy)
    gap_shortage = max(
        0.0,
        _required_gap(left, right, policy) - (right.left - left.right),
    )
    left_neighbor_overrun = max(0.0, left.right - left_limit)
    right_neighbor_overrun = max(0.0, right_limit - right.left)
    left_screen_overrun = 0.0
    if index == 0 and isfinite(left.group.min_left_edge):
        left_screen_overrun = max(0.0, left.group.min_left_edge - left.left)
    right_screen_overrun = 0.0
    if index == len(states) - 2 and isfinite(right.group.max_right_edge):
        right_screen_overrun = max(0.0, right.right - right.group.max_right_edge)
    if (
        gap_shortage <= EPSILON
        and left_neighbor_overrun <= EPSILON
        and right_neighbor_overrun <= EPSILON
        and left_screen_overrun <= EPSILON
        and right_screen_overrun <= EPSILON
    ):
        return None

    return _PairConflict(
        left_index=index,
        right_index=index + 1,
        shortage=max(
            gap_shortage,
            left_neighbor_overrun + right_neighbor_overrun,
            left_screen_overrun,
            right_screen_overrun,
        ),
        gap_shortage=gap_shortage,
        left_neighbor_overrun=left_neighbor_overrun,
        right_neighbor_overrun=right_neighbor_overrun,
        left_screen_overrun=left_screen_overrun,
        right_screen_overrun=right_screen_overrun,
    )


def _offending_sides(conflict: _PairConflict) -> list[tuple[str, float]]:
    offenders: list[tuple[str, float]] = []
    if conflict.left_neighbor_overrun > EPSILON:
        offenders.append(("left", conflict.left_neighbor_overrun))
    if conflict.right_neighbor_overrun > EPSILON:
        offenders.append(("right", conflict.right_neighbor_overrun))
    return offenders


def _selected_offender(
    states: list[_LaneGroupState],
    conflict: _PairConflict,
) -> tuple[int, float, bool] | None:
    candidates: list[tuple[float, int, int, bool]] = []
    if conflict.left_neighbor_overrun > EPSILON:
        candidates.append(
            (conflict.left_neighbor_overrun, 0, conflict.left_index, True),
        )
    if conflict.right_neighbor_overrun > EPSILON:
        candidates.append(
            (conflict.right_neighbor_overrun, 0, conflict.right_index, False),
        )
    if conflict.left_screen_overrun > EPSILON:
        candidates.append((conflict.left_screen_overrun, 1, conflict.left_index, False))
    if conflict.right_screen_overrun > EPSILON:
        candidates.append(
            (conflict.right_screen_overrun, 1, conflict.right_index, True),
        )
    if candidates:
        overrun, _, index, toward_left = max(candidates)
        return index, overrun, toward_left

    if conflict.gap_shortage <= EPSILON:
        return None

    left = states[conflict.left_index]
    right = states[conflict.right_index]
    if left.total_width > right.total_width + EPSILON:
        return conflict.left_index, conflict.gap_shortage, True
    return conflict.right_index, conflict.gap_shortage, False


def _shift_capacity(
    state: _LaneGroupState,
    *,
    toward_left: bool,
    ignore_preferred_shift_budget: bool,
) -> float:
    if ignore_preferred_shift_budget:
        return float("inf")
    return (
        state.center_x - state.min_center_x
        if toward_left
        else state.max_center_x - state.center_x
    )


def _apply_shift(
    state: _LaneGroupState,
    *,
    toward_left: bool,
    amount: float,
) -> None:
    if toward_left:
        state.center_x -= amount
    else:
        state.center_x += amount


def _current_conflicts(
    states: list[_LaneGroupState],
    policy: LaneLayoutPolicy,
) -> list[_PairConflict]:
    conflicts: list[_PairConflict] = []
    for index in range(len(states) - 1):
        conflict = _build_conflict(states, index, policy)
        if conflict is not None:
            conflicts.append(conflict)
    return conflicts


def _limits_respected(
    states: list[_LaneGroupState],
    _policy: LaneLayoutPolicy,
) -> bool:
    for state in states:
        if state.center_x + EPSILON < state.min_center_x:
            return False
        if state.center_x > state.max_center_x + EPSILON:
            return False
    return True


def _intergap_respected(
    states: list[_LaneGroupState],
    policy: LaneLayoutPolicy,
) -> bool:
    for index in range(len(states) - 1):
        left = states[index]
        right = states[index + 1]
        gap = right.left - left.right
        if gap + EPSILON < _required_gap(left, right, policy):
            return False
    return True


def _row_resolved(
    states: list[_LaneGroupState],
    policy: LaneLayoutPolicy,
) -> bool:
    return not _current_conflicts(states, policy)


def _try_local_offender_translation(
    states: list[_LaneGroupState],
    policy: LaneLayoutPolicy,
) -> bool:
    working = _clone_states(states)

    for _ in range(max(MAX_RELAXATION_PASSES, len(states) * 2)):
        conflicts = _current_conflicts(working, policy)
        if not conflicts and _row_resolved(working, policy):
            _restore_states(states, working)
            return True

        changed = False
        for conflict in conflicts:
            selected = _selected_offender(working, conflict)
            if selected is None:
                continue
            offender_index, offender_overrun, toward_left = selected
            offender = working[offender_index]
            capacity = _shift_capacity(
                offender,
                toward_left=toward_left,
                ignore_preferred_shift_budget=False,
            )
            move = min(offender_overrun, capacity)
            if move > EPSILON:
                _apply_shift(offender, toward_left=toward_left, amount=move)
                changed = True

        if not changed:
            break

    if _row_resolved(working, policy):
        _restore_states(states, working)
        return True
    return False


def _resolve_positions(
    states: list[_LaneGroupState],
    policy: LaneLayoutPolicy,
    *,
    ignore_preferred_shift_budget: bool = False,
) -> list[_PairConflict]:
    for _ in range(max(MAX_RELAXATION_PASSES, len(states) * 2)):
        changed = False

        for conflict in _current_conflicts(states, policy):
            changed |= _apply_neighbor_and_screen_moves(
                states,
                conflict,
                ignore_preferred_shift_budget=ignore_preferred_shift_budget,
            )
            changed |= _resolve_gap_shortage(
                states,
                conflict,
                policy,
                ignore_preferred_shift_budget=ignore_preferred_shift_budget,
            )

        if not changed:
            break

    return _current_conflicts(states, policy)


def _apply_neighbor_and_screen_moves(
    states: list[_LaneGroupState],
    conflict: _PairConflict,
    *,
    ignore_preferred_shift_budget: bool,
) -> bool:
    left = states[conflict.left_index]
    right = states[conflict.right_index]
    moves = (
        (left, True, conflict.left_neighbor_overrun),
        (right, False, conflict.right_neighbor_overrun),
        (left, False, conflict.left_screen_overrun),
        (right, True, conflict.right_screen_overrun),
    )
    changed = False
    for state, toward_left, overrun in moves:
        move = min(
            overrun,
            _shift_capacity(
                state,
                toward_left=toward_left,
                ignore_preferred_shift_budget=ignore_preferred_shift_budget,
            ),
        )
        if move <= EPSILON:
            continue
        _apply_shift(state, toward_left=toward_left, amount=move)
        changed = True
    return changed


def _resolve_gap_shortage(
    states: list[_LaneGroupState],
    conflict: _PairConflict,
    policy: LaneLayoutPolicy,
    *,
    ignore_preferred_shift_budget: bool,
) -> bool:
    refreshed_conflict = _build_conflict(states, conflict.left_index, policy)
    if refreshed_conflict is None or refreshed_conflict.gap_shortage <= EPSILON:
        return False

    left = states[refreshed_conflict.left_index]
    right = states[refreshed_conflict.right_index]
    shortage = refreshed_conflict.gap_shortage
    shortage, changed = _split_gap_shortage(
        left,
        right,
        shortage,
        ignore_preferred_shift_budget=ignore_preferred_shift_budget,
    )
    if shortage <= EPSILON:
        return changed
    return _apply_offender_preference(
        refreshed_conflict,
        states,
        shortage,
        changed=changed,
        ignore_preferred_shift_budget=ignore_preferred_shift_budget,
    )


def _split_gap_shortage(
    left: _LaneGroupState,
    right: _LaneGroupState,
    shortage: float,
    *,
    ignore_preferred_shift_budget: bool,
) -> tuple[float, bool]:
    half_shortage = shortage / 2.0
    left_capacity = _shift_capacity(
        left,
        toward_left=True,
        ignore_preferred_shift_budget=ignore_preferred_shift_budget,
    )
    right_capacity = _shift_capacity(
        right,
        toward_left=False,
        ignore_preferred_shift_budget=ignore_preferred_shift_budget,
    )

    changed = False
    left_gap_move = min(half_shortage, left_capacity)
    if left_gap_move > EPSILON:
        left.center_x -= left_gap_move
        shortage -= left_gap_move
        changed = True
    right_gap_move = min(half_shortage, right_capacity)
    if right_gap_move > EPSILON:
        right.center_x += right_gap_move
        shortage -= right_gap_move
        changed = True
    return shortage, changed


def _apply_offender_preference(
    refreshed_conflict: _PairConflict,
    states: list[_LaneGroupState],
    shortage: float,
    *,
    changed: bool,
    ignore_preferred_shift_budget: bool,
) -> bool:
    left = states[refreshed_conflict.left_index]
    right = states[refreshed_conflict.right_index]
    selected = _selected_offender(states, refreshed_conflict)
    preferred_sides = [False, True]
    if selected is not None:
        preferred_sides = [selected[2], not selected[2]]

    for toward_left in preferred_sides:
        offender = left if toward_left else right
        capacity = _shift_capacity(
            offender,
            toward_left=toward_left,
            ignore_preferred_shift_budget=ignore_preferred_shift_budget,
        )
        extra = min(shortage, capacity)
        if extra > EPSILON:
            _apply_shift(offender, toward_left=toward_left, amount=extra)
            shortage -= extra
            changed = True
        if shortage <= EPSILON:
            break
    return changed


def _label_scale(state: _LaneGroupState, label: str) -> float:
    return state.f_scale if label == "f" else state.g_scale


def _label_width(state: _LaneGroupState, label: str) -> float:
    return state.group.f_text_width if label == "f" else state.group.g_text_width


def _label_can_scale(
    state: _LaneGroupState,
    label: str,
    scale_floor: float,
) -> bool:
    return (
        _label_width(state, label) > EPSILON
        and _label_scale(state, label) > scale_floor + EPSILON
    )


def _scale_candidate_label(
    state: _LaneGroupState,
    policy: LaneLayoutPolicy,
    scale_floor: float,
) -> str | None:
    primary = state.group.longest_label
    secondary = "g" if primary == "f" else "f"
    primary_can_scale = _label_can_scale(state, primary, scale_floor)
    secondary_can_scale = _label_can_scale(state, secondary, scale_floor)

    if not primary_can_scale and not secondary_can_scale:
        return None

    primary_scale = _label_scale(state, primary)
    secondary_scale = _label_scale(state, secondary)
    if (
        primary_scale + policy.scale_step <= secondary_scale + EPSILON
        and secondary_can_scale
    ):
        return secondary
    if (
        secondary_scale + policy.scale_step <= primary_scale + EPSILON
        and primary_can_scale
    ):
        return primary

    if primary_can_scale:
        return primary

    if secondary_can_scale:
        return secondary

    return None


def _scaling_candidates_for_conflict(
    states: list[_LaneGroupState],
    conflict: _PairConflict,
) -> list[tuple[int, int, float]]:
    corridor_candidates: list[tuple[int, int, float]] = []
    left_limit_overrun = max(
        conflict.left_neighbor_overrun,
        conflict.left_screen_overrun,
    )
    if left_limit_overrun > EPSILON:
        corridor_candidates.append((0, conflict.left_index, left_limit_overrun))
    right_limit_overrun = max(
        conflict.right_neighbor_overrun,
        conflict.right_screen_overrun,
    )
    if right_limit_overrun > EPSILON:
        corridor_candidates.append((0, conflict.right_index, right_limit_overrun))
    if corridor_candidates:
        return corridor_candidates

    if conflict.gap_shortage <= EPSILON:
        return []

    ordered_indices = sorted(
        (conflict.left_index, conflict.right_index),
        key=lambda index: (states[index].total_width, index),
        reverse=True,
    )
    return [
        (order_rank, offender_index, conflict.gap_shortage)
        for order_rank, offender_index in enumerate(ordered_indices)
    ]


def _scale_most_offending(
    states: list[_LaneGroupState],
    conflicts: list[_PairConflict],
    policy: LaneLayoutPolicy,
    *,
    scale_floor: float,
) -> bool:
    candidates: dict[int, tuple[int, float, float, int]] = {}
    for conflict in conflicts:
        for (
            order_rank,
            offender_index,
            offender_overrun,
        ) in _scaling_candidates_for_conflict(states, conflict):
            state = states[offender_index]
            candidate = (
                -order_rank,
                state.total_width,
                offender_overrun,
                offender_index,
            )
            existing = candidates.get(offender_index)
            if existing is None or candidate > existing:
                candidates[offender_index] = candidate

    for _, _, _, offender_index in sorted(candidates.values(), reverse=True):
        state = states[offender_index]
        label = _scale_candidate_label(state, policy, scale_floor)
        if label is None:
            continue

        current_scale = _label_scale(state, label)
        target_scale = max(scale_floor, current_scale - policy.scale_step)

        if target_scale + EPSILON < current_scale:
            if label == "f":
                state.f_scale = target_scale
            else:
                state.g_scale = target_scale
            return True

    return False


def _relax_row(
    states: list[_LaneGroupState],
    policy: LaneLayoutPolicy,
    *,
    ignore_preferred_shift_budget: bool = False,
) -> list[_PairConflict]:
    _reset_positions(states)
    if _row_resolved(states, policy):
        return []
    if not ignore_preferred_shift_budget and _try_local_offender_translation(
        states,
        policy,
    ):
        return []
    return _resolve_positions(
        states,
        policy,
        ignore_preferred_shift_budget=ignore_preferred_shift_budget,
    )


def _scale_until_resolved(
    states: list[_LaneGroupState],
    conflicts: list[_PairConflict],
    policy: LaneLayoutPolicy,
    *,
    scale_floor: float,
    ignore_preferred_shift_budget: bool,
) -> list[_PairConflict]:
    max_scale_steps = max(1, int(((1.0 - scale_floor) / policy.scale_step) + 0.999999))
    for _ in range(len(states) * 2 * max_scale_steps):
        if not conflicts:
            return []
        if not _scale_most_offending(
            states,
            conflicts,
            policy,
            scale_floor=scale_floor,
        ):
            return conflicts
        conflicts = _relax_row(
            states,
            policy,
            ignore_preferred_shift_budget=ignore_preferred_shift_budget,
        )
    return conflicts


def solve_lane(
    lane_groups: list[LaneGroupInput],
    policy: LaneLayoutPolicy | None = None,
) -> list[LaneGroupPlacement]:
    """Solve one top-label lane and return the final label placements."""
    resolved_policy = policy or LaneLayoutPolicy.default()
    states = [_LaneGroupState(group=group) for group in lane_groups]

    if len(states) < MIN_SOLVER_STATE_COUNT:
        return [
            LaneGroupPlacement(
                code=state.group.code,
                center_shift=0.0,
                center_shift_fraction=0.0,
                stagger_level=0,
                f_scale=1.0,
                g_scale=1.0,
            )
            for state in states
        ]

    conflicts = _relax_row(states, resolved_policy)

    if conflicts:
        conflicts = _scale_until_resolved(
            states,
            conflicts,
            resolved_policy,
            scale_floor=resolved_policy.min_scale,
            ignore_preferred_shift_budget=False,
        )

    if conflicts:
        conflicts = _relax_row(
            states,
            resolved_policy,
            ignore_preferred_shift_budget=True,
        )

    if conflicts:
        conflicts = _scale_until_resolved(
            states,
            conflicts,
            resolved_policy,
            scale_floor=PATHOLOGICAL_SCALE_FLOOR,
            ignore_preferred_shift_budget=True,
        )

    if conflicts:
        conflicts = _relax_row(
            states,
            resolved_policy,
            ignore_preferred_shift_budget=True,
        )

    return [
        LaneGroupPlacement(
            code=state.group.code,
            center_shift=state.center_x - state.group.preferred_center_x,
            center_shift_fraction=(state.center_x - state.group.preferred_center_x)
            / state.group.body_width,
            stagger_level=state.stagger_level,
            f_scale=state.f_scale,
            g_scale=state.g_scale,
        )
        for state in states
    ]


def build_top_label_lane_layout_payload() -> dict[str, object]:
    """Build the serialized top-label lane-layout contract payload."""
    policy = LaneLayoutPolicy.default()
    example_lane = REFERENCE_LANE_GEOMETRY["SMALL_ROW_1"][:3]
    example_groups = [
        make_lane_group_from_labels(
            slot=example_lane[0],
            f_label="ASSIGN",
            g_label="MYALPHA",
            policy=policy,
        ),
        make_lane_group_from_labels(
            slot=example_lane[1],
            f_label="MyMATX",
            g_label="SOLVE",
            policy=policy,
        ),
        make_lane_group_from_labels(
            slot=example_lane[2],
            f_label="CUSTOM",
            g_label="VARS",
            policy=policy,
        ),
    ]
    example_solution = solve_lane(example_groups, policy)

    return {
        "policy": policy.as_dict(),
        "reference_lane_geometry": build_reference_lane_geometry(policy),
        "example_standard_lane": {
            "inputs": [group.as_dict() for group in example_groups],
            "placements": [placement.as_dict() for placement in example_solution],
        },
        "example_stress_r47_rows": {
            "function_row_6_keys_6_groups": build_stress_r47_row_example(
                "SMALL_ROW_1",
                policy,
            ),
            "enter_row_5_keys_5_groups": build_stress_r47_row_example(
                "ENTER_ROW",
                policy,
            ),
            "numeric_row_5_keys_5_groups": build_stress_r47_row_example(
                "MATRIX_ROW_1",
                policy,
            ),
        },
    }


def main() -> int:
    """Write the top-label lane-layout payload to standard output as JSON."""
    json.dump(
        build_top_label_lane_layout_payload(),
        sys.stdout,
        indent=2,
        sort_keys=True,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
