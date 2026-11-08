#!/bin/bash
# Assert that a built r47zen native library carries the expected
# exploit-mitigation posture. This locks in the hardening the CMake build
# enables so a dropped flag or a toolchain default change is caught instead of
# silently shipping a weaker library. ABI-aware: AArch64 branch protection
# (BTI/PAC) is required only on AArch64; the load-segment, RELRO, stack, and
# relocation checks apply to every ABI.
#
# Usage: check_native_hardening.sh <path-to-libr47zen.so>
# Exits non-zero if any required property is missing.
set -Eeuo pipefail

SO="${1:-}"
if [[ -z "$SO" || ! -f "$SO" ]]; then
    echo "usage: $0 <path-to-libr47zen.so>" >&2
    exit 2
fi

resolve_readelf() {
    if [[ -n "${READELF:-}" ]] && command -v "$READELF" >/dev/null 2>&1; then
        printf '%s\n' "$READELF"
        return 0
    fi
    if command -v llvm-readelf >/dev/null 2>&1; then
        printf '%s\n' llvm-readelf
        return 0
    fi
    local ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
    if [[ -n "$ndk" ]]; then
        local candidate
        # "|| true": a missing NDK fails find, and head's early exit can kill
        # it with SIGPIPE; under pipefail either would skip the message below.
        candidate="$(find "$ndk" -path '*/bin/llvm-readelf' 2>/dev/null | head -1 || true)"
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    if command -v readelf >/dev/null 2>&1; then
        printf '%s\n' readelf
        return 0
    fi
    return 1
}

RE="$(resolve_readelf)" || {
    echo "ERROR: no readelf/llvm-readelf on PATH or in the NDK" >&2
    exit 2
}

prog="$("$RE" -lW "$SO")"
dyn="$("$RE" -dW "$SO")"
notes="$("$RE" -nW "$SO")"
machine="$("$RE" -h "$SO" | awk -F: '/Machine/{gsub(/^[ \t]+/,"",$2);print $2}')"

rc=0
pass() { printf '  PASS  %s\n' "$1"; }
failc() {
    printf '  FAIL  %s\n' "$1"
    rc=1
}

echo "Native hardening contract: $SO ($machine)"

# Non-executable stack: GNU_STACK must be present and not executable.
if awk '$1=="GNU_STACK"{f="";for(i=7;i<NF;i++)f=f $i; if(f ~ /E/) exit 1; found=1} END{exit found?0:1}' <<<"$prog"; then
    pass "non-executable stack"
else
    failc "executable stack or GNU_STACK missing"
fi

# Full RELRO: a GNU_RELRO segment plus eager binding (BIND_NOW / NOW).
if grep -q "GNU_RELRO" <<<"$prog" && grep -qE "BIND_NOW|\bNOW\b" <<<"$dyn"; then
    pass "full RELRO (GNU_RELRO + BIND_NOW)"
else
    failc "not full RELRO (need GNU_RELRO and BIND_NOW/NOW)"
fi

# No writable-and-executable load segment.
if awk '$1=="LOAD"{f="";for(i=7;i<NF;i++)f=f $i; if(f ~ /W/ && f ~ /E/) bad=1} END{exit bad?1:0}' <<<"$prog"; then
    pass "no writable-executable (RWX) segment"
else
    failc "a LOAD segment is both writable and executable"
fi

# No text relocations.
if grep -qw "TEXTREL" <<<"$dyn"; then
    failc "text relocations present (TEXTREL)"
else
    pass "no text relocations"
fi

# The glue's android/log.h stand-in defers to the NDK's header: when it did not, the
# library imported no __android_log_print and no native line reached logcat
# (c7c52c932a). And every exit in the library is linked to a logged abort
# (-Wl,--wrap=exit in CMakeLists.txt), so an import of exit is a core error that would
# end the app with no trace.
imports="$("$RE" --dyn-syms -W "$SO" | awk '$7 == "UND" { sub(/@.*/, "", $8); print $8 }')"
if grep -qx '__android_log_print' <<<"$imports"; then
    pass "imports __android_log_print"
else
    failc "imports no __android_log_print: native logging reaches nothing"
fi
if grep -qx 'exit' <<<"$imports"; then
    failc "imports exit: a core error would end the app with no log"
else
    pass "imports no exit"
fi

# AArch64 branch protection: BTI landing pads + PAC return-address signing.
case "$machine" in
    *AArch64* | *aarch64*)
        feat="$(grep -i "aarch64 feature" <<<"$notes" || true)"
        if grep -qi "BTI" <<<"$feat" && grep -qi "PAC" <<<"$feat"; then
            pass "AArch64 branch protection (BTI + PAC)"
        else
            failc "missing BTI/PAC GNU property (found: ${feat:-none})"
        fi
        ;;
    *)
        printf '  SKIP  branch protection (not AArch64)\n'
        ;;
esac

if [[ "$rc" -ne 0 ]]; then
    echo "Native hardening contract FAILED for $SO" >&2
fi
exit "$rc"
