#!/bin/bash

# Contract test for verify_published_release_artifacts.sh. Builds synthetic
# evidence bundles on the host (no SDK, no device) and asserts the verifier
# accepts a valid published release and rejects each corruption: tampered bytes,
# wrong version, leaked ABI, wrong signing mode, an APK with no v2+ APK Signing
# Block (an unsigned or signing-stripped release), and the signer-certificate
# checks -- a signed release missing its recorded cert digest, an APK and AAB
# signed by different keys, a build whose cert does not match the pin, and a
# signed release verified with no pin or a malformed one. Every case passes the
# pin unless it tests the pin, and every rejection must name the check that
# made it, so a case cannot pass by failing for another reason.
# The v2+ block is the literal magic "APK Sig Block 42" that sits between the
# last entry and the central directory of a signed APK; the cert digest
# (artifact_signing_cert_sha256) is recorded at build time by
# collect_packaging_evidence via apksigner/keytool.
#
# Then it holds the tracked pin: R47_DEFAULT_RELEASE_SIGNER_CERT_SHA256 in
# android/r47-defaults.properties is 64 lowercase hex digits, the README states
# that value, and the release workflow hands it to the verifier.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFIER="$SCRIPT_DIR/verify_published_release_artifacts.sh"
SIG_MAGIC="APK Sig Block 42"
PIN_ABI="arm64-v8a"
VC="10"
VN="0.1.13"
# Two synthetic signer cert digests (64 lowercase hex): the release key and a
# different one used for the mismatch and pin cases.
CERT="430e736b0f2f1ca4f5d8da9286961c2273a2e574153a9fb6c8176e97b1e3e3f8"
OTHER_CERT="deadbeefcafef00dfeedface0123456789abcdef0123456789abcdef01234567"

pass=0
fail=0

# write_bundle <dir> <type> <name> <vc> <vn> <sign> <abis> <sig:yes|no> <cert>
write_bundle() {
    local dir="$1" type="$2" name="$3" vc="$4" vn="$5" sign="$6" abis="$7" sig="$8" cert="${9-}"
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
        # Omit the cert line entirely when unset, to mimic a pre-cert build.
        [ -n "$cert" ] && echo "artifact_signing_cert_sha256=$cert"
    } >"$dir/BUILD-METADATA.txt"
    # Mimic the evidence collector: digest recorded under the build-time path.
    sha256sum "$dir/$name" | sed "s#$dir/#ci-artifacts/release-$type/#" >"$dir/SHA256SUMS.txt"
    if [ "$type" = apk ]; then
        tr ',' '\n' <<<"$abis" | sed '/^$/d' >"$dir/abis.txt"
    fi
}

# run_verifier <apk_dir> <aab_dir> <vc> <vn> <abis> [cert_pin|none]
run_verifier() {
    local pin="${6:-$CERT}" extra=()
    [ "$pin" != none ] && extra=(--expected-cert-sha256 "$pin")
    bash "$VERIFIER" \
        --apk-evidence-dir "$1" \
        --aab-evidence-dir "$2" \
        --expected-version-code "$3" \
        --expected-version-name "$4" \
        --expected-apk-abis "$5" \
        --expected-signing-mode release \
        "${extra[@]}"
}

# assert_case <desc> pass -- <run_verifier args>
# assert_case <desc> fail <reason> <run_verifier args>
# A rejection counts only when the verifier's FAIL line names <reason>.
assert_case() {
    local desc="$1" want="$2" reason="$3"
    shift 3
    local rc output
    set +e
    output="$(run_verifier "$@" 2>&1)"
    rc=$?
    set -e
    if [ "$want" = pass ] && [ "$rc" -eq 0 ]; then
        echo "OK: $desc"
        pass=$((pass + 1))
    elif [ "$want" = fail ] && [ "$rc" -ne 0 ] && grep -qF -- "FAIL: $reason" <<<"$output"; then
        echo "OK: $desc"
        pass=$((pass + 1))
    else
        echo "FAIL: $desc (exit=$rc, expected to $want${reason:+ on '$reason'}):"
        printf '%s\n' "$output" | sed 's/^/    /'
        fail=$((fail + 1))
    fi
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A valid signed release: APK with the v2+ block, matching AAB, both recording
# the same signer cert.
write_bundle "$work/valid_apk" apk app.apk "$VC" "$VN" release "$PIN_ABI" yes "$CERT"
write_bundle "$work/valid_aab" aab app.aab "$VC" "$VN" release "" no "$CERT"
assert_case "valid signed release passes" pass -- "$work/valid_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# An APK with no v2+ signing block must be rejected.
write_bundle "$work/unsigned_apk" apk app.apk "$VC" "$VN" release "$PIN_ABI" no "$CERT"
assert_case "unsigned APK (no v2+ signing block) fails" fail "apk v2+ signing block" "$work/unsigned_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# Tampered bytes: stale recorded digest.
write_bundle "$work/tampered_apk" apk app.apk "$VC" "$VN" release "$PIN_ABI" yes "$CERT"
printf 'TAMPER' >>"$work/tampered_apk/app.apk"
assert_case "tampered APK bytes fail" fail "apk sha256 integrity" "$work/tampered_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# Wrong version.
assert_case "wrong version_code fails" fail "apk version_code" "$work/valid_apk" "$work/valid_aab" 9999999999 "$VN" "$PIN_ABI"

# Leaked extra ABI.
write_bundle "$work/leaked_apk" apk app.apk "$VC" "$VN" release "arm64-v8a,x86_64" yes "$CERT"
assert_case "leaked x86_64 ABI fails" fail "apk packaged ABIs" "$work/leaked_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# Wrong signing mode.
write_bundle "$work/debug_apk" apk app.apk "$VC" "$VN" debug "$PIN_ABI" yes "$CERT"
assert_case "debug signing mode fails" fail "apk signing mode" "$work/debug_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# A signed release missing its recorded signer cert digest must be rejected.
write_bundle "$work/nocert_apk" apk app.apk "$VC" "$VN" release "$PIN_ABI" yes ""
assert_case "signed release without a cert digest fails" fail "apk app.apk has no recorded signing certificate digest" "$work/nocert_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI"

# APK and AAB signed by different keys must be rejected.
write_bundle "$work/othercert_aab" aab app.aab "$VC" "$VN" release "" no "$OTHER_CERT"
assert_case "apk/aab signer mismatch fails" fail "aab signing cert sha256" "$work/valid_apk" "$work/othercert_aab" "$VC" "$VN" "$PIN_ABI"

# The pin: a different cert fails, and a signed release verified with no pin,
# or with one that is not 64 hex digits, fails before any artifact is read.
assert_case "cert pin mismatch fails" fail "apk signing cert sha256" "$work/valid_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI" "$OTHER_CERT"
assert_case "signed release with no pin fails" fail "signer pin" "$work/valid_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI" none
assert_case "malformed pin fails" fail "signer pin" "$work/valid_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI" "${CERT:0:63}"
assert_case "colon-separated uppercase pin passes" pass -- "$work/valid_apk" "$work/valid_aab" "$VC" "$VN" "$PIN_ABI" \
    "$(sed 's/../&:/g; s/:$//' <<<"$CERT" | tr 'a-f' 'A-F')"

echo "--- published-artifacts verifier contract: ${pass} passed, ${fail} failed ---"
[ "$fail" -eq 0 ] || exit 1

# The tracked pin. One owner: the defaults file. The README repeats it for
# people who verify an APK by hand, and the release workflow passes it.
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEFAULTS_FILE="$PROJECT_ROOT/android/r47-defaults.properties"
pin="$(sed -n 's/^R47_DEFAULT_RELEASE_SIGNER_CERT_SHA256=//p' "$DEFAULTS_FILE")"
if [[ ! "$pin" =~ ^[0-9a-f]{64}$ ]]; then
    echo "FAIL: R47_DEFAULT_RELEASE_SIGNER_CERT_SHA256 in $DEFAULTS_FILE must be one line of 64 lowercase hex digits, got '$pin'." >&2
    exit 1
fi
readme="$(cat "$PROJECT_ROOT/README.md")"
if ! grep -qxF -- "    $pin" <<<"$readme"; then
    echo "FAIL: README.md does not state the release signer pin $pin as an indented line of its own." >&2
    exit 1
fi
release_workflow="$(grep -vE '^[[:space:]]*#' "$PROJECT_ROOT/.github/workflows/android-release.yml")"
if ! grep -qF -- '--expected-cert-sha256 "$R47_DEFAULT_RELEASE_SIGNER_CERT_SHA256"' <<<"$release_workflow"; then
    echo "FAIL: android-release.yml does not pass R47_DEFAULT_RELEASE_SIGNER_CERT_SHA256 to the verifier." >&2
    exit 1
fi
echo "OK: the release signer pin is $pin in r47-defaults.properties, README.md, and the release verifier."
