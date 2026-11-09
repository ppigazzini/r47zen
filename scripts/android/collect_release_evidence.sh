#!/bin/bash
# Collect the packaging evidence of a signed release APK and bundle, for the two
# jobs of android-release.yml that sign: sign-production-release with the release
# key, and sign-dry-run with a key the dry run made. Both sign the same bytes the
# same way, so the evidence is collected the same way, here.
#
# Every input comes through the environment, never a ${{ }} expression in the
# step: the names and the upstream version come from a job that ran upstream
# code, so each is taken as plain data and refused when it is not the shape of
# its kind.
#
#   R47_VERSION_CODE_INPUT, R47_VERSION_NAME_INPUT  from resolve-release-inputs
#   RELEASE_APK_NAME, RELEASE_BUNDLE_NAME           from the build job
#   UPSTREAM_URL, UPSTREAM_COMMIT                   from resolve-upstream-core
#   UPSTREAM_VERSION, HOST_TOOLS                    from the build job
#
# It reads ci-artifacts/release-signed/ and ci-artifacts/release-unsigned/ and
# writes ci-artifacts/release-aab/ and ci-artifacts/release-apk/.

set -euo pipefail

: "${R47_VERSION_CODE_INPUT:?}" "${R47_VERSION_NAME_INPUT:?}"
: "${RELEASE_APK_NAME:?}" "${RELEASE_BUNDLE_NAME:?}"
: "${UPSTREAM_URL:?}" "${UPSTREAM_COMMIT:?}"

# shellcheck source=android/r47-defaults.properties
. ./android/r47-defaults.properties

if [[ ! "$RELEASE_APK_NAME" =~ ^[A-Za-z0-9._-]+\.apk$ ||
    ! "$RELEASE_BUNDLE_NAME" =~ ^[A-Za-z0-9._-]+\.aab$ ]]; then
    echo "Refusing the release asset names '${RELEASE_APK_NAME}' and '${RELEASE_BUNDLE_NAME}' from build-production-release-bundle." >&2
    exit 1
fi
if [[ -n "${UPSTREAM_VERSION:-}" && ! "$UPSTREAM_VERSION" =~ ^[0-9A-Za-z.-]{1,40}$ ]]; then
    echo "Refusing the upstream version '${UPSTREAM_VERSION}' from build-production-release-bundle." >&2
    exit 1
fi

bundle_output_dir='ci-artifacts/release-aab'
apk_output_dir='ci-artifacts/release-apk'
bundle_path="ci-artifacts/release-signed/${RELEASE_BUNDLE_NAME}"
release_apk_path="ci-artifacts/release-signed/${RELEASE_APK_NAME}"
mapping_path='ci-artifacts/release-unsigned/mapping.txt'
native_symbols_path="$PWD/ci-artifacts/release-unsigned/native-debug-symbols.zip"

for required in "$bundle_path" "$release_apk_path" "$mapping_path" "$native_symbols_path"; do
    if [[ ! -f "$required" ]]; then
        echo "Missing ${required}." >&2
        exit 1
    fi
done

mkdir -p "$bundle_output_dir" "$apk_output_dir"

# Everything but the artifact itself is the same for both.
evidence_args=(
    --variant release
    --expected-abis "$R47_DEFAULT_ANDROID_ABI_FILTERS"
    --android-sdk-root "${ANDROID_SDK_ROOT:?}"
    --ndk-version "$R47_DEFAULT_ANDROID_NDK_VERSION"
    --compile-sdk "$R47_DEFAULT_ANDROID_COMPILE_SDK"
    --cmake-version "$R47_DEFAULT_ANDROID_CMAKE_VERSION"
    --android-source-repository-url "${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}"
    --android-source-commit "${GITHUB_SHA}"
    --upstream-source-repository-url "$UPSTREAM_URL"
    --upstream-source-commit "$UPSTREAM_COMMIT"
    --upstream-version "${UPSTREAM_VERSION:-}"
    --host-tools "${HOST_TOOLS:-}"
    --xlsxio-source-repository-url "$R47_DEFAULT_XLSXIO_URL"
    --xlsxio-source-commit "$R47_DEFAULT_XLSXIO_COMMIT"
    --application-id "$R47_DEFAULT_ANDROID_APPLICATION_ID"
    --version-code "$R47_VERSION_CODE_INPUT"
    --version-name "$R47_VERSION_NAME_INPUT"
    --signing-mode release
    --ref "${GITHUB_REF}"
    --sha "${GITHUB_SHA}"
    --run-id "${GITHUB_RUN_ID}"
    --run-attempt "${GITHUB_RUN_ATTEMPT}"
    --mapping-file "$mapping_path"
    --native-symbols "$native_symbols_path"
)

bash ./scripts/android/collect_packaging_evidence.sh \
    --bundle "$bundle_path" \
    --output-dir "$bundle_output_dir" \
    --artifact-name "$RELEASE_BUNDLE_NAME" \
    "${evidence_args[@]}"

bash ./scripts/android/collect_packaging_evidence.sh \
    --apk "$release_apk_path" \
    --output-dir "$apk_output_dir" \
    --artifact-name "$RELEASE_APK_NAME" \
    "${evidence_args[@]}"
