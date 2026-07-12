#!/bin/bash

# Mutation spot-check for the hardened pure seams (REPORT-24 Milestone 5; widened
# in REPORT-28 Milestone C to the keypad decoder, LCD theme, and graph-gesture
# accumulator). It applies a small set of compile-clean semantic mutations to
# those seams and asserts each one is KILLED by the JVM unit tests. This is an
# empirical measure of assertion strength -- do the tests actually fail when the
# production logic is broken? -- not a CI gate: it recompiles per mutation and is
# meant for manual maintainer runs.
#
# A mutant is "killed" when the targeted test fails with the mutation applied and
# "survived" when the test still passes, which marks an assertion gap. The
# original sources are always restored, including on error.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
KOTLIN_ROOT="$ANDROID_DIR/app/src/main/java/io/github/ppigazzini/r47zen"

POLICY_FILE="$KOTLIN_ROOT/LiveProgramStopKeyPolicy.kt"
ROUTER_FILE="$KOTLIN_ROOT/LiveKeyRouter.kt"
DECODER_FILE="$KOTLIN_ROOT/KeypadSnapshot.kt"
THEME_FILE="$KOTLIN_ROOT/LcdThemePolicy.kt"
GESTURE_FILE="$KOTLIN_ROOT/GraphGestureAccumulator.kt"
POLICY_TEST="io.github.ppigazzini.r47zen.LiveProgramStopKeyPolicyTest"
ROUTER_TEST="io.github.ppigazzini.r47zen.LiveKeyRouterTest"
DECODER_TEST="io.github.ppigazzini.r47zen.KeypadSnapshotDecoderTest"
THEME_TEST="io.github.ppigazzini.r47zen.LcdThemePolicyTest"
GESTURE_TEST="io.github.ppigazzini.r47zen.GraphGestureAccumulatorTest"

# Parallel mutation records: file, exact unique old text, mutated text, the test
# class that must catch it, and a human description.
MUT_FILES=(
    "$POLICY_FILE"
    "$POLICY_FILE"
    "$ROUTER_FILE"
    "$ROUTER_FILE"
    "$DECODER_FILE"
    "$DECODER_FILE"
    "$THEME_FILE"
    "$THEME_FILE"
    "$GESTURE_FILE"
    "$GESTURE_FILE"
)
MUT_OLD=(
    "const val EXIT_KEY_CODE = 33"
    "return keyCode == RUN_STOP_KEY_CODE || keyCode == EXIT_KEY_CODE"
    "LiveProgramStopKeyPolicy.shouldPublishDirectStop(keyCode) && queryDirectStopGate()"
    "if (!consumedAsDirectStop) {"
    "private fun keyMetaAt(offset: Int, index: Int): Int = meta[offset + index]"
    "return labels.getOrElse(index * LABELS_PER_KEY + slot) { \"\" }"
    "return entries.firstOrNull { it.storageValue == value } ?: DEFAULT"
    "return minimumLuminance + ((maximumLuminance - minimumLuminance) * fraction)"
    "return sign(value) * panApplyLimit"
    "return value.coerceIn(-panPendingLimit, panPendingLimit)"
)
MUT_NEW=(
    "const val EXIT_KEY_CODE = 34"
    "return keyCode == RUN_STOP_KEY_CODE"
    "LiveProgramStopKeyPolicy.shouldPublishDirectStop(keyCode) || queryDirectStopGate()"
    "if (consumedAsDirectStop) {"
    "private fun keyMetaAt(offset: Int, index: Int): Int = meta[offset]"
    "return labels.getOrElse(index * LABELS_PER_KEY) { \"\" }"
    "return entries.firstOrNull { it.storageValue == value } ?: AMBER"
    "return minimumLuminance"
    "return value"
    "return value"
)
MUT_TESTS=(
    "$POLICY_TEST"
    "$POLICY_TEST"
    "$ROUTER_TEST"
    "$ROUTER_TEST"
    "$DECODER_TEST"
    "$DECODER_TEST"
    "$THEME_TEST"
    "$THEME_TEST"
    "$GESTURE_TEST"
    "$GESTURE_TEST"
)
MUT_DESC=(
    "LiveProgramStopKeyPolicy: EXIT key code 33 -> 34"
    "LiveProgramStopKeyPolicy: drop EXIT from the direct-stop key set"
    "LiveKeyRouter: AND -> OR (query the gate for non-stop keys)"
    "LiveKeyRouter: invert the forward-vs-swallow decision"
    "KeypadSnapshot: drop the per-key index from the meta-lane read"
    "KeypadSnapshot: drop the label slot from the label-lane read"
    "LcdThemePolicy: unknown theme falls back to AMBER instead of DEFAULT"
    "LcdThemePolicy: collapse the luminance interpolation to its minimum"
    "GraphGestureAccumulator: drop the per-apply pan-step clamp"
    "GraphGestureAccumulator: drop the pending-backlog cap"
)

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

# Score a mutant from the JUnit results XML, not the raw Gradle exit code.
# Gradle exits non-zero both when a test FAILS (the real kill signal) and when
# "No tests found for given includes" or a daemon/lock error occurs -- the
# latter would miscount a renamed/deleted test class as a phantom kill forever.
# Prints exactly one of KILLED / SURVIVED / NOTESTS on stdout; a missing results
# file is a hard error (the mutant could not be scored).
score_mutant() {
    local test_class="$1"
    local results_dir="$ANDROID_DIR/app/build/test-results/testReleaseUnitTest"
    local results_file="$results_dir/TEST-${test_class}.xml"

    rm -f "$results_file"
    # Ignore Gradle's exit code on purpose: the verdict comes from the XML below.
    (cd "$ANDROID_DIR" && ./gradlew :app:testReleaseUnitTest \
        --tests "$test_class" \
        --console=plain -Pr47.testBuildType=release >/dev/null 2>&1) || true

    if [[ ! -f "$results_file" ]]; then
        fail "no JUnit results for $test_class (the test class did not run -- renamed or deleted?); the mutant cannot be scored"
    fi

    python3 - "$results_file" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
tests = int(root.get("tests", "0"))
failures = int(root.get("failures", "0"))
errors = int(root.get("errors", "0"))
if tests == 0:
    print("NOTESTS")
else:
    print("KILLED" if (failures + errors) > 0 else "SURVIVED")
PY
}

restore_backups() {
    local backup
    for backup in "$KOTLIN_ROOT"/*.mutbak; do
        [[ -e "$backup" ]] || continue
        mv -f "$backup" "${backup%.mutbak}"
    done
}

apply_mutation() {
    local file="$1"
    local old="$2"
    local new="$3"

    python3 - "$file" "$old" "$new" <<'PY'
import sys

path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
count = text.count(old)
if count != 1:
    sys.exit(f"mutation target appears {count} times (need exactly 1) in {path}: {old}")
open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
PY
}

trap restore_backups EXIT

command -v python3 >/dev/null 2>&1 || fail "python3 is required"
for seam_file in "$POLICY_FILE" "$ROUTER_FILE" "$DECODER_FILE" "$THEME_FILE" "$GESTURE_FILE"; do
    [[ -f "$seam_file" ]] || fail "seam source not found: $seam_file"
done

killed=0
survived=0
total=${#MUT_FILES[@]}

for index in "${!MUT_FILES[@]}"; do
    file="${MUT_FILES[$index]}"
    description="${MUT_DESC[$index]}"

    cp -f "$file" "$file.mutbak"
    if ! apply_mutation "$file" "${MUT_OLD[$index]}" "${MUT_NEW[$index]}"; then
        fail "could not apply mutation cleanly: $description"
    fi

    echo "--- mutant: $description"
    # A mutant that does not compile would make testReleaseUnitTest exit non-zero
    # for the wrong reason and be miscounted as killed. Require the mutated main
    # source to compile first: a non-compiling mutant is a bad mutation spec (fix
    # it), not evidence the tests caught the change.
    if ! (cd "$ANDROID_DIR" && ./gradlew :app:compileReleaseKotlin \
        --console=plain -Pr47.testBuildType=release >/dev/null 2>&1); then
        fail "mutant did not compile: $description (fix the mutation spec; a non-compiling mutant is a false kill)"
    fi
    verdict="$(score_mutant "${MUT_TESTS[$index]}")"
    case "$verdict" in
        KILLED)
            echo "killed:   $description"
            killed=$((killed + 1))
            ;;
        SURVIVED)
            echo "SURVIVED: $description (${MUT_TESTS[$index]} did not catch it)"
            survived=$((survived + 1))
            ;;
        *)
            fail "test class ${MUT_TESTS[$index]} executed zero tests; cannot score mutant: $description"
            ;;
    esac

    mv -f "$file.mutbak" "$file"
done

echo
echo "Mutation spot-check: $killed/$total mutants killed."
if [[ "$survived" -gt 0 ]]; then
    fail "$survived mutant(s) survived -- the seam tests have an assertion gap"
fi
echo "All seam mutants were caught."
