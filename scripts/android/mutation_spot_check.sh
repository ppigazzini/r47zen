#!/bin/bash

# Mutation spot-check for the hardened seams: the physical-key ids and
# shortcuts, the graph-gesture flusher and accumulator, the keypad decoder, the LCD theme and
# raster, Paste Number's tokens, the native file-request gate, the document
# transfer, the saved manual's grant, and the f-key ASSIGN gate.
# It applies a small set of compile-clean semantic mutations to those seams and
# asserts each one is KILLED by the JVM unit tests: an empirical measure of
# assertion strength -- do the tests actually fail when the production logic is
# broken? The android-tests job in android-ci.yml runs it on every event and
# fails on a survivor; run it locally after changing a seam or its test.
#
# A mutant is "killed" when the targeted test fails with the mutation applied and
# "survived" when the test still passes, which marks an assertion gap. The
# original sources are always restored, including on error.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# R47_MUTATION_ANDROID_DIR points the run at another tree: the test-integrity
# contract runs this script against a sandbox with a fake gradlew.
ANDROID_DIR="${R47_MUTATION_ANDROID_DIR:-$PROJECT_ROOT/android}"
KOTLIN_ROOT="$ANDROID_DIR/app/src/main/java/io/github/ppigazzini/r47zen"

KEYS_FILE="$KOTLIN_ROOT/PhysicalKeyboardInput.kt"
FLUSHER_FILE="$KOTLIN_ROOT/GraphGestureFlusher.kt"
DECODER_FILE="$KOTLIN_ROOT/KeypadSnapshot.kt"
THEME_FILE="$KOTLIN_ROOT/LcdThemePolicy.kt"
GESTURE_FILE="$KOTLIN_ROOT/GraphGestureAccumulator.kt"
RASTER_FILE="$KOTLIN_ROOT/PackedLcdRaster.kt"
KEYS_TEST="io.github.ppigazzini.r47zen.PcKeysTest"
FLUSHER_TEST="io.github.ppigazzini.r47zen.GraphGestureFlusherTest"
DECODER_TEST="io.github.ppigazzini.r47zen.KeypadSnapshotDecoderTest"
THEME_TEST="io.github.ppigazzini.r47zen.LcdThemePolicyTest"
GESTURE_TEST="io.github.ppigazzini.r47zen.GraphGestureAccumulatorTest"
GESTURE_PROPERTY_TEST="io.github.ppigazzini.r47zen.GraphGestureAccumulatorPropertyTest"
DECODER_PROPERTY_TEST="io.github.ppigazzini.r47zen.KeypadSnapshotDecoderPropertyTest"
RASTER_TEST="io.github.ppigazzini.r47zen.PackedLcdRasterTest"
LCD_ORDER_TEST="io.github.ppigazzini.r47zen.ReplicaOverlayLcdOrderTest"
PASTE_FILE="$KOTLIN_ROOT/PasteNumber.kt"
GATE_FILE="$KOTLIN_ROOT/NativeFileRequestGate.kt"
TRANSFER_FILE="$KOTLIN_ROOT/DocumentTransfer.kt"
MIRROR_FILE="$KOTLIN_ROOT/DataFolderMirror.kt"
MANUAL_FILE="$KOTLIN_ROOT/SavedManual.kt"
KEY_VIEW_FILE="$KOTLIN_ROOT/CalculatorKeyView.kt"
PASTE_TEST="io.github.ppigazzini.r47zen.PasteNumberTest"
GATE_TEST="io.github.ppigazzini.r47zen.NativeFileRequestGateTest"
TRANSFER_TEST="io.github.ppigazzini.r47zen.DocumentTransferTest"
MIRROR_TEST="io.github.ppigazzini.r47zen.DataFolderMirrorTest"
MANUAL_TEST="io.github.ppigazzini.r47zen.SavedManualTest"
ASSIGN_TEST="io.github.ppigazzini.r47zen.ReplicaKeypadLayoutHapticsTest"

# Parallel mutation records: file, exact unique old text, mutated text, the test
# class that must catch it, and a human description.
MUT_FILES=(
    "$KEYS_FILE"
    "$KEYS_FILE"
    "$FLUSHER_FILE"
    "$DECODER_FILE"
    "$DECODER_FILE"
    "$THEME_FILE"
    "$THEME_FILE"
    "$GESTURE_FILE"
    "$GESTURE_FILE"
    "$GESTURE_FILE"
    "$GESTURE_FILE"
    "$DECODER_FILE"
    "$RASTER_FILE"
    "$RASTER_FILE"
    "$RASTER_FILE"
    "$RASTER_FILE"
    "$PASTE_FILE"
    "$PASTE_FILE"
    "$GATE_FILE"
    "$GATE_FILE"
    "$GATE_FILE"
    "$GATE_FILE"
    "$TRANSFER_FILE"
    "$TRANSFER_FILE"
    "$TRANSFER_FILE"
    "$MIRROR_FILE"
    "$MANUAL_FILE"
    "$KEY_VIEW_FILE"
)
MUT_OLD=(
    "state = if (event.action == KeyEvent.ACTION_DOWN) state and own.inv() else state or own"
    "if (keypadDigits) KP_0 + keypadDigit else keypadMoves[keypadDigit]"
    "if (sinceLast >= minFlushIntervalMs) {"
    "private fun keyMetaAt(offset: Int, index: Int): Int = meta[offset + index]"
    "return labels.getOrElse(index * LABELS_PER_KEY + slot) { \"\" }"
    "return entries.firstOrNull { it.storageValue == value } ?: DEFAULT"
    "return minimumLuminance + ((maximumLuminance - minimumLuminance) * fraction)"
    "return sign(value) * panApplyLimit"
    "return value.coerceIn(-panPendingLimit, panPendingLimit)"
    "val updatedScale = pendingScaleFactor * scaleFactor"
    "updatedScale.coerceIn(scaleFactorMin, scaleFactorMax)"
    "return keyStates[code - 1]"
    "val wholeFrame = !hasNativeFrame"
    "linePixels[destX + bit] = if ((packedByte and (1 shl (7 - bit))) != 0) {"
    "val wholeFrame = !hasNativeFrame"
    "this.palette = palette"
    $'            append(canonicalMantissa(mantissa.replace(\',\', \'.\')))\n            if (number.startsWith(\'-\')) {\n                append(TOKEN_NEGATE)\n            }'
    "if (COMMA_GROUPING.matches(mantissa)) {"
    "private fun endLaunched(): Boolean = (state == LAUNCHED).also { if (it) state = IDLE }"
    "answer(if (synchronized(lock) { endLaunched() }) ANSWER else LOST, fd, name)"
    "val start = runCatching { findStart(fileType, isSave) }.getOrNull()"
    "transfer != ticket -> if (transfer == NO_TICKET) LOST else LATE"
    "if (temp.length() > 0) {"
    "if (total > MAX_LOAD_BYTES) {"
    ".onSuccess { fd -> onLoaded(fd, nameOf(uri)) }"
    ".onSuccess { copies.edit { putString(COPIED + file.name, stamp) } }"
    "if (previous != null && previous != uri) {"
    "isEnabled = enabled || isPressed || (isFnKey && calcMode == KeypadSceneContract.CALC_MODE_ASSIGN)"
)
MUT_NEW=(
    "state = if (event.action == KeyEvent.ACTION_DOWN) state or own else state and own.inv()"
    "keypadMoves[keypadDigit]"
    "if (sinceLast >= 0L) {"
    "private fun keyMetaAt(offset: Int, index: Int): Int = meta[offset]"
    "return labels.getOrElse(index * LABELS_PER_KEY) { \"\" }"
    "return entries.firstOrNull { it.storageValue == value } ?: AMBER"
    "return minimumLuminance"
    "return value"
    "return value"
    "val updatedScale = pendingScaleFactor + scaleFactor - 1f"
    "scaleFactorMax"
    "return KeypadKeySnapshot.EMPTY"
    "val wholeFrame = false"
    "linePixels[destX + bit] = if ((packedByte and (1 shl bit)) != 0) {"
    "val wholeFrame = !hasNativeFrame && (0 until R47LcdContract.PIXEL_HEIGHT).none { (buffer[it * R47LcdContract.PACKED_ROW_SIZE_BYTES].toInt() and 0xFF) != 0 }"
    "this.palette = palette; hasNativeFrame = true"
    $'            if (number.startsWith(\'-\')) {\n                append(TOKEN_NEGATE)\n            }\n            append(canonicalMantissa(mantissa.replace(\',\', \'.\')))'
    "if (COMMA_GROUPING.matches(mantissa) && false) {"
    "private fun endLaunched(): Boolean = state == LAUNCHED"
    "answer(ANSWER, fd, name)"
    "val start = findStart(fileType, isSave)"
    "transfer == NO_TICKET -> LOST"
    "if (temp.length() >= 0) {"
    "if (total >= MAX_LOAD_BYTES) {"
    ".onSuccess { fd -> onLoaded(fd, null) }"
    ".also { copies.edit { putString(COPIED + file.name, stamp) } }"
    "if (previous != null && previous == uri) {"
    "isEnabled = enabled || isPressed"
)
MUT_TESTS=(
    "$KEYS_TEST"
    "$KEYS_TEST"
    "$FLUSHER_TEST"
    "$DECODER_TEST"
    "$DECODER_TEST"
    "$THEME_TEST"
    "$THEME_TEST"
    "$GESTURE_TEST"
    "$GESTURE_TEST"
    "$GESTURE_PROPERTY_TEST"
    "$GESTURE_PROPERTY_TEST"
    "$DECODER_PROPERTY_TEST"
    "$RASTER_TEST"
    "$RASTER_TEST"
    "$LCD_ORDER_TEST"
    "$LCD_ORDER_TEST"
    "$PASTE_TEST"
    "$PASTE_TEST"
    "$GATE_TEST"
    "$GATE_TEST"
    "$GATE_TEST"
    "$GATE_TEST"
    "$TRANSFER_TEST"
    "$TRANSFER_TEST"
    "$TRANSFER_TEST"
    "$MIRROR_TEST"
    "$MANUAL_TEST"
    "$ASSIGN_TEST"
)
MUT_DESC=(
    "PcKeys: a modifier's own bit goes with its press, not its release"
    "PcKeys: a keypad digit gives its move with NumLock on"
    "GraphGestureFlusher: flush with no minimum interval"
    "KeypadSnapshot: drop the per-key index from the meta-lane read"
    "KeypadSnapshot: drop the label slot from the label-lane read"
    "LcdThemePolicy: unknown theme falls back to AMBER instead of DEFAULT"
    "LcdThemePolicy: collapse the luminance interpolation to its minimum"
    "GraphGestureAccumulator: drop the per-apply pan-step clamp"
    "GraphGestureAccumulator: drop the pending-backlog cap"
    "GraphGestureAccumulator: compose pinch factors by adding, not multiplying"
    "GraphGestureAccumulator: drain every pinch at the maximum factor"
    "KeypadSnapshot: decode every in-range key as the empty key"
    "PackedLcdRaster: decode only flagged rows of a host's first frame"
    "PackedLcdRaster: read each pixel byte least significant bit first"
    "PackedLcdRaster: decode a host's first frame whole only when it flags no row"
    "PackedLcdRaster: a palette change marks a frame received"
    "PasteNumber: CHS before the digits it negates"
    "PasteNumber: take a comma-grouped mantissa as a decimal"
    "NativeFileRequestGate: an answer leaves the request state as it was"
    "NativeFileRequestGate: an answer this gate never launched reaches native"
    "NativeFileRequestGate: a start lookup that throws loses the request"
    "NativeFileRequestGate: a late copy answers whatever request waits"
    "DocumentTransfer: write the provider when native wrote nothing"
    "DocumentTransfer: refuse a document of exactly the limit"
    "DocumentTransfer: a load hands native no name"
    "DataFolderMirror: record a copy that failed as made"
    "SavedManual: release the new grant on a repick, keep the old one on a change"
    "CalculatorKeyView: a disabled f-key takes no press in ASSIGN"
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
for seam_file in $(printf '%s\n' "${MUT_FILES[@]}" | sort -u); do
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
