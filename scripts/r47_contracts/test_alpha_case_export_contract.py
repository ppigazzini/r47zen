"""Audit repo sources that implement alpha-case export and layout behavior."""

from __future__ import annotations

import re
import unittest

from r47_contracts._repo_paths import (
    ANDROID_CPP_ROOT,
    KOTLIN_R47_ROOT,
    STAGED_NATIVE_C47_ROOT,
)

JNI_DISPLAY_PATH = ANDROID_CPP_ROOT / "jni_display.c"
ASSIGN_PATH = STAGED_NATIVE_C47_ROOT / "assign.c"
ITEMS_PATH = STAGED_NATIVE_C47_ROOT / "items.c"
KEY_VIEW_PATH = KOTLIN_R47_ROOT / "CalculatorKeyView.kt"
KEYPAD_LAYOUT_PATH = KOTLIN_R47_ROOT / "ReplicaKeypadLayout.kt"


def _assert_true(condition: object) -> None:
    if not condition:
        raise AssertionError


class AlphaCaseExportContractTest(unittest.TestCase):
    """Verify repo sources keep the alpha-case export contract intact."""

    @classmethod
    def setUpClass(cls) -> None:
        """Load shared fixtures for the test class."""
        cls.jni_display = JNI_DISPLAY_PATH.read_text(encoding="utf-8")
        cls.assign = ASSIGN_PATH.read_text(encoding="utf-8")
        cls.items = ITEMS_PATH.read_text(encoding="utf-8")
        cls.key_view = KEY_VIEW_PATH.read_text(encoding="utf-8")
        cls.keypad_layout = KEYPAD_LAYOUT_PATH.read_text(encoding="utf-8")

    def test_staged_alpha_arrows_still_map_to_case_items(self) -> None:
        """Staged alpha arrows still map to case items."""
        _assert_true(re.search(r"CHR_caseUP\s*,\s*ITM_UP_ARROW", self.assign))
        _assert_true(re.search(r"CHR_caseDN\s*,\s*ITM_DOWN_ARROW", self.assign))

    def test_staged_items_define_full_case_labels(self) -> None:
        """Staged items define full case labels."""
        _assert_true('"CASE UP"' in self.items)
        _assert_true('"CASE DN"' in self.items)

    def test_jni_does_not_truncate_case_labels(self) -> None:
        """Jni does not truncate case labels."""
        _assert_true('"CASE "' not in self.jni_display)

    def test_jni_uses_core_case_selection_rule(self) -> None:
        """Jni uses core case selection rule."""
        _assert_true(
            re.search(
                re.compile(
                    r"static bool_t isLowercaseAlphaSelected\(void\) \{"
                    r"\s*return \(alphaCase == AC_LOWER && !shiftF\) \|\|"
                    r"\s*\(alphaCase == AC_UPPER && shiftF\);"
                    r"\s*\}",
                    re.DOTALL,
                ),
                self.jni_display,
            ),
        )

    def test_jni_matches_upstream_alphabetic_key_gate(self) -> None:
        """Jni matches upstream alphabetic key gate."""
        _assert_true(
            re.search(
                re.compile(
                    r"static bool_t isAlphaKeyboardActive\(void\) \{\s*"
                    r"extern bool_t getSystemFlag\(int32_t sf\);\s*"
                    r"bool_t alphaFlag = getSystemFlag\(FLAG_ALPHA\);\s*"
                    r"return \(calcMode == CM_AIM\) \|\|\s*"
                    r"\(catalog && catalog != CATALOG_MVAR &&"
                    r" calcMode != CM_NIM\) \|\|\s*"
                    r"\(calcMode == CM_EIM\) \|\| tam\.alpha \|\|\s*"
                    r"\(calcMode == CM_ASSIGN &&\s*"
                    r"\(previousCalcMode == CM_AIM \|\|"
                    r" previousCalcMode == CM_EIM\)\) \|\|\s*"
                    r"\(calcMode == CM_PEM && alphaFlag\);\s*"
                    r"\}",
                    re.DOTALL,
                ),
                self.jni_display,
            ),
        )

    def test_jni_limits_case_swaps_to_latin_pairs(self) -> None:
        """Jni limits case swaps to latin pairs."""
        _assert_true("upper[0] >= 'A' && upper[0] <= 'Z'" in self.jni_display)
        _assert_true("lower[0] == (upper[0] - 'A' + 'a')" in self.jni_display)

    def test_jni_promotes_dynamic_alpha_preview_into_primary_label(self) -> None:
        """Jni promotes dynamic alpha preview into primary label."""
        _assert_true(
            re.search(
                re.compile(
                    r"case KEYPAD_LABEL_PRIMARY:\s*"
                    r"if \(previewF\) \{\s*return key->fShiftedAim;\s*\}"
                    r"\s*if \(previewG\) \{\s*return key->gShiftedAim;\s*\}"
                    r"\s*return \(casePair && lowercaseSelected\) \? key->fShiftedAim"
                    r"\s*:\s*key->primaryAim;",
                    re.DOTALL,
                ),
                self.jni_display,
            ),
        )

    def test_jni_keeps_static_alpha_second_and_third_slots(self) -> None:
        """Jni keeps static alpha second and third slots."""
        _assert_true(
            re.search(
                re.compile(
                    r"case KEYPAD_LABEL_F:\s*return key->fShiftedAim;",
                    re.DOTALL,
                ),
                self.jni_display,
            ),
        )
        _assert_true(
            re.search(
                re.compile(
                    r"case KEYPAD_LABEL_G:\s*return key->gShiftedAim;",
                    re.DOTALL,
                ),
                self.jni_display,
            ),
        )

    def test_jni_uses_dynamic_roles_for_dynamic_snapshot_meta(self) -> None:
        """Jni uses dynamic roles for dynamic snapshot meta."""
        _assert_true(
            "resolveMainLabelRoles(key, keyCode, isDynamic, alphaOn);"
            in self.jni_display,
        )

    def test_main_key_primary_label_fits_to_button_width(self) -> None:
        """Main key primary label fits to button width."""
        _assert_true(
            "val primaryMaxWidth = "
            "primaryLabelMaxWidthPx(referenceCellToViewWidthScale)" in self.key_view,
        )
        _assert_true(
            "fittedTextSizePx(primaryLabel, primarySize, primaryMaxWidth)"
            in self.key_view,
        )
        _assert_true(
            ".coerceAtLeast(baseSize * R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE)"
            in self.key_view,
        )

    def test_alpha_layout_keeps_letter_spacer_reserved_and_hidden(self) -> None:
        """Alpha layout keeps letter spacer reserved and hidden."""
        _assert_true(
            re.search(
                re.compile(
                    r"if \(usesLetterSpacer\) \{\s*"
                    r"buttonParams\.endToStart = letterLabel\.id\s*"
                    r"buttonParams\.endToEnd = LayoutParams\.UNSET\s*"
                    r"\} else \{\s*"
                    r"buttonParams\.endToStart = LayoutParams\.UNSET\s*"
                    r"buttonParams\.endToEnd = LayoutParams\.PARENT_ID",
                    re.DOTALL,
                ),
                self.key_view,
            ),
        )
        _assert_true(
            "keyState.layoutClass == KeypadSceneContract.LAYOUT_CLASS_ALPHA ||"
            in self.key_view,
        )
        _assert_true("letterLabel.visibility = View.INVISIBLE" in self.key_view)

    def test_alpha_layout_refreshes_offsets_without_forcing_lane_reset(self) -> None:
        """Alpha layout refreshes offsets without forcing lane reset."""
        _assert_true(
            re.search(
                re.compile(
                    r"override fun onSizeChanged\(w: Int, h: Int,"
                    r" oldw: Int, oldh: Int\) \{\s*"
                    r"super\.onSizeChanged\(w, h, oldw, oldh\)\s*"
                    r"updateFontSize\(currentShiftFOn, currentShiftGOn\)\s*"
                    r"updateFaceplateOffsets\(\)\s*"
                    r"\}",
                    re.DOTALL,
                ),
                self.key_view,
            ),
        )
        _assert_true(
            re.search(
                re.compile(
                    r"override fun onLayout\(changed: Boolean, left: Int,"
                    r" top: Int, right: Int, bottom: Int\) \{\s*"
                    r"super\.onLayout\(changed, left, top, right, bottom\)\s*"
                    r"updateFaceplateOffsets\(\)\s*"
                    r"\}",
                    re.DOTALL,
                ),
                self.key_view,
            ),
        )
        _assert_true(
            "internal fun applyTopLabelPlacementsAfterLayout(overlay: ReplicaOverlay) {"
            in self.keypad_layout,
        )
        _assert_true(
            re.search(
                re.compile(
                    r"mainKeyState = keyState\s*"
                    r"primaryLabel\.text = keyState\.primaryLabel\s*"
                    r"fLabel\.text = keyState\.fLabel\s*"
                    r"gLabel\.text = keyState\.gLabel\s*"
                    r"letterLabel\.text = keyState\.letterLabel\s*"
                    r"currentShiftFOn = resolvedShiftFOn\s*"
                    r"currentShiftGOn = resolvedShiftGOn\s*"
                    r"updateLayoutPositioning\(keyState\.layoutClass\)\s*"
                    r"applySceneStyling\(keyState\)\s*"
                    r"applyLabelVisibility\(keyState\)\s*"
                    r"updateFontSize\(currentShiftFOn, currentShiftGOn\)\s*"
                    r"requestLayout\(\)\s*"
                    r"invalidate\(\)",
                    re.DOTALL,
                ),
                self.key_view,
            ),
        )
        _assert_true(
            re.search(
                re.compile(
                    r"internal fun applyTopLabelPlacement\("
                    r"placement: TopLabelLanePlacement\?\) \{.*?"
                    r"val resolvedPlacement = placement"
                    r" \?: TopLabelLanePlacement\.DEFAULT\s*"
                    r"if \(topLabelPlacement == resolvedPlacement\) \{\s*return\s*\}\s*"
                    r"val scaleChanged =\s*"
                    r"topLabelPlacement\.fScale != resolvedPlacement\.fScale \|\|\s*"
                    r"topLabelPlacement\.gScale != resolvedPlacement\.gScale\s*"
                    r"topLabelPlacement = resolvedPlacement\s*"
                    r"if \(scaleChanged\) \{\s*"
                    r"updateFontSize\(currentShiftFOn, currentShiftGOn\)\s*"
                    r"requestLayout\(\)\s*"
                    r"\} else \{\s*"
                    r"updateFaceplateOffsets\(\)",
                    re.DOTALL,
                ),
                self.key_view,
            ),
        )
        _assert_true(
            not re.search(
                re.compile(
                    r"internal fun updateLabels\(snapshot: KeypadSnapshot\) \{.*?"
                    r"topLabelPlacement = TopLabelLanePlacement\.DEFAULT",
                    re.DOTALL,
                ),
                self.key_view,
            ),
        )


if __name__ == "__main__":
    unittest.main()
