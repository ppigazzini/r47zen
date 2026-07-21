#!/bin/bash

# Verify the exact production artifacts that the release lane publishes.
#
# The signed APK and AAB that get attached to the GitHub release are produced by
# build-production-release-bundle and uploaded as evidence bundles. This script
# runs against those downloaded bundles and asserts that the published bytes
# match their recorded digest and carry the requested identity, so the release
# gate verifies the artifact that ships rather than a separately rebuilt one.
#
# It needs no Android SDK and no signing key: it reads the evidence each bundle
# already carries (BUILD-METADATA.txt, SHA256SUMS.txt, abis.txt) and recomputes
# the artifact digest.

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage:
    scripts/android/verify_published_release_artifacts.sh \
    --apk-evidence-dir <dir> \
    --aab-evidence-dir <dir> \
    --expected-version-code <code> \
    --expected-version-name <name> \
    --expected-apk-abis <abi[,abi...]> \
    [--expected-signing-mode <mode>] \
    [--expected-cert-sha256 <hex>]
EOF
}

apk_evidence_dir=""
aab_evidence_dir=""
expected_version_code=""
expected_version_name=""
expected_apk_abis=""
expected_signing_mode="release"
# Optional out-of-band pin of the signing certificate SHA-256. When set (the
# public cert fingerprint of the release key, safe to track), the published
# APK's and AAB's recorded cert must equal it, so a build signed with the wrong
# key fails even though the bundle is internally consistent. Unset keeps the
# self-consistency checks (APK cert == AAB cert, and present for a signed mode).
expected_cert_sha256="${R47_RELEASE_EXPECTED_CERT_SHA256:-}"
apk_signing_cert=""
aab_signing_cert=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk-evidence-dir)
            apk_evidence_dir="$2"
            shift 2
            ;;
        --aab-evidence-dir)
            aab_evidence_dir="$2"
            shift 2
            ;;
        --expected-version-code)
            expected_version_code="$2"
            shift 2
            ;;
        --expected-version-name)
            expected_version_name="$2"
            shift 2
            ;;
        --expected-apk-abis)
            expected_apk_abis="$2"
            shift 2
            ;;
        --expected-signing-mode)
            expected_signing_mode="$2"
            shift 2
            ;;
        --expected-cert-sha256)
            expected_cert_sha256="$2"
            shift 2
            ;;
        --help | -h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -z "$apk_evidence_dir" || -z "$aab_evidence_dir" ]]; then
    echo "Both --apk-evidence-dir and --aab-evidence-dir are required." >&2
    usage >&2
    exit 1
fi

if [[ -z "$expected_version_code" || -z "$expected_version_name" ]]; then
    echo "Both --expected-version-code and --expected-version-name are required." >&2
    usage >&2
    exit 1
fi

if [[ -z "$expected_apk_abis" ]]; then
    echo "--expected-apk-abis is required." >&2
    usage >&2
    exit 1
fi

# Normalize the optional pin to the same shape the evidence records: lowercase
# hex, no separators.
expected_cert_sha256="$(printf '%s' "$expected_cert_sha256" | tr -d ':' | tr 'A-F' 'a-f')"

read_metadata_value() {
    local key="$1"
    local metadata_file="$2"
    sed -n "s/^${key}=//p" "$metadata_file" | head -n 1
}

compute_sha256() {
    local target="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$target" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$target" | awk '{print $1}'
    else
        echo "Neither sha256sum nor shasum is available." >&2
        exit 1
    fi
}

assert_equal() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if [[ "$expected" != "$actual" ]]; then
        echo "FAIL: ${label}: expected '${expected}', found '${actual}'." >&2
        exit 1
    fi
    echo "OK: ${label} = ${actual}"
}

verify_evidence_dir() {
    local evidence_dir="$1"
    local expected_type="$2"

    echo "== Verifying published ${expected_type} evidence in ${evidence_dir} =="

    local metadata_file="$evidence_dir/BUILD-METADATA.txt"
    local sums_file="$evidence_dir/SHA256SUMS.txt"

    if [[ ! -f "$metadata_file" ]]; then
        echo "FAIL: missing BUILD-METADATA.txt in ${evidence_dir}." >&2
        exit 1
    fi
    if [[ ! -f "$sums_file" ]]; then
        echo "FAIL: missing SHA256SUMS.txt in ${evidence_dir}." >&2
        exit 1
    fi

    local artifact_type artifact_name version_code version_name signing_mode signing_cert
    artifact_type="$(read_metadata_value artifact_type "$metadata_file")"
    artifact_name="$(read_metadata_value artifact_name "$metadata_file")"
    version_code="$(read_metadata_value version_code "$metadata_file")"
    version_name="$(read_metadata_value version_name "$metadata_file")"
    signing_mode="$(read_metadata_value artifact_signed "$metadata_file")"
    signing_cert="$(read_metadata_value artifact_signing_cert_sha256 "$metadata_file")"

    assert_equal "${expected_type} artifact_type" "$expected_type" "$artifact_type"

    if [[ -z "$artifact_name" ]]; then
        echo "FAIL: BUILD-METADATA.txt in ${evidence_dir} has no artifact_name." >&2
        exit 1
    fi

    local artifact_path="$evidence_dir/$artifact_name"
    if [[ ! -s "$artifact_path" ]]; then
        echo "FAIL: published ${expected_type} not found or empty: ${artifact_path}." >&2
        exit 1
    fi

    # SHA256SUMS.txt records the digest under the build-time path; match the line
    # whose filename basename equals this artifact, not the first line. Matching
    # by position was safe only while the file held exactly one entry; the moment
    # it gains a second (mapping.txt, a symbols zip) the first line could be a
    # different artifact's digest.
    local recorded_sha actual_sha
    recorded_sha="$(awk -v name="$artifact_name" '
        { n = $2; sub(/.*\//, "", n); if (n == name) { print $1; found = 1; exit } }
        END { exit found ? 0 : 1 }
    ' "$sums_file")" || {
        echo "FAIL: SHA256SUMS.txt in ${evidence_dir} has no entry for ${artifact_name}." >&2
        exit 1
    }
    actual_sha="$(compute_sha256 "$artifact_path")"
    assert_equal "${expected_type} sha256 integrity" "$recorded_sha" "$actual_sha"

    assert_equal "${expected_type} version_code" "$expected_version_code" "$version_code"
    assert_equal "${expected_type} version_name" "$expected_version_name" "$version_name"
    assert_equal "${expected_type} signing mode" "$expected_signing_mode" "$signing_mode"

    # A signed release must carry a cryptographically derived signer cert digest
    # (recorded at build time by collect_packaging_evidence via apksigner/keytool).
    # Its absence means the artifact was unsigned or built before this evidence
    # existed; either way it must not pass as a signed release.
    if [[ "$expected_signing_mode" == "release" || "$expected_signing_mode" == "prerelease" ]]; then
        if [[ -z "$signing_cert" || "$signing_cert" == "unknown" ]]; then
            echo "FAIL: ${expected_type} ${artifact_name} has no recorded signing certificate digest (unsigned or pre-cert build?)." >&2
            exit 1
        fi
    fi
    if [[ "$expected_type" == "apk" ]]; then
        apk_signing_cert="$signing_cert"
    else
        aab_signing_cert="$signing_cert"
    fi
    if [[ -n "$expected_cert_sha256" && -n "$signing_cert" && "$signing_cert" != "unknown" ]]; then
        assert_equal "${expected_type} signing cert sha256" "$expected_cert_sha256" "$signing_cert"
    fi

    if [[ "$expected_type" == "apk" ]]; then
        local abis_file="$evidence_dir/abis.txt"
        if [[ ! -f "$abis_file" ]]; then
            echo "FAIL: missing abis.txt in ${evidence_dir}." >&2
            exit 1
        fi
        local expected_abi_list actual_abi_list
        expected_abi_list="$(tr ',' '\n' <<<"$expected_apk_abis" | sed '/^$/d' | sort | paste -sd ',' -)"
        actual_abi_list="$(sed '/^$/d' "$abis_file" | sort | paste -sd ',' -)"
        assert_equal "apk packaged ABIs" "$expected_abi_list" "$actual_abi_list"

        # The published APK must carry a v2+ APK Signing Block: the literal magic
        # "APK Sig Block 42" sits between the last entry and the central directory
        # of a signed APK. This catches an unsigned or signing-stripped release
        # without needing the Android SDK (apksigner).
        if ! LC_ALL=C grep -qa "APK Sig Block 42" "$artifact_path"; then
            echo "FAIL: apk v2+ signing block: published APK ${artifact_name} has no APK Signing Block (unsigned?)." >&2
            exit 1
        fi
        echo "OK: apk v2+ signing block present"
    fi

    echo "Published ${expected_type} verified: ${artifact_name}"
}

verify_evidence_dir "$apk_evidence_dir" "apk"
verify_evidence_dir "$aab_evidence_dir" "aab"

# The APK and AAB ship from one release build and must carry the same signer.
# A mismatch means the two artifacts were signed by different keys.
if [[ -n "$apk_signing_cert" && "$apk_signing_cert" != "unknown" &&
    -n "$aab_signing_cert" && "$aab_signing_cert" != "unknown" ]]; then
    assert_equal "apk/aab signing cert match" "$apk_signing_cert" "$aab_signing_cert"
fi

if [[ -z "$expected_cert_sha256" ]]; then
    echo "NOTE: no --expected-cert-sha256 pin supplied; verified signer self-consistency only." >&2
    echo "NOTE: observed signing cert sha256 = ${apk_signing_cert:-unknown}. Pin it via" >&2
    echo "NOTE: R47_RELEASE_EXPECTED_CERT_SHA256 to assert signer identity out-of-band." >&2
fi

echo "All published release artifacts verified against their recorded evidence."
