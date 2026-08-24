#!/bin/bash

# Shared helpers sourced by repo-owned build and staging scripts.
#
# Source after SCRIPT_DIR is set:
#   source "$SCRIPT_DIR/../lib/common.sh"
#
# Functions here must stay self-contained (no caller-provided globals) so any
# script one level under scripts/ can source this file safely.

# Echo a positive integer job count, or fail (return 1) for empty/non-numeric/0.
normalize_job_count() {
    case "$1" in
        '' | *[!0-9]* | 0)
            return 1
            ;;
        *)
            printf '%s\n' "$1"
            ;;
    esac
}

# Detect the host CPU count, falling back to 1 when it cannot be determined.
detect_job_count() {
    local detected_jobs=""

    if command -v nproc >/dev/null 2>&1; then
        detected_jobs=$(nproc 2>/dev/null || true)
    fi

    if [ -z "$detected_jobs" ] && command -v getconf >/dev/null 2>&1; then
        detected_jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)
    fi

    if ! detected_jobs=$(normalize_job_count "$detected_jobs"); then
        detected_jobs=1
    fi

    printf '%s\n' "$detected_jobs"
}

# Sum the JUnit "tests" counts across every result XML under a directory tree.
# Echoes the total (0 when the directory is missing or holds no results). Used
# to guard an instrumentation selection against silently executing nothing --
# e.g. a hardcoded -e class filter whose class was renamed or removed, which
# some AndroidJUnitRunner versions report as zero tests with a success exit.
count_androidtest_cases() {
    local results_dir="$1"
    local total=0 n
    [ -d "$results_dir" ] || {
        printf '0\n'
        return 0
    }
    while IFS= read -r n; do
        [ -n "$n" ] && total=$((total + n))
    done < <(
        find "$results_dir" -type f -name '*.xml' -print0 2>/dev/null |
            xargs -0 -r grep -hoE '<testsuite[^>]* tests="[0-9]+"' 2>/dev/null |
            grep -oE '[0-9]+'
    )
    printf '%s\n' "$total"
}

# Run a command, retrying with backoff when it exits non-zero. Intended for
# flaky upstream network operations (git ls-remote / git fetch) that fail
# transiently mid-transfer -- e.g. "early EOF" / "fetch-pack: invalid index-pack
# output" -- and otherwise abort a whole CI lane on a single dropped packet.
#
# Any non-zero exit is retried; git does not cleanly separate a transient
# transport error from a genuinely missing ref, so a real failure simply costs
# the full retry budget before surfacing the original error.
#
# Only the winning (or final) attempt's stdout is emitted -- a failed attempt's
# partial stdout is discarded so a capturing caller (remote_line=$(...)) never
# parses truncated ls-remote output. The command's stderr streams live for
# progress; retry diagnostics also go to stderr.
#
# Configuration via environment (all optional):
#   R47_NET_RETRY_ATTEMPTS  total attempts, default 3
#   R47_NET_RETRY_DELAY     seconds slept before the first retry, default 3;
#                           each subsequent retry doubles the delay
#
# Usage: retry_with_backoff <label> <command> [args...]
retry_with_backoff() {
    local label="$1"
    shift

    local attempts delay
    if ! attempts=$(normalize_job_count "${R47_NET_RETRY_ATTEMPTS:-3}"); then
        attempts=3
    fi
    case "${R47_NET_RETRY_DELAY:-3}" in
        '' | *[!0-9]*) delay=3 ;;
        *) delay="${R47_NET_RETRY_DELAY:-3}" ;;
    esac

    local attempt=1
    local status=0
    local out=""
    while true; do
        if out="$("$@")"; then
            [ -n "$out" ] && printf '%s\n' "$out"
            return 0
        else
            status=$?
        fi

        if [ "$attempt" -ge "$attempts" ]; then
            [ -n "$out" ] && printf '%s\n' "$out"
            printf '%s failed after %d attempt(s) (exit %d).\n' \
                "$label" "$attempt" "$status" >&2
            return "$status"
        fi

        printf '%s failed (exit %d); retrying in %ds (attempt %d/%d).\n' \
            "$label" "$status" "$delay" "$((attempt + 1))" "$attempts" >&2
        sleep "$delay"
        attempt=$((attempt + 1))
        delay=$((delay * 2))
    done
}

# Print the home of a JDK whose JNI headers the host C harnesses compile
# against: JAVA_HOME when it holds one, otherwise the JDK owning javac on
# PATH. Fails (1) with an actionable message when neither yields a JDK, so a
# host without one stops here rather than inside a compiler invocation whose
# -I flags point somewhere arbitrary.
#
# include/jni.h is checked, not assumed: a JRE, a JAVA_HOME pointing at a
# stale directory, and a javac shim all resolve to a path that exists and has
# no headers under it.
#
# Any JDK satisfies this. These harnesses need jni.h and jni_md.h, not the
# pinned build JDK: R47_DEFAULT_ANDROID_BUILD_JDK_VERSION governs the Gradle
# toolchain that compiles the app, not host links against the JNI headers.
resolve_jdk_home() {
    local candidate=""
    local javac_path=""

    if [ -n "${JAVA_HOME-}" ] && [ -f "$JAVA_HOME/include/jni.h" ]; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi

    javac_path="$(command -v javac 2>/dev/null || true)"
    if [ -n "$javac_path" ]; then
        javac_path="$(readlink -f "$javac_path")"
        candidate="$(cd "$(dirname "$javac_path")/.." && pwd)"
        if [ -f "$candidate/include/jni.h" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi

    echo "ERROR: No JDK with JNI headers found." >&2
    echo "Set JAVA_HOME to a JDK containing include/jni.h, or install a JDK providing javac on PATH." >&2
    if [ -n "$candidate" ]; then
        echo "Checked: $candidate (from javac at $javac_path)" >&2
    fi
    return 1
}
