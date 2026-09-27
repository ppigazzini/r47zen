#!/bin/bash

# Sign an unsigned release APK and/or App Bundle with one upload key, outside
# Gradle. The signing jobs run this over bytes that a separate, secret-free job
# built. Keep the key off any runner that syncs, compiles, or runs the upstream
# core: that job executes unreviewed upstream code, and a compile step alone can
# embed any readable file (a keystore, /proc/self/environ) into the shipped
# native library.
#
# Passwords come only from R47_SIGNING_STORE_PASSWORD and
# R47_SIGNING_KEY_PASSWORD and reach apksigner, jarsigner, and keytool by
# variable name, never as an argument that /proc/<pid>/cmdline would expose.
#
# Every output is verified before the script returns: the APK with
# `apksigner verify`, the bundle with `jarsigner -verify`, and both signer
# certificates must equal the certificate the keystore holds for the alias.

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEFAULTS_FILE="$PROJECT_ROOT/android/r47-defaults.properties"

usage() {
    cat <<'EOF'
Usage:
    scripts/android/sign_android_artifacts.sh \
    --keystore <path> \
    --key-alias <alias> \
    [--apk <unsigned.apk> --apk-out <signed.apk>] \
    [--bundle <unsigned.aab> --bundle-out <signed.aab>] \
    [--android-sdk-root <path>] \
    [--build-tools-version <version>]

Environment (required):
    R47_SIGNING_STORE_PASSWORD   keystore password
    R47_SIGNING_KEY_PASSWORD     key password

At least one of --apk or --bundle is required. --build-tools-version defaults to
R47_DEFAULT_ANDROID_BUILD_TOOLS_VERSION in android/r47-defaults.properties.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

keystore=""
key_alias=""
apk_in=""
apk_out=""
bundle_in=""
bundle_out=""
android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
build_tools_version=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --keystore)
            keystore="$2"
            shift 2
            ;;
        --key-alias)
            key_alias="$2"
            shift 2
            ;;
        --apk)
            apk_in="$2"
            shift 2
            ;;
        --apk-out)
            apk_out="$2"
            shift 2
            ;;
        --bundle)
            bundle_in="$2"
            shift 2
            ;;
        --bundle-out)
            bundle_out="$2"
            shift 2
            ;;
        --android-sdk-root)
            android_sdk_root="$2"
            shift 2
            ;;
        --build-tools-version)
            build_tools_version="$2"
            shift 2
            ;;
        --help | -h)
            usage
            exit 0
            ;;
        *)
            usage >&2
            fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "$keystore" && -n "$key_alias" ]] || {
    usage >&2
    fail "--keystore and --key-alias are required."
}
[[ -f "$keystore" ]] || fail "Keystore not found: $keystore"
[[ -n "${R47_SIGNING_STORE_PASSWORD:-}" ]] || fail "R47_SIGNING_STORE_PASSWORD is empty."
[[ -n "${R47_SIGNING_KEY_PASSWORD:-}" ]] || fail "R47_SIGNING_KEY_PASSWORD is empty."
[[ -n "$apk_in" || -n "$bundle_in" ]] || {
    usage >&2
    fail "Provide --apk and/or --bundle."
}
if [[ -n "$apk_in" ]]; then
    [[ -n "$apk_out" ]] || fail "--apk requires --apk-out."
    [[ -f "$apk_in" ]] || fail "APK not found: $apk_in"
fi
if [[ -n "$bundle_in" ]]; then
    [[ -n "$bundle_out" ]] || fail "--bundle requires --bundle-out."
    [[ -f "$bundle_in" ]] || fail "App bundle not found: $bundle_in"
fi

for tool in keytool jarsigner unzip; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is not on PATH; install the pinned build JDK."
done

# Normalize a colon-separated or mixed-case SHA-256 fingerprint to bare
# lowercase hex, the form collect_packaging_evidence.sh records.
normalize_sha256() {
    tr -d ':[:space:]' | tr 'A-F' 'a-f'
}

# The identity every signed output must carry: the certificate the keystore
# holds for the alias. Comparing against it catches a wrong alias, a keystore
# with several keys, or a tool that silently signed with something else.
expected_cert_sha256="$(
    keytool -list -v -keystore "$keystore" -alias "$key_alias" \
        -storepass:env R47_SIGNING_STORE_PASSWORD 2>/dev/null |
        sed -n 's/^[[:space:]]*SHA256:[[:space:]]*\([0-9A-Fa-f:]*\).*/\1/p' |
        head -n 1 | normalize_sha256
)"
[[ "$expected_cert_sha256" =~ ^[0-9a-f]{64}$ ]] ||
    fail "Could not read the certificate for alias '$key_alias' from $keystore (wrong alias or store password?)."

if [[ -n "$apk_in" ]]; then
    if [[ -z "$build_tools_version" ]]; then
        [[ -f "$DEFAULTS_FILE" ]] || fail "Missing $DEFAULTS_FILE; pass --build-tools-version."
        build_tools_version="$(sed -n 's/^R47_DEFAULT_ANDROID_BUILD_TOOLS_VERSION=//p' "$DEFAULTS_FILE" | head -n 1)"
    fi
    [[ -n "$android_sdk_root" ]] || fail "Set ANDROID_SDK_ROOT or pass --android-sdk-root."
    # Use the pinned build-tools, not whichever version happens to sort newest,
    # so the signing tool is the one r47-defaults.properties names.
    apksigner="$android_sdk_root/build-tools/$build_tools_version/apksigner"
    [[ -x "$apksigner" ]] || fail "apksigner not found at $apksigner."

    mkdir -p "$(dirname "$apk_out")"
    rm -f "$apk_out" "$apk_out.idsig"
    # v1 through v3 match the Gradle signingConfig the release build type
    # declares, and CERT is the v1 file name Gradle writes. v4 is off: it
    # writes a detached .idsig that only incremental adb installs read, and the
    # published release carries the APK alone. apksigner page-aligns
    # uncompressed native libraries to 16 KB by default, which
    # collect_packaging_evidence.sh then checks with zipalign -P 16.
    "$apksigner" sign \
        --ks "$keystore" \
        --ks-key-alias "$key_alias" \
        --ks-pass env:R47_SIGNING_STORE_PASSWORD \
        --key-pass env:R47_SIGNING_KEY_PASSWORD \
        --v1-signer-name CERT \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --v4-signing-enabled false \
        --out "$apk_out" \
        "$apk_in"

    apk_certs="$("$apksigner" verify --print-certs "$apk_out")" ||
        fail "apksigner verify rejected $apk_out."
    apk_cert_sha256="$(
        printf '%s\n' "$apk_certs" |
            sed -n 's/.*certificate SHA-256 digest: *\([0-9a-fA-F]*\).*/\1/p' |
            head -n 1 | normalize_sha256
    )"
    [[ "$apk_cert_sha256" == "$expected_cert_sha256" ]] ||
        fail "APK signer $apk_cert_sha256 does not match the keystore certificate $expected_cert_sha256."
    echo "Signed APK $apk_out (signer cert sha256 $apk_cert_sha256)."
fi

if [[ -n "$bundle_in" ]]; then
    # jarsigner appends a second signature to an already signed bundle rather
    # than replacing it, so refuse one: the input must be the unsigned bundle a
    # build with no signing config produces.
    if unzip -Z1 "$bundle_in" | grep -Eq '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$'; then
        fail "$bundle_in already carries a JAR signature; pass the unsigned bundle."
    fi

    mkdir -p "$(dirname "$bundle_out")"
    rm -f "$bundle_out"
    cp "$bundle_in" "$bundle_out"
    # A bundle takes a v1 JAR signature, which is what Play verifies against the
    # registered upload key. jarsigner picks the digest and signature algorithms
    # from the key, as the Play upload-key instructions assume.
    jarsigner \
        -keystore "$keystore" \
        -storepass:env R47_SIGNING_STORE_PASSWORD \
        -keypass:env R47_SIGNING_KEY_PASSWORD \
        "$bundle_out" \
        "$key_alias" >/dev/null

    jarsigner -verify "$bundle_out" | grep -q '^jar verified\.' ||
        fail "jarsigner -verify rejected $bundle_out."
    bundle_cert_sha256="$(
        keytool -printcert -jarfile "$bundle_out" 2>/dev/null |
            sed -n 's/^[[:space:]]*SHA256:[[:space:]]*\([0-9A-Fa-f:]*\).*/\1/p' |
            head -n 1 | normalize_sha256
    )"
    [[ "$bundle_cert_sha256" == "$expected_cert_sha256" ]] ||
        fail "Bundle signer $bundle_cert_sha256 does not match the keystore certificate $expected_cert_sha256."
    echo "Signed bundle $bundle_out (signer cert sha256 $bundle_cert_sha256)."
fi
