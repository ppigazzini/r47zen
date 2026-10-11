#!/bin/bash

# Contract: every consumer of the build JDK reads the same pin.
#
# R47_DEFAULT_ANDROID_BUILD_JDK_VERSION in android/r47-defaults.properties is
# the single source for the JDK that runs Gradle, AGP, javac, the Kotlin
# compiler, and the JVM unit tests. Three consumers can drift from it silently:
# the Gradle Java toolchain in android/app/build.gradle, the actions/setup-java
# steps in the workflows, and the doctor in scripts/android/build_android.sh. A
# workflow that hardcodes java-version is the dangerous case: CI would keep
# building on the old JDK while the toolchain demanded the new one, and the
# failure surfaces as an unrelated "No matching toolchains found" on the runner.
#
# Also asserts the one invariant between the two JDK axes: the app language
# level (R47_DEFAULT_ANDROID_JAVA_VERSION) can never exceed the build JDK,
# because no compiler emits bytecode newer than itself. The separate ceiling
# that the AGP-bundled Kotlin compiler imposes on jvmTarget is not encoded
# here: it moves with AGP, and the Kotlin compiler already rejects an
# out-of-range value with its own explicit error.
#
# Every check reads live code: a comment that names the pin satisfies nothing.
# The Gradle and doctor checks first prove they reject seeded copies of the
# live files, among them the copy with both live lines commented out and a
# literal toolchain added. Pure host test, no SDK needed. File paths are
# env-overridable so the contract can be run against a drifted copy without
# touching the live tree.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

defaults_file="${R47_DEFAULTS_FILE:-$PROJECT_ROOT/android/r47-defaults.properties}"
app_gradle_file="${R47_APP_BUILD_GRADLE_FILE:-$PROJECT_ROOT/android/app/build.gradle}"
doctor_file="${R47_BUILD_ANDROID_FILE:-$PROJECT_ROOT/scripts/android/build_android.sh}"
WORKFLOW_DIR="${R47_WORKFLOW_DIR:-$WORKFLOW_DIR}"
export WORKFLOW_DIR

for f in "$defaults_file" "$app_gradle_file" "$doctor_file"; do
    [ -f "$f" ] || contract_fail "missing required file: $f"
done
[ -d "$WORKFLOW_DIR" ] || contract_fail "missing workflow directory: $WORKFLOW_DIR"

read_property() {
    local file="$1" key="$2" value
    value="$(sed -n "s/^${key}=//p" "$file" | head -n 1)"
    [ -n "$value" ] || contract_fail "missing ${key} in ${file}"
    printf '%s' "$value"
}

build_jdk="$(read_property "$defaults_file" R47_DEFAULT_ANDROID_BUILD_JDK_VERSION)"
language_level="$(read_property "$defaults_file" R47_DEFAULT_ANDROID_JAVA_VERSION)"

case "$build_jdk" in
    '' | *[!0-9]*)
        contract_fail "R47_DEFAULT_ANDROID_BUILD_JDK_VERSION must be a plain major version, got '${build_jdk}'."
        ;;
esac
case "$language_level" in
    '' | *[!0-9]*)
        contract_fail "R47_DEFAULT_ANDROID_JAVA_VERSION must be a plain major version, got '${language_level}'."
        ;;
esac

if [ "$language_level" -gt "$build_jdk" ]; then
    contract_fail \
        "language level ${language_level} exceeds the build JDK ${build_jdk}." \
        "A compiler cannot emit bytecode newer than itself: raise R47_DEFAULT_ANDROID_BUILD_JDK_VERSION or lower R47_DEFAULT_ANDROID_JAVA_VERSION in ${defaults_file}."
fi

# --- Gradle toolchain reads the pin ------------------------------------------

# Print each way FILE, comments aside, fails to take the toolchain from the pin;
# print nothing when it takes it. The pin must be read into buildJdkVersion
# exactly once and never reassigned, every JavaLanguageVersion.of must take
# buildJdkVersion, and Kotlin's jvmToolchain, which sets the same toolchain,
# must not appear.
gradle_toolchain_problems() {
    local live reads assignments calls
    live="$(strip_c_comments "$1")"
    reads="$(grep -cE "^[[:space:]]*def[[:space:]]+buildJdkVersion[[:space:]]*=[[:space:]]*readDefaultInteger\('R47_DEFAULT_ANDROID_BUILD_JDK_VERSION'\)[[:space:]]*$" <<<"$live" || true)"
    [ "$reads" = 1 ] ||
        echo "reads R47_DEFAULT_ANDROID_BUILD_JDK_VERSION into buildJdkVersion ${reads} time(s), not once"
    assignments="$(grep -vE '^[[:space:]]*def[[:space:]]+buildJdkVersion' <<<"$live" || true)"
    if grep -qE '(^|[^A-Za-z0-9_])buildJdkVersion[[:space:]]*=[^=]' <<<"$assignments"; then
        echo "reassigns buildJdkVersion"
    fi
    grep -qF 'JavaLanguageVersion.of(buildJdkVersion)' <<<"$live" ||
        echo "never feeds buildJdkVersion to JavaLanguageVersion.of"
    calls="$(grep -oE 'JavaLanguageVersion\.of\([^)]*\)' <<<"$live" || true)"
    if grep -qvx 'JavaLanguageVersion.of(buildJdkVersion)' <<<"$calls"; then
        echo "passes JavaLanguageVersion.of something other than buildJdkVersion"
    fi
    if grep -qE '(^|[^A-Za-z0-9_])jvmToolchain[[:space:]]*[({]' <<<"$live"; then
        echo "sets jvmToolchain, a second owner of the toolchain"
    fi
}

seeds="$(mktemp -d)"
trap 'rm -rf "$seeds"' EXIT

# The checker must reject each seeded drift before its verdict on the live file
# means anything. The first seed is the copy that passed the old grep: both live
# lines commented out and a literal toolchain added.
sed -e "s|^def buildJdkVersion = |// &|" \
    -e "s|^\([[:space:]]*\)languageVersion = JavaLanguageVersion.of(buildJdkVersion)|\1// languageVersion = JavaLanguageVersion.of(buildJdkVersion)\n\1languageVersion = JavaLanguageVersion.of(17)|" \
    "$app_gradle_file" >"$seeds/commented.gradle"
sed -e "s|^def buildJdkVersion = .*|/* & */ def buildJdkVersion = 17|" \
    "$app_gradle_file" >"$seeds/block-commented.gradle"
{
    cat "$app_gradle_file"
    printf 'kotlin {\n    jvmToolchain(17)\n}\n'
} >"$seeds/kotlin-toolchain.gradle"
{
    cat "$app_gradle_file"
    printf 'buildJdkVersion = 17\n'
} >"$seeds/reassigned.gradle"
for seed in commented block-commented kotlin-toolchain reassigned; do
    [ -n "$(gradle_toolchain_problems "$seeds/$seed.gradle")" ] ||
        contract_fail "the Gradle toolchain check accepted the seeded '${seed}' drift; it would pass a literal toolchain."
done

problems="$(gradle_toolchain_problems "$app_gradle_file")"
if [ -n "$problems" ]; then
    contract_fail \
        "${app_gradle_file} does not take the Java toolchain from the pin:" \
        "$problems" \
        "Expected: def buildJdkVersion = readDefaultInteger('R47_DEFAULT_ANDROID_BUILD_JDK_VERSION'), then java { toolchain { languageVersion = JavaLanguageVersion.of(buildJdkVersion) } }"
fi

# --- The doctor reads the pin ------------------------------------------------

doctor_reads_pin() {
    local live
    live="$(strip_hash_comments <"$1")"
    grep -qF 'find_jdk_home_for_major "$R47_DEFAULT_ANDROID_BUILD_JDK_VERSION"' <<<"$live"
}

sed -e 's|^\([[:space:]]*\)\(.*find_jdk_home_for_major "\$R47_DEFAULT_ANDROID_BUILD_JDK_VERSION".*\)|\1# \2|' \
    "$doctor_file" >"$seeds/doctor.sh"
if doctor_reads_pin "$seeds/doctor.sh"; then
    contract_fail "the doctor check accepted a copy whose JDK lookup is commented out."
fi

if ! doctor_reads_pin "$doctor_file"; then
    contract_fail \
        "${doctor_file} does not look up the JDK the pin names." \
        "The doctor must report the host JDK against the pin, so a mismatch is named before Gradle fails on a missing toolchain."
fi

# --- No workflow hardcodes a java-version ------------------------------------

literal_pins="$(workflow_grep '^[[:space:]]*java-version:[[:space:]]*.?[0-9]' || true)"
if [ -n "$literal_pins" ]; then
    contract_fail \
        "a workflow hardcodes java-version instead of reading the pin:" \
        "$literal_pins" \
        "Use: java-version: \${{ steps.defaults.outputs.build_jdk_version }}"
fi

# --- Every job that sets up Java resolves it from the defaults file -----------

checked_jobs=0
for workflow in "$WORKFLOW_DIR"/*.yml; do
    [ -f "$workflow" ] || continue

    while IFS= read -r job; do
        block="$(workflow_job_block "$workflow" "$job" | strip_hash_comments)"
        case "$block" in
            *actions/setup-java*) ;;
            *) continue ;;
        esac

        checked_jobs=$((checked_jobs + 1))

        case "$block" in
            *'build_jdk_version=$R47_DEFAULT_ANDROID_BUILD_JDK_VERSION'*) ;;
            *)
                contract_fail \
                    "job '${job}' in ${workflow} sets up Java without exporting the pin." \
                    "Add to its 'Load shared Android defaults' step: echo \"build_jdk_version=\$R47_DEFAULT_ANDROID_BUILD_JDK_VERSION\" >> \"\$GITHUB_OUTPUT\""
                ;;
        esac

        case "$block" in
            *'java-version: ${{ steps.defaults.outputs.build_jdk_version }}'*) ;;
            *)
                contract_fail \
                    "job '${job}' in ${workflow} sets up Java without consuming the exported pin." \
                    "Use: java-version: \${{ steps.defaults.outputs.build_jdk_version }}"
                ;;
        esac
    done < <(sed -n 's/^  \([A-Za-z0-9_-]*\):[[:space:]]*$/\1/p' "$workflow")
done

if [ "$checked_jobs" -eq 0 ]; then
    contract_fail \
        "no workflow job was found using actions/setup-java." \
        "This contract would pass vacuously: check ${WORKFLOW_DIR} and the job-header pattern this contract scans for."
fi

contract_pass "build JDK ${build_jdk} is the single source for the Gradle toolchain, the doctor, and all ${checked_jobs} setup-java job(s); language level ${language_level} fits inside it."
