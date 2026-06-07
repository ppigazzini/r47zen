#!/bin/bash

# Contract test for verify_published_release_artifacts.sh. Builds synthetic
# evidence bundles on the host (no SDK, no device) and asserts the verifier
# accepts a valid published release and rejects each corruption: tampered bytes,
# wrong version, leaked ABI, wrong signing mode, and -- the load-bearing new
# case -- an APK with no v2+ APK Signing Block (an unsigned or signing-stripped
# release). The v2+ block is the literal magic "APK Sig Block 42" that sits
# between the last entry and the central directory of a signed APK.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="$SCRIPT_DIR/verify_published_release_artifacts.sh"
SIG_MAGIC="APK Sig Block 42"
PIN_ABI="arm64-v8a"
VC="2026060701"
VN="0.1.0-signed.2026060701"

pass=0
fail=0

# write_bundle <dir> <type> <name> <vc> <vn> <sign> <abis> <with_sig_block:yes|no>
write_bundle() {
    local dir="$1" type="$2" name="$3" vc="$4" vn="$5" sign="$6" abis="$7" sig="$8"
    mkdir -p "$dir"
    printf 'fake-%s-bytes' "$type" >"$dir/$name"
    if [ "$type" = apk ] && [ "$sig" = yes ]; then
        printf '%s' "$SIG_MAGIC" >>"$dir/$name"
    fi
    {
        echo "artifact_type=$type"
        echo "artifact_name=$name"
        echo "version_code=$vc"
        echo "version_name=$vn"
        echo "artifact_signed=$sign"
    } >"$dir/BUILD-METADATA.txt"
    # Mimic the evidence collector: digest recorded under the build-time path.
    sha256sum "$dir/$name" | sed "s#$dir/#ci-artifacts/release-$type/#" >"$dir/SHA256SUMS.txt"
    if [ "$type" = apk ]; then
        tr ',' '\n' <<<"$abis" | sed '/^$/d' >"$dir/abis.txt"
    fi
}

run_verifier() {
    bash "$VERIFIER" \
        --apk-evidence-dir "$1" \
        --aab-evidence-dir "$2" \
        --expected-version-code "$3" \
        --expected-version-name "$4" \
        --expected-apk-abis "$5" \
        --expected-signing-mode release
}

assert_case() {
    local desc="$1" want="$2"
    shift 2
    local rc
    set +e
    run_verifier "$@" >/dev/null 2>&1
    rc=$?
    set -e
    if { [ "$want" = pass ] && [ "$rc" -eq 0 ]; } ||
        { [ "$want" = fail ] && [ "$rc" -ne 0 ]; }; then
        echo "OK: $desc"
        pass=$((pass + 1))
    else
        echo "FAIL: $desc (exit=$rc, expected to $want)"
        fail=$((fail + 1))
    fi
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A valid signed release: APK with the v2+ block, matching AAB.
write_bundle "$work/valid_apk" apk app.apk "$VC" "$VN" release "$PIN_ABI" yes
write_bundle "$work/valid_aab" aab app.aab "$VC" "$VN" release "" no
assert_case "valid signed release passes" pass "$work/valid_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# The new guard: an APK with no v2+ signing block must be rejected.
write_bundle "$work/unsigned_apk" apk app.apk "$VC" "$VN" release "$PIN_ABI" no
assert_case "unsigned APK (no v2+ signing block) fails" fail "$work/unsigned_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# Tampered bytes: stale recorded digest.
write_bundle "$work/tampered_apk" apk app.apk "$VC" "$VN" release "$PIN_ABI" yes
printf 'TAMPER' >>"$work/tampered_apk/app.apk"
assert_case "tampered APK bytes fail" fail "$work/tampered_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# Wrong version.
assert_case "wrong version_code fails" fail "$work/valid_apk" "$work/valid_aab" 9999999999 "$VN" "$PIN_ABI"

# Leaked extra ABI.
write_bundle "$work/leaked_apk" apk app.apk "$VC" "$VN" release "arm64-v8a,x86_64" yes
assert_case "leaked x86_64 ABI fails" fail "$work/leaked_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# Wrong signing mode.
write_bundle "$work/debug_apk" apk app.apk "$VC" "$VN" debug "$PIN_ABI" yes
assert_case "debug signing mode fails" fail "$work/debug_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

echo "--- published-artifacts verifier contract: ${pass} passed, ${fail} failed ---"
[ "$fail" -eq 0 ]
