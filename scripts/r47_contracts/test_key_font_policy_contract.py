"""Lock the keypad font-policy JSON to live fonts, fixtures, and Kotlin owners.

Two complementary layers (de-circularization):

- `test_key_font_policy_payload_matches_contract_json` is a *freshness* guard --
  it proves the committed JSON is a faithful re-derivation. On its own it is
  circular (re-running the deriver re-blesses any change), so it is not a
  correctness oracle. It has two parts. Each font must keep every code point the
  JSON records, read from the font file, so a lost glyph fails whoever needs it,
  a softkey's included. And the JSON must equal the derivation, with only the
  fonts upstream changed since the provenance ledger recorded them capped at the
  code points the JSON records: so a code point such a font gains passes, and an
  upstream font that grows does not stop every lane behind the contract suite,
  while any other change fails. A hand edit of an upstream-changed font's own
  fields passes until the JSON is re-derived. The ledger's drift report names the
  JSON; re-derive it and record the ledger with `--record` in that commit, since
  both record commands refuse a golden that is not its derivation.
- The remaining tests are the *correctness* oracle: they root in facts that do
  not come from the snapshot -- the font files' names and paths, the documented
  keypad constants, and structural invariants -- so a re-blessed wrong value
  fails here even though the freshness guard would pass. They read the JSON, not
  the fonts: the freshness guard's code point check is what reads the fonts.
"""

from __future__ import annotations

import unittest
from typing import Final

from fontTools.ttLib import TTFont

from r47_contracts._contract_data import (
    load_key_font_policy_contract,
    mapping_member,
    number_member,
    require_mapping,
    string_member,
)
from r47_contracts._repo_paths import REPO_ROOT
from r47_contracts.derive_key_font_policy import build_key_font_policy_payload
from r47_contracts.upstream_provenance import hash_file, load_ledger

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


_SHOWN_LOST_CODEPOINTS: Final = 8
_RANGE_PAIR_LENGTH: Final = 2


def _moved_fonts(payload: dict[str, object]) -> set[str]:
    """Return the fonts, by alias, whose files differ from the ledger's record."""
    recorded = {entry["path"]: entry["sha256"] for entry in load_ledger()["inputs"]}
    fonts = mapping_member(payload, "font_assets", label="font policy")
    moved: set[str] = set()
    for font, asset in fonts.items():
        path = string_member(
            require_mapping(asset, label=font),
            "font_path",
            label=font,
        )
        if hash_file(REPO_ROOT / path) != recorded.get(path):
            moved.add(font)
    return moved


def _cmap_codepoints(payload: dict[str, object]) -> dict[str, set[int]]:
    """Return each font file's code points, read from its cmap, not the deriver."""
    fonts = mapping_member(payload, "font_assets", label="font policy")
    codepoints: dict[str, set[int]] = {}
    for font, asset in fonts.items():
        path = REPO_ROOT / string_member(
            require_mapping(asset, label=font),
            "font_path",
            label=font,
        )
        cmap = TTFont(path).getBestCmap()
        codepoints[font] = set(cmap) if cmap is not None else set()
    return codepoints


def _codepoints(payload: dict[str, object]) -> dict[str, set[int]]:
    """Return each font's code points, from the payload's range pairs."""
    fonts = mapping_member(payload, "font_assets", label="font policy")
    codepoints: dict[str, set[int]] = {}
    for font, asset in fonts.items():
        pairs = require_mapping(asset, label=font).get("unicode_range_pairs")
        if not isinstance(pairs, list):
            message = f"{font}.unicode_range_pairs is not a list"
            raise TypeError(message)
        covered: set[int] = set()
        for pair in pairs:
            if (
                not isinstance(pair, list)
                or len(pair) != _RANGE_PAIR_LENGTH
                or not all(
                    isinstance(end, int) and not isinstance(end, bool) for end in pair
                )
            ):
                message = f"{font}.unicode_range_pairs holds {pair!r}"
                raise TypeError(message)
            covered.update(range(pair[0], pair[1] + 1))
        codepoints[font] = covered
    return codepoints


class KeyFontPolicyContractTest(unittest.TestCase):
    """Verify the font contract is a fresh re-derivation and independently correct."""

    def test_key_font_policy_payload_matches_contract_json(self) -> None:
        """Freshness guard in two parts (module docstring)."""
        expected = load_key_font_policy_contract()
        recorded = _codepoints(expected)
        live = _cmap_codepoints(expected)
        lost = {
            font: sorted(codepoints - live.get(font, set()))
            for font, codepoints in recorded.items()
            if codepoints - live.get(font, set())
        }
        if lost:
            shown = {
                font: [f"U+{c:04X}" for c in codepoints[:_SHOWN_LOST_CODEPOINTS]]
                + (
                    [f"and {len(codepoints) - _SHOWN_LOST_CODEPOINTS} more"]
                    if len(codepoints) > _SHOWN_LOST_CODEPOINTS
                    else []
                )
                for font, codepoints in lost.items()
            }
            message = f"A font lost code points the contract was derived with: {shown}"
            raise AssertionError(message)
        # A font whose bytes the ledger recorded must derive to the JSON whole: both
        # record commands refuse a golden that is not its derivation. Only a font
        # upstream changed since is capped at the code points the JSON records.
        moved = _moved_fonts(expected)
        limits = {font: recorded[font] for font in moved}
        if build_key_font_policy_payload(codepoint_limits=limits) != expected:
            message = (
                "Keypad font-policy contract drifted from its derivation"
                + (
                    f" with {sorted(moved)} capped at the code points it records"
                    if moved
                    else ""
                )
                + ": re-derive it in the commit that changed its inputs"
            )
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
