#!/bin/bash

# Run detekt with type resolution over the app's Kotlin sources and hold its
# baselines to the code.
#
# :app:detektMain covers the production classes of every variant, and
# :app:detektTest the unit and instrumented tests. They resolve types, so the
# rules that need types run too: the plain :app:detekt task skips every one of
# them. Each fails on any finding its baseline does not list, so new code meets
# detekt's default rule set. The baselines then have to match the findings
# exactly: an entry no finding matches anymore fails this script too, so a fix
# removes its entry in the same commit and a baseline only shrinks. Extra
# arguments go to both Gradle runs. The committed baselines are restored either
# way; the regenerated ones are shown as a diff.
#
# Needs the Android Gradle build; no device, no staged native tree.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
# One per variant the two tasks analyze, named from build.gradle's baseline. The
# test variants follow the test build type, which a channel or a property can
# turn to release, so the runs below pin it to debug: a variant with no baseline
# of its own would run with none.
BASELINES=(debug release debugUnitTest debugAndroidTest)

committed="$(mktemp -d)"
restore() {
    for variant in "${BASELINES[@]}"; do
        cp "$committed/$variant.xml" "$ANDROID_DIR/app/detekt-baseline-$variant.xml"
    done
    rm -rf "$committed"
}
for variant in "${BASELINES[@]}"; do
    cp "$ANDROID_DIR/app/detekt-baseline-$variant.xml" "$committed/$variant.xml"
done
trap restore EXIT

cd "$ANDROID_DIR"
./gradlew :app:detektMain :app:detektTest --console=plain "$@" -Pr47.testBuildType=debug
./gradlew :app:detektBaselineMain :app:detektBaselineTest --console=plain "$@" -Pr47.testBuildType=debug

stale=0
for variant in "${BASELINES[@]}"; do
    baseline="app/detekt-baseline-$variant.xml"
    if ! cmp -s "$committed/$variant.xml" "$baseline"; then
        echo "FAIL: $baseline lists findings the code no longer has." >&2
        echo "Remove the entries below (- lines) from it:" >&2
        diff -u "$committed/$variant.xml" "$baseline" >&2 || true
        stale=1
    fi
done
# A baseline file outside the list is never compared, so it would hide findings.
for baseline in app/detekt-baseline*.xml; do
    variant="${baseline#app/detekt-baseline-}"
    variant="${variant%.xml}"
    if [[ " ${BASELINES[*]} " != *" $variant "* ]]; then
        echo "FAIL: $baseline is not one of the baselines this script compares." >&2
        stale=1
    fi
done
[ "$stale" = 0 ] || exit 1

echo "OK: no new detekt finding with type resolution, and every baseline entry still matches one."
