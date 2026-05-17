"""Derive the keypad font-policy contract from live fonts, fixtures, and owners."""

from __future__ import annotations

import json
import sys
from typing import TYPE_CHECKING, cast

from fontTools.ttLib import TTFont

from r47_contracts._contract_data import require_mapping, require_string
from r47_contracts._repo_paths import (
    KEYPAD_FIXTURES_ROOT,
    KOTLIN_R47ZEN_ROOT,
    REPO_ROOT,
)
from r47_contracts.runtime_fonts import resolve_runtime_font_path

if TYPE_CHECKING:
    from pathlib import Path

_FONT_ASSETS = {
    "standard": "C47__StandardFont.ttf",
    "numeric": "C47__NumericFont.ttf",
    "tiny": "C47__TinyFont.ttf",
}

_FONT_FALLBACK_POLICY = {
    "main_key_primary_default": ["standard", "tiny"],
    "main_key_primary_style_numeric": ["standard", "tiny"],
    "main_key_top_f": ["standard", "tiny"],
    "main_key_top_g": ["standard", "tiny"],
    "main_key_letter": ["standard", "tiny"],
    "softkey_primary": ["standard", "tiny"],
    "softkey_value": ["standard", "tiny"],
    "softkey_aux": ["standard", "tiny"],
    "softkey_overlay_mb": ["standard", "tiny"],
}

_LANE_SLOTS = {
    "primary": 0,
    "f": 1,
    "g": 2,
    "letter": 3,
}

_MAIN_KEY_CODE_START = 1
_MAIN_KEY_CODE_END = 37
_META_KEY_ENABLED_OFFSET = 13
_META_SCALARS_BEFORE_STYLE_ROLE = 9
_STYLE_NUMERIC = 5

_REPLICA_LAYOUT_PATH = KOTLIN_R47ZEN_ROOT / "ReplicaKeypadLayout.kt"
_TEXT_RENDERER_PATH = KOTLIN_R47ZEN_ROOT / "C47TextRenderer.kt"
_TYPEFACE_POLICY_PATH = KOTLIN_R47ZEN_ROOT / "C47TypefacePolicy.kt"
_MAIN_KEY_VIEW_PATH = KOTLIN_R47ZEN_ROOT / "CalculatorKeyView.kt"
_SOFTKEY_PAINTER_PATH = KOTLIN_R47ZEN_ROOT / "CalculatorSoftkeyPainter.kt"


class KeyFontPolicyError(ValueError):
    """Raised when the keypad font-policy inputs drift out of contract."""

    @classmethod
    def missing_unicode_cmap(cls, font_name: str) -> KeyFontPolicyError:
        """Build an error for a runtime font without a Unicode cmap."""
        message = f"{font_name} does not expose a Unicode cmap"
        return cls(message)

    @classmethod
    def invalid_int(cls, label: str, value: object) -> KeyFontPolicyError:
        """Build an error for a JSON integer field with the wrong type."""
        message = f"Expected {label} to be an integer, got {value!r}"
        return cls(message)

    @classmethod
    def invalid_list(cls, label: str, value: object) -> KeyFontPolicyError:
        """Build an error for a JSON array field with the wrong type."""
        message = f"Expected {label} to be a list, got {value!r}"
        return cls(message)

    @classmethod
    def invalid_string(cls, label: str, value: object) -> KeyFontPolicyError:
        """Build an error for a JSON string field with the wrong type."""
        message = f"Expected {label} to be a string, got {value!r}"
        return cls(message)

    @classmethod
    def missing_source_contract(
        cls,
        path: Path,
        snippet: str,
    ) -> KeyFontPolicyError:
        """Build an error for a Kotlin owner that drifted from the font contract."""
        message = (
            f"{path.relative_to(REPO_ROOT)} is missing the expected font-contract "
            f"snippet: {snippet!r}"
        )
        return cls(message)

    @classmethod
    def wrong_vector_length(
        cls,
        label: str,
        expected: int,
        actual: int,
    ) -> KeyFontPolicyError:
        """Build an error for a fixture vector with an unexpected length."""
        message = f"Expected {label} length {expected}, got {actual}"
        return cls(message)


def _require_int(value: object, *, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise KeyFontPolicyError.invalid_int(label, value)
    return value


def _require_list(value: object, *, label: str) -> list[object]:
    if not isinstance(value, list):
        raise KeyFontPolicyError.invalid_list(label, value)
    return cast("list[object]", value)


def _require_text(value: object, *, label: str) -> str:
    if not isinstance(value, str):
        raise KeyFontPolicyError.invalid_string(label, value)
    return value


def _load_json_mapping(path: Path, *, label: str) -> dict[str, object]:
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    return require_mapping(payload, label=label)


def _compress_codepoint_ranges(codepoints: list[int]) -> list[list[int]]:
    if not codepoints:
        return []

    ranges: list[list[int]] = []
    start = codepoints[0]
    end = start
    for codepoint in codepoints[1:]:
        if codepoint == end + 1:
            end = codepoint
            continue

        ranges.append([start, end])
        start = codepoint
        end = codepoint

    ranges.append([start, end])
    return ranges


def _font_payload(font_name: str) -> tuple[dict[str, object], set[int]]:
    font_path = resolve_runtime_font_path(font_name)
    cmap = TTFont(font_path).getBestCmap()
    if cmap is None:
        raise KeyFontPolicyError.missing_unicode_cmap(font_name)

    codepoints = sorted(cmap)
    return (
        {
            "asset_name": font_name,
            "font_path": str(font_path.relative_to(REPO_ROOT)),
            "unicode_codepoint_count": len(codepoints),
            "unicode_range_pairs": _compress_codepoint_ranges(codepoints),
        },
        set(codepoints),
    )


def _label_supported(label: str, supported_codepoints: set[int]) -> bool:
    return all(ord(character) in supported_codepoints for character in label)


def _coverage_payload(
    labels: set[str],
    codepoints: set[int],
    *,
    font_codepoints: dict[str, set[int]],
) -> dict[str, object]:
    distinct_labels = sorted(labels)
    return {
        "distinct_codepoint_count": len(codepoints),
        "distinct_label_count": len(distinct_labels),
        "distinct_labels": distinct_labels,
        "font_coverage": {
            alias: {
                "missing_labels": [
                    label
                    for label in distinct_labels
                    if not _label_supported(label, supported_codepoints)
                ],
                "supported_codepoint_count": len(codepoints & supported_codepoints),
                "supported_label_count": sum(
                    1
                    for label in distinct_labels
                    if _label_supported(label, supported_codepoints)
                ),
            }
            for alias, supported_codepoints in font_codepoints.items()
        },
    }


def _style_role_offset(key_count: int) -> int:
    # Mirrors KeypadSnapshot's meta layout so the exported fixtures stay aligned.
    return _META_KEY_ENABLED_OFFSET + key_count + _META_SCALARS_BEFORE_STYLE_ROLE


def _collect_fixture_coverage() -> dict[str, object]:
    manifest_path = KEYPAD_FIXTURES_ROOT / "manifest.json"
    manifest = _load_json_mapping(manifest_path, label="keypad fixture manifest")
    scenarios = _require_list(manifest.get("scenarios"), label="manifest.scenarios")
    key_count = _require_int(manifest.get("keyCount"), label="manifest.keyCount")
    labels_per_key = _require_int(
        manifest.get("labelsPerKey"),
        label="manifest.labelsPerKey",
    )
    style_role_offset = _style_role_offset(key_count)
    expected_label_count = key_count * labels_per_key
    expected_style_role_meta_length = style_role_offset + key_count

    lane_labels = {lane: set() for lane in _LANE_SLOTS}
    lane_codepoints = {lane: set() for lane in _LANE_SLOTS}
    style_numeric_primary_labels: set[str] = set()
    style_numeric_primary_codepoints: set[int] = set()

    for index, scenario in enumerate(scenarios):
        scenario_mapping = require_mapping(
            scenario,
            label=f"manifest.scenarios[{index}]",
        )
        scenario_file = require_string(
            scenario_mapping.get("file"),
            label=f"manifest.scenarios[{index}].file",
        )
        snapshot = _load_json_mapping(
            KEYPAD_FIXTURES_ROOT / scenario_file,
            label=f"keypad fixture {scenario_file}",
        )
        labels = _require_list(snapshot.get("labels"), label=f"{scenario_file}.labels")
        meta = _require_list(snapshot.get("meta"), label=f"{scenario_file}.meta")

        if len(labels) != expected_label_count:
            label_vector_name = f"{scenario_file}.labels"
            raise KeyFontPolicyError.wrong_vector_length(
                label_vector_name,
                expected_label_count,
                len(labels),
            )
        if len(meta) < expected_style_role_meta_length:
            meta_vector_name = f"{scenario_file}.meta"
            raise KeyFontPolicyError.wrong_vector_length(
                meta_vector_name,
                expected_style_role_meta_length,
                len(meta),
            )

        for key_code in range(_MAIN_KEY_CODE_START, _MAIN_KEY_CODE_END + 1):
            label_base = (key_code - 1) * labels_per_key
            for lane, slot in _LANE_SLOTS.items():
                label = _require_text(
                    labels[label_base + slot],
                    label=f"{scenario_file}.labels[{label_base + slot}]",
                )
                if not label:
                    continue
                lane_labels[lane].add(label)
                lane_codepoints[lane].update(map(ord, label))

            style_role = _require_int(
                meta[style_role_offset + key_code - 1],
                label=f"{scenario_file}.meta[{style_role_offset + key_code - 1}]",
            )
            if style_role != _STYLE_NUMERIC:
                continue

            primary_label = _require_text(
                labels[label_base + _LANE_SLOTS["primary"]],
                label=f"{scenario_file}.labels[{label_base}]",
            )
            if not primary_label:
                continue
            style_numeric_primary_labels.add(primary_label)
            style_numeric_primary_codepoints.update(map(ord, primary_label))

    return {
        "fixture_corpus": {
            "labels_per_key": labels_per_key,
            "main_key_code_range": [_MAIN_KEY_CODE_START, _MAIN_KEY_CODE_END],
            "manifest_path": str(manifest_path.relative_to(REPO_ROOT)),
            "scenario_count": len(scenarios),
            "scene_contract_version": _require_int(
                manifest.get("sceneContractVersion"),
                label="manifest.sceneContractVersion",
            ),
            "style_numeric_role": {
                "name": "STYLE_NUMERIC",
                "value": _STYLE_NUMERIC,
            },
        },
        "lane_labels": lane_labels,
        "lane_codepoints": lane_codepoints,
        "style_numeric_primary_labels": style_numeric_primary_labels,
        "style_numeric_primary_codepoints": style_numeric_primary_codepoints,
    }


def _require_source_snippets(path: Path, snippets: tuple[str, ...]) -> None:
    source = path.read_text(encoding="utf-8")
    for snippet in snippets:
        if snippet not in source:
            raise KeyFontPolicyError.missing_source_contract(path, snippet)


def _validate_owner_contracts() -> None:
    _require_source_snippets(
        _REPLICA_LAYOUT_PATH,
        tuple(
            f'loadTypeface(context, "fonts/{font_name}")'
            for font_name in _FONT_ASSETS.values()
        ),
    )
    _require_source_snippets(
        _TEXT_RENDERER_PATH,
        (
            "internal object C47TextRenderer",
            "isSubpixelText = true",
            "isLinearText = false",
        ),
    )
    _require_source_snippets(
        _TYPEFACE_POLICY_PATH,
        (
            "fun standardFirst(",
            "val standardTypeface = fontSet.standard",
            "return standardTypeface",
            "val tinyTypeface = fontSet.tiny",
            "return tinyTypeface",
        ),
    )
    _require_source_snippets(
        _MAIN_KEY_VIEW_PATH,
        (
            "return C47TypefacePolicy.standardFirst(",
            "text = mainKeyState.primaryLabel",
            "fLabel, gLabel, letterLabel -> C47TypefacePolicy.standardFirst(",
            "override fun drawChild(",
            "drawMainKeyLabels(canvas)",
            "C47TextRenderer.buildLabelSpec(",
            "KeyRenderPainter.drawLabel(",
        ),
    )
    _require_source_snippets(
        _SOFTKEY_PAINTER_PATH,
        (
            "C47TextRenderer.newTextPaint(",
            "C47TextRenderer.buildFittedLabelSpec(",
            "KeyRenderPainter.drawLabel(",
            "typeface = C47TypefacePolicy.standardFirst(",
            "text = valueText",
            "text = keyState.primaryLabel",
            "text = keyState.auxLabel",
            'text = "M"',
        ),
    )


def build_key_font_policy_payload() -> dict[str, object]:
    """Build the live keypad font-policy payload used by the contract suite."""
    _validate_owner_contracts()

    font_payloads: dict[str, dict[str, object]] = {}
    font_codepoints: dict[str, set[int]] = {}
    for alias, font_name in _FONT_ASSETS.items():
        payload, supported_codepoints = _font_payload(font_name)
        font_payloads[alias] = payload
        font_codepoints[alias] = supported_codepoints

    coverage = _collect_fixture_coverage()
    lane_labels = cast("dict[str, set[str]]", coverage.pop("lane_labels"))
    lane_codepoints = cast("dict[str, set[int]]", coverage.pop("lane_codepoints"))
    style_numeric_primary_labels = cast(
        "set[str]",
        coverage.pop("style_numeric_primary_labels"),
    )
    style_numeric_primary_codepoints = cast(
        "set[int]",
        coverage.pop("style_numeric_primary_codepoints"),
    )
    fixture_corpus = cast("dict[str, object]", coverage["fixture_corpus"])

    return {
        "contract_domain": "android_app",
        "fixture_corpus": fixture_corpus,
        "font_assets": font_payloads,
        "font_fallback_policy": _FONT_FALLBACK_POLICY,
        "lane_expectations": {
            lane: {
                "label_slot": _LANE_SLOTS[lane],
                **_coverage_payload(
                    lane_labels[lane],
                    lane_codepoints[lane],
                    font_codepoints=font_codepoints,
                ),
            }
            for lane in _LANE_SLOTS
        },
        "rendering_policy": {
            "label_renderers": {
                "main_key": {
                    "owner": "CalculatorKeyView",
                    "surface": "custom_paint",
                },
                "softkey": {
                    "owner": "CalculatorSoftkeyPainter",
                    "surface": "custom_paint",
                },
            },
            "shared_text_paint_policy": {
                "owner": "C47TextRenderer",
                "anti_alias": True,
                "subpixel_text": True,
                "linear_text": False,
            },
        },
        "style_numeric_primary_expectations": {
            "preferred_fallback_chain": _FONT_FALLBACK_POLICY[
                "main_key_primary_style_numeric"
            ],
            **_coverage_payload(
                style_numeric_primary_labels,
                style_numeric_primary_codepoints,
                font_codepoints=font_codepoints,
            ),
        },
    }


def main() -> int:
    """Write the keypad font-policy payload to standard output as JSON."""
    json.dump(
        build_key_font_policy_payload(),
        sys.stdout,
        indent=2,
        sort_keys=True,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
