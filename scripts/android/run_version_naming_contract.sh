#!/bin/bash

# Contract: the version rules a release is held to, and the upstream version it
# shows.
#
# check_release_version.sh must take the PATCH step when android/app/src/main/
# is unchanged since the previous release, even when only tests, CI, or docs
# changed, and the MINOR step with PATCH 0 (or the MAJOR step) when it changed.
# From a pre-release name, the release that every release before these rules
# was (0.1.13-signed.2026102901), the steps drop the suffix as npm semver's
# inc() does. It must refuse a malformed version, a skipped or repeated one,
# and a previous commit missing from the history.
#
# read_upstream_version.sh must print VERSION1 from a defines.h and print
# nothing for a missing file or a malformed define.
#
# Pure host test, needs git.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

CHECKER="$SCRIPT_DIR/check_release_version.sh"
READER="$SCRIPT_DIR/read_upstream_version.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A repository with one commit per kind of change.
repo="$work/repo"
git init -q "$repo"
commit() {
    git -C "$repo" add -A
    git -C "$repo" -c user.name=contract -c user.email=contract@example.invalid \
        commit -q -m "$1"
    git -C "$repo" rev-parse HEAD
}
mkdir -p "$repo/android/app/src/main" "$repo/android/app/src/test" "$repo/scripts"
echo 'class App' >"$repo/android/app/src/main/App.kt"
echo 'class AppTest' >"$repo/android/app/src/test/AppTest.kt"
echo 'echo ci' >"$repo/scripts/ci.sh"
base="$(commit base)"
echo 'echo ci2' >"$repo/scripts/ci.sh"
echo 'class AppTest2' >"$repo/android/app/src/test/AppTest.kt"
repo_only="$(commit "ci and tests")"
echo 'class App2' >"$repo/android/app/src/main/App.kt"
app_changed="$(commit "app code")"

# check <desc> <want pass|fail> <version> <previous version> <previous commit> <head>
check() {
    local desc="$1" want="$2" got=pass
    bash "$CHECKER" --repo "$repo" --version "$3" --previous-version "$4" \
        --previous-commit "$5" --head "$6" >/dev/null 2>&1 || got=fail
    [ "$got" = "$want" ] || contract_fail "$desc: expected $want, got $got."
}

old="0.1.13-signed.2026102901"
check "pre-release, app unchanged: drop the suffix" pass 0.1.13 "$old" "$base" "$repo_only"
check "pre-release, app unchanged: no PATCH + 1" fail 0.1.14 "$old" "$base" "$repo_only"
check "pre-release, app unchanged: no MINOR step" fail 0.2.0 "$old" "$base" "$repo_only"
check "pre-release, app changed: MINOR step" pass 0.2.0 "$old" "$base" "$app_changed"
check "pre-release, app changed: MAJOR step" pass 1.0.0 "$old" "$base" "$app_changed"
check "pre-release, app changed: not the plain version" fail 0.1.13 "$old" "$base" "$app_changed"
check "pre-release with PATCH 0, app changed: drop the suffix" pass 0.2.0 0.2.0-rc.1 "$base" "$app_changed"
check "release, tests and CI changed only: PATCH" pass 0.1.14 0.1.13 "$base" "$repo_only"
check "release, app unchanged: no MINOR step" fail 0.2.0 0.1.13 "$base" "$repo_only"
check "release, app unchanged: no repeat" fail 0.1.13 0.1.13 "$base" "$repo_only"
check "release, app unchanged: no skipped PATCH" fail 0.1.15 0.1.13 "$base" "$repo_only"
check "release, app changed: MINOR with PATCH 0" pass 0.2.0 0.1.14 "$base" "$app_changed"
check "release, app changed: MAJOR" pass 1.0.0 0.1.14 "$base" "$app_changed"
check "release, app changed: PATCH is refused" fail 0.1.15 0.1.14 "$base" "$app_changed"
check "release, app changed: PATCH must reset" fail 0.2.1 0.1.14 "$base" "$app_changed"
check "first release: any major.minor.patch" pass 0.1.13 "" "" "$app_changed"
check "malformed: two fields" fail 0.1 "" "" "$app_changed"
check "malformed: leading zero" fail 0.01.3 "" "" "$app_changed"
check "malformed: pre-release suffix" fail 0.1.13-1 "" "" "$app_changed"
check "previous commit outside the history" fail 0.1.14 0.1.13 \
    0123456789abcdef0123456789abcdef01234567 "$app_changed"

expected="$(bash "$CHECKER" --repo "$repo" --version 0.1.13 --previous-version "$old" \
    --previous-commit "$base" --head "$repo_only" | sed -n 's/^expected=//p')"
[ "$expected" = "0.1.13" ] || contract_fail "expected= names '$expected', not 0.1.13."

defines="$work/defines.h"
printf '%s\n' '// Version 00.109.02.07b11   Public Release' \
    '#define VERSION1 "00.109.05.00b2"       // major . minor . build' >"$defines"
[ "$(bash "$READER" "$defines")" = "00.109.05.00b2" ] ||
    contract_fail "read_upstream_version.sh did not read VERSION1."
[ -z "$(bash "$READER" "$work/absent.h")" ] ||
    contract_fail "read_upstream_version.sh printed a value for a missing file."
printf '%s\n' '#define VERSION1 "00.109 beta"' >"$defines"
[ -z "$(bash "$READER" "$defines")" ] ||
    contract_fail "read_upstream_version.sh printed a malformed VERSION1."

contract_pass "release versions follow the SemVer steps, and read_upstream_version.sh reads VERSION1."
