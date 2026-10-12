#!/bin/bash

# Contract: the documentary AGP and Gradle pins in android/r47-defaults
# .properties must match the authoritative values Gradle actually uses -- the
# wrapper distributionUrl for Gradle and the version catalog for AGP. Every
# other value in the defaults file is the single source its consumers read;
# these two are documentary copies (the docs present the defaults file as the
# pin inventory), so without this guard they can drift silently when the
# wrapper or the catalog is bumped. The same holds for the AGP and Gradle
# release-notes links in android/docs/dev/09-official-references.md, which name
# the pinned versions in their URLs. Pure host test, no SDK needed.
#
# File paths are env-overridable so the contract can be negative-proven against
# a drifted copy without touching the live tree.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

defaults_file="${R47_DEFAULTS_FILE:-$PROJECT_ROOT/android/r47-defaults.properties}"
wrapper_file="${R47_GRADLE_WRAPPER_FILE:-$PROJECT_ROOT/android/gradle/wrapper/gradle-wrapper.properties}"
catalog_file="${R47_VERSIONS_TOML_FILE:-$PROJECT_ROOT/android/gradle/libs.versions.toml}"

for f in "$defaults_file" "$wrapper_file" "$catalog_file"; do
    [ -f "$f" ] || contract_fail "missing required file: $f"
done

read_property() {
    local file="$1" key="$2" value
    value="$(sed -n "s/^${key}=//p" "$file" | head -n 1)"
    [ -n "$value" ] || contract_fail "missing ${key} in ${file}"
    printf '%s' "$value"
}

declared_gradle="$(read_property "$defaults_file" R47_DEFAULT_ANDROID_GRADLE_VERSION)"
declared_agp="$(read_property "$defaults_file" R47_DEFAULT_ANDROID_AGP_VERSION)"

# distributionUrl=https\://services.gradle.org/distributions/gradle-<ver>-bin.zip
wrapper_gradle="$(sed -n 's/^distributionUrl=.*gradle-\([0-9][0-9.]*\)-[a-z]*\.zip$/\1/p' "$wrapper_file" | head -n 1)"
[ -n "$wrapper_gradle" ] || contract_fail "could not parse the Gradle version from distributionUrl in ${wrapper_file}"

# agp = "<ver>" in the [versions] table.
catalog_agp="$(sed -n 's/^agp[[:space:]]*=[[:space:]]*"\([0-9][0-9.]*\)".*$/\1/p' "$catalog_file" | head -n 1)"
[ -n "$catalog_agp" ] || contract_fail "could not parse the agp version from ${catalog_file}"

if [ "$declared_gradle" != "$wrapper_gradle" ]; then
    contract_fail \
        "Gradle pin drift: R47_DEFAULT_ANDROID_GRADLE_VERSION=${declared_gradle} but the wrapper distributionUrl pins ${wrapper_gradle}." \
        "Update ${defaults_file} to match ${wrapper_file}."
fi

if [ "$declared_agp" != "$catalog_agp" ]; then
    contract_fail \
        "AGP pin drift: R47_DEFAULT_ANDROID_AGP_VERSION=${declared_agp} but the version catalog pins ${catalog_agp}." \
        "Update ${defaults_file} to match ${catalog_file}."
fi

# 09-official-references.md links the release notes of the pinned AGP line and
# Gradle version, so each link URL carries a version. A bump that leaves the
# link behind still resolves, to the notes of a toolchain the build no longer
# uses; hold both links to the pins.
references_file="${R47_REFERENCES_DOC_FILE:-$PROJECT_ROOT/android/docs/dev/09-official-references.md}"
[ -f "$references_file" ] || contract_fail "missing required file: $references_file"

# Print one line per release-notes link in FILE that names a version other
# than AGP_LINE (major.minor) or GRADLE.
find_reference_drift() {
    local file="$1" agp_line="$2" gradle="$3" linked
    linked="$(grep -oE 'agp-[0-9]+-[0-9]+-0-release-notes' "$file" | sort -u)"
    [ -n "$linked" ] || echo "no AGP release-notes link"
    while read -r link; do
        [ -n "$link" ] || continue
        link="${link#agp-}"
        link="${link%-0-release-notes}"
        [ "${link/-/.}" = "$agp_line" ] || echo "AGP release notes link the ${link/-/.} line, not ${agp_line}"
    done <<<"$linked"
    linked="$(grep -oE 'docs\.gradle\.org/[0-9][0-9.]*/release-notes' "$file" | sort -u)"
    [ -n "$linked" ] || echo "no Gradle release-notes link"
    while read -r link; do
        [ -n "$link" ] || continue
        link="${link#docs.gradle.org/}"
        link="${link%/release-notes}"
        [ "$link" = "$gradle" ] || echo "Gradle release notes link ${link}, not ${gradle}"
    done <<<"$linked"
}

catalog_agp_line="$(cut -d. -f1-2 <<<"$catalog_agp")"
fixture_file="$(mktemp)"
trap 'rm -f "$fixture_file"' EXIT
printf '%s\n' "(https://developer.android.com/build/releases/agp-1-2-0-release-notes)" \
    "(https://docs.gradle.org/3.4.5/release-notes.html)" >"$fixture_file"
[ -z "$(find_reference_drift "$fixture_file" 1.2 3.4.5)" ] ||
    contract_fail "the reference checker flagged links that match their pins."
[ -n "$(find_reference_drift "$fixture_file" 1.3 3.4.5)" ] ||
    contract_fail "the reference checker missed an AGP link one line behind."
[ -n "$(find_reference_drift "$fixture_file" 1.2 3.4.6)" ] ||
    contract_fail "the reference checker missed a Gradle link one patch behind."

reference_drift="$(find_reference_drift "$references_file" "$catalog_agp_line" "$wrapper_gradle")"
if [ -n "$reference_drift" ]; then
    contract_fail "release-notes links in ${references_file} do not match the pins:" "$reference_drift"
fi

contract_pass "documentary pins and the release-notes links match their sources (Gradle ${wrapper_gradle}, AGP ${catalog_agp})."
