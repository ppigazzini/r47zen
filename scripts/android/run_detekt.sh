#!/bin/bash

# Run detekt over the app's Kotlin sources and hold its baseline to the code.
#
# :app:detekt fails on any finding the baseline does not list, so new code
# meets detekt's default rule set. The baseline then has to match the findings
# exactly: an entry no finding matches anymore fails this script too, so a fix
# removes its entry in the same commit and the baseline only shrinks. Extra
# arguments go to both Gradle runs. The committed baseline is restored either
# way; the regenerated one is shown as a diff.
#
# Needs the Android Gradle build; no device, no staged native tree.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
BASELINE="$ANDROID_DIR/app/detekt-baseline.xml"

committed="$(mktemp)"
trap 'cp "$committed" "$BASELINE"; rm -f "$committed"' EXIT
cp "$BASELINE" "$committed"

cd "$ANDROID_DIR"
./gradlew :app:detekt --console=plain "$@"
./gradlew :app:detektBaseline --console=plain "$@"

if ! cmp -s "$committed" "$BASELINE"; then
    echo "FAIL: app/detekt-baseline.xml lists findings the code no longer has." >&2
    echo "Remove the entries below (- lines) from the baseline:" >&2
    diff -u "$committed" "$BASELINE" >&2 || true
    exit 1
fi

echo "OK: no new detekt finding, and every baseline entry still matches one."
