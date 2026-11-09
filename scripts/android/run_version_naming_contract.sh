#!/bin/bash

# Contract: the version and naming rules a release is held to.
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
# resolve_android_artifact_metadata.sh must name files
# r47zen_<version>_<upstream8>_<android8>.<ext>, keep the commit-only dev tag
# the skip gate keys on, and refuse a version holding the "_" field separator.
# The README's Obtainium regexes must split those names: each link filter
# selects only its own app's APK, and each version regex cuts the version out
# of what Obtainium reads, the production tag r47zen-v<version> or the dev
# title, which is the file stem.
#
# Pure host test, needs git.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

CHECKER="$SCRIPT_DIR/check_release_version.sh"
READER="$SCRIPT_DIR/read_upstream_version.sh"
NAMER="$SCRIPT_DIR/resolve_android_artifact_metadata.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Run from a pre-commit hook, git exports GIT_INDEX_FILE and the other
# repository-local variables; `git commit <path>` makes it an absolute path to
# this repository's commit index, so `git -C "$repo" add` would rewrite that
# index. githooks(5): clear them before driving a foreign repository.
mapfile -t git_local_vars < <(git rev-parse --local-env-vars)
unset "${git_local_vars[@]}"

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

# A previous release whose commit is on no ref, as a re-cut followed by a rewrite of
# main leaves it: a fresh clone does not have it, so a rerun repeats the refusal, and
# the message must say what does help. A tag at the commit, fetched, does.
default_branch="$(git -C "$repo" symbolic-ref --short HEAD)"
git -C "$repo" checkout -q -b gone "$base"
echo 'echo gone' >"$repo/scripts/gone.sh"
gone="$(commit "on no ref")"
git -C "$repo" checkout -q "$default_branch"
git -C "$repo" branch -q -D gone
clone="$work/clone"
git clone -q --no-local "$repo" "$clone"
refusal="$(bash "$CHECKER" --repo "$clone" --version 0.2.0 --previous-version 0.1.14 \
    --previous-commit "$gone" --head "$app_changed" 2>&1 >/dev/null || true)"
grep -q "is on no ref" <<<"$refusal" && grep -q "a rerun repeats" <<<"$refusal" ||
    contract_fail "a previous commit on no ref is not explained: $refusal"
git -C "$repo" tag keep-gone "$gone"
git -C "$clone" fetch -q --tags origin
check_in_clone() {
    bash "$CHECKER" --repo "$clone" --version 0.2.0 --previous-version 0.1.14 \
        --previous-commit "$gone" --head "$app_changed" >/dev/null 2>&1
}
check_in_clone || contract_fail "a tag at the previous release's commit did not let the check run"

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

release_names="$(bash "$NAMER" --upstream-commit d8f0c932085890d4a18699560f1fd234065e29b7 \
    --android-commit cc7b9537e29750d75527b2ca306649c8f15d8df5 --version 0.1.13)"
for want in \
    "R47_ANDROID_RELEASE_APK_NAME=r47zen_0.1.13_d8f0c932_cc7b9537.apk" \
    "R47_ANDROID_RELEASE_AAB_NAME=r47zen_0.1.13_d8f0c932_cc7b9537.aab" \
    "R47_ANDROID_DEV_RELEASE_TAG=r47zen-d8f0c932-cc7b9537-dev"; do
    grep -qxF -- "$want" <<<"$release_names" || contract_fail "artifact names lack '$want'."
done
dev_names="$(bash "$NAMER" --upstream-commit d8f0c932 --android-commit cc7b9537 --version 0.1.14-53.1)"
for want in \
    "R47_ANDROID_DEV_APK_NAME=r47zen_0.1.14-53.1_d8f0c932_cc7b9537.apk" \
    "R47_ANDROID_DEV_RELEASE_TITLE=r47zen_0.1.14-53.1_d8f0c932_cc7b9537"; do
    grep -qxF -- "$want" <<<"$dev_names" || contract_fail "dev names lack '$want'."
done

# The README's Obtainium settings, one entry per app. Obtainium compiles each
# regex as a Dart RegExp and searches with it; for the constructs used here
# grep -E matches the same strings, and tail -n 1 takes the last match, as
# Obtainium's version extraction does.
release_apk_regex='^r47zen_[0-9]+\.[0-9]+\.[0-9]+_[0-9a-f]{8}_[0-9a-f]{8}\.apk$'
dev_apk_regex='^r47zen_[0-9]+\.[0-9]+\.[0-9]+-[0-9]+\.[0-9]+_[0-9a-f]{8}_[0-9a-f]{8}\.apk$'
release_version_regex='[0-9]+\.[0-9]+\.[0-9]+'
dev_version_regex='[0-9]+\.[0-9]+\.[0-9]+-[0-9]+\.[0-9]+'
readme_lines="$(sed 's/^[[:space:]]*//' "$PROJECT_ROOT/README.md")"
for regex in "$release_apk_regex" "$dev_apk_regex" "$release_version_regex" "$dev_version_regex"; do
    grep -qxF -- "$regex" <<<"$readme_lines" ||
        contract_fail "README.md does not hold the Obtainium regex '$regex' on a line of its own."
done
release_apk="$(sed -n 's/^R47_ANDROID_RELEASE_APK_NAME=//p' <<<"$release_names")"
dev_apk="$(sed -n 's/^R47_ANDROID_DEV_APK_NAME=//p' <<<"$dev_names")"
dev_title="$(sed -n 's/^R47_ANDROID_DEV_RELEASE_TITLE=//p' <<<"$dev_names")"
grep -qE -- "$release_apk_regex" <<<"$release_apk" ||
    contract_fail "the README's release link filter does not select $release_apk."
if grep -qE -- "$release_apk_regex" <<<"$dev_apk"; then
    contract_fail "the README's release link filter selects the dev APK $dev_apk."
fi
grep -qE -- "$dev_apk_regex" <<<"$dev_apk" ||
    contract_fail "the README's dev link filter does not select $dev_apk."
if grep -qE -- "$dev_apk_regex" <<<"$release_apk"; then
    contract_fail "the README's dev link filter selects the release APK $release_apk."
fi
got="$(grep -oE -- "$dev_version_regex" <<<"$dev_title" | tail -n 1)"
[ "$got" = "0.1.14-53.1" ] ||
    contract_fail "the README's dev version regex cuts '$got' from the title $dev_title, not 0.1.14-53.1."
release_workflow="$(strip_hash_comments <"$PROJECT_ROOT/.github/workflows/android-release.yml")"
grep -qF -- 'release_tag="r47zen-v${version_name}"' <<<"$release_workflow" ||
    contract_fail "android-release.yml no longer tags a production release r47zen-v<version>, the string the README's release version regex reads."
got="$(grep -oE -- "$release_version_regex" <<<"r47zen-v0.1.13" | tail -n 1)"
[ "$got" = "0.1.13" ] ||
    contract_fail "the README's release version regex cuts '$got' from the tag r47zen-v0.1.13, not 0.1.13."
if bash "$NAMER" --upstream-commit d8f0c932 --android-commit cc7b9537 --version 0.1_13 >/dev/null 2>&1; then
    contract_fail "resolve_android_artifact_metadata.sh accepted a version holding '_'."
fi

contract_pass "release versions follow the SemVer steps, published files are named r47zen_<version>_<upstream8>_<android8>, and the README's Obtainium regexes split the release from the dev APKs and cut out each version."
