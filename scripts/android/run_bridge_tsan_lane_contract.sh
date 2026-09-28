#!/bin/bash

# Contract: the bridge ThreadSanitizer lane keeps its hard gate. The lane only
# means something if (1) a data race in the Android-owned bridge actually fails
# it and (2) the suppression escape hatch can never be widened to silence such a
# race. Both checks read live lines only, so a comment that names
# halt_on_error=1 or a bridge path never satisfies or violates them:
#
# - the build script must build with -fsanitize=thread, set halt_on_error=1 in
#   the TSAN_OPTS assignment itself, run the harness with TSAN_OPTIONS taken
#   from TSAN_OPTS, and load the suppression file;
# - every active suppression must name a staged upstream path
#   (android/.staged-native/cpp/{c47,decNumberICU,gmp,generated}/). TSan
#   matches a pattern against function, file, and module names alike, so a
#   denylist of bridge paths misses `race:jni_display` or a JNI function name;
#   the allowlist is the only shape that keeps the bridge ungated-proof.
#
# Each check first proves it fails on seeded fixtures, then reads the real
# files. Pure host, no SDK, no staged native tree, no sanitizer build: what
# proves the lane itself is scripts/workload-regressions/build_bridge_tsan_harness.sh
# in the linux-ci host-workload lane.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WR_DIR="$PROJECT_ROOT/scripts/workload-regressions"
BUILD_SCRIPT="$WR_DIR/build_bridge_tsan_harness.sh"
HARNESS_SRC="$WR_DIR/bridge_tsan_harness.c"
SUPPRESSIONS="$WR_DIR/bridge_tsan_suppressions.txt"
CI_WORKFLOW="$PROJECT_ROOT/.github/workflows/linux-ci.yml"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# Print FILE's lines with shell comments removed: whole-line comments and a
# trailing " # ..." (the build script puts no # inside a quoted string).
live_lines() {
    sed -e '/^[[:space:]]*#/d' -e 's/[[:space:]]#.*$//' "$1"
}

# Print one line per gate the build script FILE is missing; nothing when whole.
tsan_build_gaps() {
    local live
    live="$(live_lines "$1")"
    grep -Eq -- '-fsanitize=thread' <<<"$live" ||
        echo "does not build under -fsanitize=thread"
    grep -Eq '^[[:space:]]*TSAN_OPTS="([^"]*:)?halt_on_error=1[:"]' <<<"$live" ||
        echo "does not set halt_on_error=1 in the TSAN_OPTS assignment"
    grep -Eq 'TSAN_OPTIONS="\$TSAN_OPTS"' <<<"$live" ||
        echo "does not run the harness with TSAN_OPTIONS from TSAN_OPTS"
    grep -q 'bridge_tsan_suppressions.txt' <<<"$live" ||
        echo "does not load bridge_tsan_suppressions.txt"
}

# Print each active suppression in FILE that is not a staged upstream path.
tsan_suppression_gaps() {
    live_lines "$1" | sed -e '/^[[:space:]]*$/d' |
        grep -Ev '^(race|race_top|thread|mutex|signal|deadlock|called_from_lib):android/\.staged-native/cpp/(c47|decNumberICU|gmp|generated)/' ||
        true
}

fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT

cat >"$fixtures/good-build.sh" <<'SH'
"$CC_BIN" -fsanitize=thread harness.c
TSAN_OPTS="halt_on_error=1:exitcode=66"
TSAN_OPTS="$TSAN_OPTS:suppressions=$SCRIPT_DIR/bridge_tsan_suppressions.txt"
TSAN_OPTIONS="$TSAN_OPTS" ./harness
SH
sed -e 's/halt_on_error=1:exitcode=66/exitcode=66/' \
    -e '1i # halt_on_error=1 makes the first race abort the run.' \
    "$fixtures/good-build.sh" >"$fixtures/gate-only-in-comment.sh"
sed -e 's/halt_on_error=1/halt_on_error=0/' "$fixtures/good-build.sh" >"$fixtures/gate-off.sh"
sed -e 's/TSAN_OPTIONS="\$TSAN_OPTS" //' "$fixtures/good-build.sh" >"$fixtures/options-unused.sh"

[ -z "$(tsan_build_gaps "$fixtures/good-build.sh")" ] ||
    fail "TSan build checker flagged the complete fixture: $(tsan_build_gaps "$fixtures/good-build.sh")"
for fixture in gate-only-in-comment gate-off options-unused; do
    [ -n "$(tsan_build_gaps "$fixtures/$fixture.sh")" ] ||
        fail "TSan build checker missed the seeded gap in fixture $fixture.sh."
done

cat >"$fixtures/good-suppressions.txt" <<'TXT'
# race:jni_display is a comment and suppresses nothing.
race:android/.staged-native/cpp/c47/
race:android/.staged-native/cpp/decNumberICU/
TXT
[ -z "$(tsan_suppression_gaps "$fixtures/good-suppressions.txt")" ] ||
    fail "TSan suppression checker flagged the upstream-scoped fixture."
for bad in 'race:jni_display' 'race:Java_com_example_r47_MainActivity_sendKey' \
    'race:android/app/src/main/cpp/r47zen/' 'race:*' 'race:android/.staged-native/cpp/'; do
    printf '%s\n' "$bad" >"$fixtures/bad-suppressions.txt"
    [ -n "$(tsan_suppression_gaps "$fixtures/bad-suppressions.txt")" ] ||
        fail "TSan suppression checker accepted the seeded suppression '$bad'."
done

for f in "$BUILD_SCRIPT" "$HARNESS_SRC" "$SUPPRESSIONS" "$CI_WORKFLOW"; do
    [ -f "$f" ] || fail "missing required file: ${f#"$PROJECT_ROOT/"}"
done

build_gaps="$(tsan_build_gaps "$BUILD_SCRIPT")"
[ -z "$build_gaps" ] ||
    fail "build_bridge_tsan_harness.sh lost its hard gate: $build_gaps"

suppression_gaps="$(tsan_suppression_gaps "$SUPPRESSIONS")"
[ -z "$suppression_gaps" ] ||
    fail "bridge_tsan_suppressions.txt holds a suppression outside the staged upstream core: $suppression_gaps"

grep -q 'build_bridge_tsan_harness.sh' <(live_lines "$CI_WORKFLOW") ||
    fail "linux-ci.yml does not run build_bridge_tsan_harness.sh."

echo "OK: bridge ThreadSanitizer lane keeps -fsanitize=thread, halt_on_error=1 in TSAN_OPTS, upstream-only suppressions, and CI wiring."
