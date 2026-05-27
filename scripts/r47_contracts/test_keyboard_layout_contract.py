"""Exercise the live R47 keyboard-layout contract against the current tree."""

from __future__ import annotations

import unittest
from typing import ClassVar, cast

from r47_contracts._contract_data import load_keyboard_layout_contract
from r47_contracts.derive_keyboard_layout_contract import (
    ASSIGN_VARIANT_TABLE_NAMES,
    EXPECTED_VARIANT_KEY_COUNT,
    R47_VARIANTS,
    build_keyboard_layout_contract_payload,
)


def _assert_equal(actual: object, expected: object) -> None:
    if actual != expected:
        message = f"Expected {expected!r}, got {actual!r}"
        raise AssertionError(message)


def _assert_true(condition: object) -> None:
    if not condition:
        raise AssertionError


class KeyboardLayoutContractTest(unittest.TestCase):
    """Verify the maintained keyboard-layout contract against live repo sources."""

    expected_contract: ClassVar[dict[str, object]]
    canonical_source: ClassVar[dict[str, object]]
    variant_key_counts: ClassVar[dict[str, int]]
    shift_legends: ClassVar[dict[str, dict[str, str]]]
    assign_shift_rows: ClassVar[dict[str, dict[str, str]]]
    representative_assign_rows: ClassVar[dict[str, dict[str, str]]]
    core_display_names: ClassVar[dict[str, str]]
    android_static_overrides: ClassVar[dict[str, dict[str, str]]]
    android_formatter_assists: ClassVar[dict[str, bool]]
    android_fixture_expectations: ClassVar[dict[str, dict[str, dict[str, str]]]]

    @classmethod
    def setUpClass(cls) -> None:
        """Load the derived contract payload once for the class."""
        payload = build_keyboard_layout_contract_payload()
        cls.expected_contract = load_keyboard_layout_contract()
        cls.canonical_source = cast("dict[str, object]", payload["canonical_source"])
        cls.variant_key_counts = cast(
            "dict[str, int]",
            payload["variant_key_counts"],
        )
        cls.shift_legends = cast(
            "dict[str, dict[str, str]]",
            payload["shift_legends"],
        )
        cls.assign_shift_rows = cast(
            "dict[str, dict[str, str]]",
            payload["assign_shift_rows"],
        )
        cls.representative_assign_rows = cast(
            "dict[str, dict[str, str]]",
            payload["representative_assign_rows"],
        )
        cls.core_display_names = cast(
            "dict[str, str]",
            payload["core_display_names"],
        )
        cls.android_static_overrides = cast(
            "dict[str, dict[str, str]]",
            payload["android_static_overrides"],
        )
        cls.android_formatter_assists = cast(
            "dict[str, bool]",
            payload["android_formatter_assists"],
        )
        cls.android_fixture_expectations = cast(
            "dict[str, dict[str, dict[str, str]]]",
            payload["android_fixture_expectations"],
        )

    def test_keyboard_layout_payload_matches_contract_json(self) -> None:
        """Keep the canonical keyboard-layout JSON aligned with the live payload."""
        actual = build_keyboard_layout_contract_payload()
        if actual != self.expected_contract:
            message = "Keyboard-layout contract drifted from the live payload"
            raise AssertionError(message)

    def test_canonical_source_points_at_assign_items_and_android_bridge(self) -> None:
        """The maintained keyboard audit is rooted in assign.c, items.c, and JNI."""
        _assert_equal(self.canonical_source["assign_path"], "src/c47/assign.c")
        _assert_equal(self.canonical_source["items_path"], "src/c47/items.c")
        _assert_equal(
            self.canonical_source["jni_display_path"],
            "android/app/src/main/cpp/r47zen/jni_display.c",
        )

    def test_assign_tables_expose_complete_r47_variant_family(self) -> None:
        """Assign tables expose the full four-variant R47 family with 37 keys each."""
        for variant_name in R47_VARIANTS:
            _assert_equal(
                self.variant_key_counts[variant_name],
                EXPECTED_VARIANT_KEY_COUNT,
            )

    def test_items_define_shift_legends_for_the_r47_variant_family(self) -> None:
        """Assign primary items resolve through items.c to the expected shift labels."""
        _assert_equal(self.core_display_names["ITM_SHIFTf"], "f")
        _assert_equal(self.core_display_names["ITM_SHIFTg"], "g")
        _assert_equal(self.core_display_names["KEY_fg"], "f/g")
        _assert_equal(self.shift_legends["R47"], {"35": "f", "36": "g"})
        _assert_equal(self.shift_legends["R47bkfg"], {"35": "", "36": "f/g"})
        _assert_equal(self.shift_legends["R47fgbk"], {"35": "f/g", "36": ""})
        _assert_equal(self.shift_legends["R47fg_g"], {"35": "f/g", "36": "g"})

    def test_assign_tables_keep_shift_variant_structure(self) -> None:
        """Generated assign tables keep the expected shift-variant structure."""
        _assert_equal(
            self.assign_shift_rows["R47"],
            {"35": "ITM_SHIFTf", "36": "ITM_SHIFTg"},
        )
        _assert_equal(
            self.assign_shift_rows["R47bkfg"],
            {"35": "ITM_NULL", "36": "KEY_fg"},
        )
        _assert_equal(
            self.assign_shift_rows["R47fgbk"],
            {"35": "KEY_fg", "36": "ITM_NULL"},
        )
        _assert_equal(
            self.assign_shift_rows["R47fg_g"],
            {"35": "KEY_fg", "36": "ITM_SHIFTg"},
        )

        _assert_equal(
            set(self.assign_shift_rows),
            set(ASSIGN_VARIANT_TABLE_NAMES),
        )

    def test_assign_tables_keep_representative_semantic_rows(self) -> None:
        """Generated assign tables keep the representative semantic rows we rely on."""
        _assert_equal(
            self.representative_assign_rows["51"],
            {
                "primary": "ITM_XEQ",
                "f_shifted": "ITM_AIM",
                "g_shifted": "ITM_GTO",
            },
        )
        _assert_equal(
            self.representative_assign_rows["52"],
            {
                "primary": "ITM_7",
                "f_shifted": "ITM_sin",
                "g_shifted": "ITM_arcsin",
            },
        )
        _assert_equal(
            self.representative_assign_rows["61"],
            {
                "primary": "ITM_UP1",
                "f_shifted": "ITM_BST",
                "g_shifted": "ITM_RBR",
            },
        )
        _assert_equal(
            self.representative_assign_rows["81"],
            {
                "primary": "ITM_EXIT1",
                "f_shifted": "ITM_OFF",
                "g_shifted": "-MNU_INFO",
            },
        )
        _assert_equal(
            self.representative_assign_rows["84"],
            {
                "primary": "ITM_RS",
                "f_shifted": "ITM_PR",
                "g_shifted": "-MNU_PFN",
            },
        )
        _assert_equal(
            self.representative_assign_rows["85"],
            {
                "primary": "ITM_ADD",
                "f_shifted": "-MNU_CATALOG",
                "g_shifted": "-MNU_CONST",
            },
        )

    def test_android_static_overrides_stay_limited_to_renderer_specific_exceptions(
        self,
    ) -> None:
        """Key 37 follows the shared visible-space formatter, not a slot literal."""
        _assert_equal(self.core_display_names["MNU_HOME"], "HOME")
        _assert_equal(self.core_display_names["MNU_MyMenu"], "MyM")
        _assert_true("11" not in self.android_static_overrides)
        _assert_true("12" not in self.android_static_overrides)
        _assert_true("37" not in self.android_static_overrides)

    def test_android_formatter_assists_stay_explicit(self) -> None:
        """Android label compaction and glyph assists remain explicit code policy."""
        _assert_true(self.android_formatter_assists["space_placeholder"])
        _assert_true(self.android_formatter_assists["mode_hash_to_hash"])
        _assert_true(self.android_formatter_assists["linpol_to_lin"])
        _assert_true(self.android_formatter_assists["slash_to_divide_glyph_in_tam"])

    def test_android_fixture_expectations_capture_representative_app_layouts(
        self,
    ) -> None:
        """Representative keypad fixtures stay aligned with the shared contract.

        This keeps the shared JSON resource anchored to current exported app
        states.
        """
        _assert_equal(
            self.android_fixture_expectations["default-keypad"]["11"],
            {
                "primary_label": "f",
                "f_label": "HOME",
                "g_label": "",
                "letter_label": "",
                "aux_label": "",
            },
        )
        _assert_equal(
            self.android_fixture_expectations["default-keypad"]["11"]["primary_label"],
            self.core_display_names["ITM_SHIFTf"],
        )
        _assert_equal(
            self.android_fixture_expectations["default-keypad"]["12"],
            {
                "primary_label": "g",
                "f_label": "MyM",
                "g_label": "",
                "letter_label": "",
                "aux_label": "",
            },
        )
        _assert_equal(
            self.android_fixture_expectations["default-keypad"]["12"]["primary_label"],
            self.core_display_names["ITM_SHIFTg"],
        )
        _assert_equal(
            self.android_fixture_expectations["default-keypad"]["12"]["f_label"],
            self.core_display_names["MNU_MyMenu"],
        )
        _assert_equal(
            self.android_fixture_expectations["shift-f-preview"]["35"]["primary_label"],
            "SHOW",
        )
        _assert_equal(
            self.android_fixture_expectations["shift-g-preview"]["35"]["primary_label"],
            "a b/c",
        )
        _assert_equal(
            self.android_fixture_expectations["default-keypad"]["37"]["letter_label"],
            "·_·",
        )
        for scene_expectations in self.android_fixture_expectations.values():
            for key_expectation in scene_expectations.values():
                _assert_true(key_expectation["f_label"] != "CUST")


if __name__ == "__main__":
    unittest.main()
