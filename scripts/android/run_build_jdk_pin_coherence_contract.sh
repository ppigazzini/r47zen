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
# Pure host test, no SDK needed. File paths are env-overridable so the contract
# can be negative-proven against a drifted copy without touching the live tree.

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

if ! grep -q "readDefaultInteger('R47_DEFAULT_ANDROID_BUILD_JDK_VERSION')" "$app_gradle_file"; then
    contract_fail \
        "${app_gradle_file} does not read R47_DEFAULT_ANDROID_BUILD_JDK_VERSION." \
        "The Gradle Java toolchain must be configured from the pin, not from a literal or from whatever JDK launched Gradle."
fi

if ! grep -q 'JavaLanguageVersion.of(buildJdkVersion)' "$app_gradle_file"; then
    contract_fail \
        "${app_gradle_file} does not feed the pinned build JDK into a Java toolchain." \
        "Expected: java { toolchain { languageVersion = JavaLanguageVersion.of(buildJdkVersion) } }"
fi

# --- The doctor reads the pin ------------------------------------------------

if ! grep -q 'R47_DEFAULT_ANDROID_BUILD_JDK_VERSION' "$doctor_file"; then
    contract_fail \
        "${doctor_file} does not read R47_DEFAULT_ANDROID_BUILD_JDK_VERSION." \
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
        block="$(workflow_job_block "$workflow" "$job" | strip_yaml_comments)"
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
