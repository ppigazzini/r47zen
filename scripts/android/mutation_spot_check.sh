#!/bin/bash

# Mutation spot-check for the live program-stop routing seam (REPORT-24
# Milestone 5). It applies a small set of compile-clean semantic mutations to the
# hardened seam and asserts each one is KILLED by the JVM unit tests. This is an
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
POLICY_TEST="io.github.ppigazzini.r47zen.LiveProgramStopKeyPolicyTest"
ROUTER_TEST="io.github.ppigazzini.r47zen.LiveKeyRouterTest"

# Parallel mutation records: file, exact unique old text, mutated text, the test
# class that must catch it, and a human description.
MUT_FILES=(
    "$POLICY_FILE"
    "$POLICY_FILE"
    "$ROUTER_FILE"
    "$ROUTER_FILE"
)
MUT_OLD=(
    "const val EXIT_KEY_CODE = 33"
    "return keyCode == RUN_STOP_KEY_CODE || keyCode == EXIT_KEY_CODE"
    "LiveProgramStopKeyPolicy.shouldPublishDirectStop(keyCode) && queryDirectStopGate()"
    "if (!consumedAsDirectStop) {"
)
MUT_NEW=(
    "const val EXIT_KEY_CODE = 34"
    "return keyCode == RUN_STOP_KEY_CODE"
    "LiveProgramStopKeyPolicy.shouldPublishDirectStop(keyCode) || queryDirectStopGate()"
    "if (consumedAsDirectStop) {"
)
MUT_TESTS=(
    "$POLICY_TEST"
    "$POLICY_TEST"
    "$ROUTER_TEST"
    "$ROUTER_TEST"
)
MUT_DESC=(
    "LiveProgramStopKeyPolicy: EXIT key code 33 -> 34"
    "LiveProgramStopKeyPolicy: drop EXIT from the direct-stop key set"
    "LiveKeyRouter: AND -> OR (query the gate for non-stop keys)"
    "LiveKeyRouter: invert the forward-vs-swallow decision"
)

fail() {
    echo "ERROR: $*" >&2
    exit 1
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
[[ -f "$POLICY_FILE" && -f "$ROUTER_FILE" ]] || fail "routing-seam sources not found under $KOTLIN_ROOT"

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
    if (cd "$ANDROID_DIR" && ./gradlew :app:testReleaseUnitTest \
        --tests "${MUT_TESTS[$index]}" \
        --console=plain -Pr47.testBuildType=release >/dev/null 2>&1); then
        echo "SURVIVED: $description (${MUT_TESTS[$index]} did not catch it)"
        survived=$((survived + 1))
    else
        echo "killed:   $description"
        killed=$((killed + 1))
    fi

    mv -f "$file.mutbak" "$file"
done

echo
echo "Mutation spot-check: $killed/$total mutants killed."
if [[ "$survived" -gt 0 ]]; then
    fail "$survived mutant(s) survived -- the routing-seam tests have an assertion gap"
fi
echo "All routing-seam mutants were caught."
