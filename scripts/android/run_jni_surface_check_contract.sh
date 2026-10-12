#!/bin/bash

# Contract: scripts/android/check_jni_surface.sh, which fails a minified APK
# that lost a name the native bridge looks up, can fail. It runs over dexdump
# text: a complete MainActivity built here from the C sources' own names
# passes, and a copy missing one registered native, one that keeps the name but
# lost its native flag, one missing a GetMethodID callback, one with the
# callback's signature changed, and one with the class renamed each fail. The
# release lane runs the checker over the shipped APK, inside
# collect_packaging_evidence.sh. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

CHECKER="$SCRIPT_DIR/check_jni_surface.sh"
GLUE_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

class_path="$(sed -n 's/^#define MAIN_ACTIVITY_CLASS "\([^"]*\)".*/\1/p' "$GLUE_DIR/jni_bridge.h")"
[ -n "$class_path" ] || contract_fail "no MAIN_ACTIVITY_CLASS in jni_bridge.h"

# One dexdump method stanza; ACCESS is the access line's text.
method_stanza() {
    printf '    #0              : (in L%s;)\n' "$class_path"
    printf "      name          : '%s'\n" "$1"
    printf "      type          : '%s'\n" "$2"
    printf '      access        : %s\n' "$3"
}

{
    echo "Class #0            -"
    printf "  Class descriptor  : 'L%s;'\n" "$class_path"
    echo "  Instance fields   -"
    method_stanza a 'I' '0x0001 (PUBLIC)'
    echo "  Direct methods    -"
    grep -oE '\{"[A-Za-z0-9_]+", "[^"]+",' "$GLUE_DIR/jni_registration.c" |
        sed -E 's/^\{"([^"]+)", "([^"]+)",$/\1 \2/' |
        while read -r name signature; do
            method_stanza "$name" "$signature" '0x0112 (PRIVATE FINAL NATIVE)'
        done
    echo "  Virtual methods   -"
    cat "$GLUE_DIR"/*.c "$GLUE_DIR"/hal/*.c | tr '\n' ' ' |
        grep -oE 'GetMethodID\([^,]+,[^,]+,[[:space:]]*"[A-Za-z0-9_]+",[[:space:]]*"[^"]+"' |
        sed -E 's/.*"([A-Za-z0-9_]+)",[[:space:]]*"([^"]+)"$/\1 \2/' |
        while read -r name signature; do
            method_stanza "$name" "$signature" '0x0011 (PUBLIC FINAL)'
        done
} >"$fixture_dir/complete.txt"

grep -q "name          : 'tick'" "$fixture_dir/complete.txt" ||
    contract_fail "the complete fixture holds no tick native; the extraction read nothing."
grep -q "name          : 'playTone'" "$fixture_dir/complete.txt" ||
    contract_fail "the complete fixture holds no playTone callback; the extraction read nothing."

# seed NAME AWK_PROGRAM: a copy of the complete fixture rewritten by awk.
seed() {
    awk "$2" "$fixture_dir/complete.txt" >"$fixture_dir/$1.txt"
}
# Drop the stanza of one method: its four lines start at the "#0" line before
# its name line, so drop the line before NAME and the two after it.
drop_method() {
    printf '%s' "
        { lines[NR] = \$0 }
        \$0 ~ /name +: '$1'/ { skip[NR - 1] = 1; skip[NR] = 1; skip[NR + 1] = 1; skip[NR + 2] = 1 }
        END { for (i = 1; i <= NR; i++) if (!(i in skip)) print lines[i] }"
}
seed missing-native "$(drop_method tick)"
seed not-native '
    /name +: '\''sendKey'\''/ { hit = 1 }
    hit && /access/ { sub(/0x0112 \(PRIVATE FINAL NATIVE\)/, "0x0012 (PRIVATE FINAL)"); hit = 0 }
    { print }'
seed missing-callback "$(drop_method playTone)"
seed changed-signature '
    /name +: '\''requestFile'\''/ { hit = 1 }
    hit && /type/ { sub(/'\''[^'\'']*'\''/, "'\''(ZLjava/lang/String;)V'\''"); hit = 0 }
    { print }'
seed renamed-class '{ sub(/MainActivity;/, "a;") } { print }'

bash "$CHECKER" --dexdump-output "$fixture_dir/complete.txt" >/dev/null ||
    contract_fail "the checker rejected the complete fixture."
for fixture in missing-native not-native missing-callback changed-signature renamed-class; do
    if bash "$CHECKER" --dexdump-output "$fixture_dir/$fixture.txt" >/dev/null 2>&1; then
        contract_fail "the checker accepted the seeded fixture $fixture."
    fi
done

contract_pass "check_jni_surface.sh passes the complete MainActivity and fails each of 5 seeded losses."
