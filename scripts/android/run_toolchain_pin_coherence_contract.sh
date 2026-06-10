#!/bin/bash

# Contract: the documentary AGP and Gradle pins in android/r47-defaults
# .properties must match the authoritative values Gradle actually uses -- the
# wrapper distributionUrl for Gradle and the version catalog for AGP. Every
# other value in the defaults file is the single source its consumers read;
# these two are documentary copies (the docs present the defaults file as the
# pin inventory), so without this guard they can drift silently when the
# wrapper or the catalog is bumped. Pure host test, no SDK needed.
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

contract_pass "documentary pins match their sources (Gradle ${wrapper_gradle}, AGP ${catalog_agp})."
