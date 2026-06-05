"""Lock the keypad font-policy JSON to live fonts, fixtures, and Kotlin owners.

Two complementary layers (REPORT-24 Milestone 4 de-circularization):

- `test_key_font_policy_payload_matches_contract_json` is a *freshness* guard --
  it proves the committed JSON is a faithful re-derivation. On its own it is
  circular (re-running the deriver re-blesses any change), so it is not a
  correctness oracle.
- The remaining tests are the *correctness* oracle: they root in facts that do
  not come from the snapshot -- the real font files on disk, the documented
  keypad constants, and structural invariants -- so a re-blessed wrong value
  fails here even though the freshness guard would pass.
"""

from __future__ import annotations

import unittest
from typing import Final

from r47_contracts._contract_data import (
    load_key_font_policy_contract,
    mapping_member,
    number_member,
    require_mapping,
    string_member,
)
from r47_contracts._repo_paths import REPO_ROOT
from r47_contracts.derive_key_font_policy import build_key_font_policy_payload

_EXPECTED_FONT_ASSETS: Final = {
    "numeric": "C47__NumericFont.ttf",
    "standard": "C47__StandardFont.ttf",
    "tiny": "C47__TinyFont.ttf",
}
_FONT_ASSET_DIR: Final = "res/fonts"
_EXPECTED_LABELS_PER_KEY: Final = 5
_EXPECTED_MAIN_KEY_CODE_RANGE: Final = [1, 37]
_EXPECTED_LANES: Final = {"f", "g", "letter", "primary"}
_MIN_POSITIVE_COUNT: Final = 1


class KeyFontPolicyContractTest(unittest.TestCase):
    """Verify the font contract is a fresh re-derivation and independently correct."""

    def test_key_font_policy_payload_matches_contract_json(self) -> None:
        """Freshness guard: keep the font-policy JSON a faithful re-derivation."""
        actual = build_key_font_policy_payload()
        expected = load_key_font_policy_contract()
        if actual != expected:
            message = "Keypad font-policy contract drifted from the live payload"
            raise AssertionError(message)

    def test_font_assets_name_path_and_files_exist(self) -> None:
        """Each font asset keeps its name, res/fonts path, and a real file on disk."""
        font_assets = mapping_member(
            load_key_font_policy_contract(),
            "font_assets",
            label="font_assets",
        )
        if set(font_assets) != set(_EXPECTED_FONT_ASSETS):
            message = (
                f"font_assets keys drifted from {sorted(_EXPECTED_FONT_ASSETS)}: "
                f"{sorted(font_assets)}"
            )
            raise AssertionError(message)
        for key, expected_name in _EXPECTED_FONT_ASSETS.items():
            asset = mapping_member(font_assets, key, label=f"font_assets.{key}")
            expected_path = f"{_FONT_ASSET_DIR}/{expected_name}"
            asset_name = string_member(asset, "asset_name", label=f"{key}.asset_name")
            if asset_name != expected_name:
                message = f"font asset {key} name drifted: {asset_name!r}"
                raise AssertionError(message)
            font_path = string_member(asset, "font_path", label=f"{key}.font_path")
            if font_path != expected_path:
                message = f"font asset {key} path drifted: {font_path!r}"
                raise AssertionError(message)
            if not (REPO_ROOT / expected_path).is_file():
                message = f"font asset file missing on disk: {expected_path}"
                raise AssertionError(message)
            codepoints = number_member(
                asset,
                "unicode_codepoint_count",
                label=f"{key}.codepoints",
            )
            if codepoints < _MIN_POSITIVE_COUNT:
                message = (
                    f"font asset {key} codepoint count is not positive: {codepoints!r}"
                )
                raise AssertionError(message)

    def test_fallback_chains_reference_only_known_fonts(self) -> None:
        """Every fallback chain is non-empty and names only declared font assets."""
        contract = load_key_font_policy_contract()
        known_fonts = set(mapping_member(contract, "font_assets", label="font_assets"))
        fallback = mapping_member(
            contract,
            "font_fallback_policy",
            label="font_fallback_policy",
        )
        for role, chain in fallback.items():
            if not isinstance(chain, list) or not chain:
                message = f"font fallback chain for {role} is empty or not a list"
                raise AssertionError(message)
            unknown = [font for font in chain if font not in known_fonts]
            if unknown:
                message = (
                    f"font fallback chain for {role} references unknown fonts {unknown}"
                )
                raise AssertionError(message)

    def test_fixture_corpus_keypad_constants(self) -> None:
        """The fixture corpus keeps the documented keypad shape constants."""
        corpus = mapping_member(
            load_key_font_policy_contract(),
            "fixture_corpus",
            label="fixture_corpus",
        )
        labels_per_key = number_member(corpus, "labels_per_key", label="labels_per_key")
        if labels_per_key != _EXPECTED_LABELS_PER_KEY:
            message = (
                f"labels_per_key drifted from {_EXPECTED_LABELS_PER_KEY}: "
                f"{labels_per_key!r}"
            )
            raise AssertionError(message)
        main_key_code_range = corpus["main_key_code_range"]
        if main_key_code_range != _EXPECTED_MAIN_KEY_CODE_RANGE:
            message = (
                f"main_key_code_range drifted from {_EXPECTED_MAIN_KEY_CODE_RANGE}: "
                f"{main_key_code_range!r}"
            )
            raise AssertionError(message)

    def test_lane_expectations_cover_every_label_lane(self) -> None:
        """Every keypad label lane declares a positive distinct-label count."""
        lanes = mapping_member(
            load_key_font_policy_contract(),
            "lane_expectations",
            label="lane_expectations",
        )
        if set(lanes) != _EXPECTED_LANES:
            message = (
                f"lane_expectations keys drifted from {sorted(_EXPECTED_LANES)}: "
                f"{sorted(lanes)}"
            )
            raise AssertionError(message)
        for lane, expectation in lanes.items():
            lane_mapping = require_mapping(
                expectation,
                label=f"lane_expectations.{lane}",
            )
            distinct_labels = number_member(
                lane_mapping,
                "distinct_label_count",
                label=f"{lane}.distinct_label_count",
            )
            if distinct_labels < _MIN_POSITIVE_COUNT:
                message = (
                    f"lane {lane} distinct_label_count is not positive: "
                    f"{distinct_labels!r}"
                )
                raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
