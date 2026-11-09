#!/bin/bash
# Regenerate android/gradle/verification-metadata.xml, the SHA-256 of every
# artifact the Gradle build resolves, after a dependency or plugin bump.
#
# Gradle checks each artifact it resolves against this file, from the network or
# from a cache, and fails on any it lacks or that differs: a pin names
# coordinates, this names content. A bump therefore needs this run and
# its diff in the same commit, Dependabot's Gradle pull requests included, since
# Dependabot does not write the file.
#
# AGP resolves some configurations (lint, aapt2, the kover and detekt tools) only
# while their tasks run, so the file is written by running the tasks CI runs, in
# both test build types, not by `help`. The native build resolves nothing from a
# repository and is skipped, so no staged tree or NDK compile is needed. The file
# is started empty, so versions a bump retired drop out. Review the diff: Gradle
# records whatever the repositories serve at this moment.
#
# aapt2 is resolved per host OS. This file holds the Linux one, which every lane
# and this repo's maintainer use; a build on macOS or Windows needs
# --dependency-verification lenient until its classifier is added.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/../../android"
METADATA="$ANDROID_DIR/gradle/verification-metadata.xml"

skip_native=(
    -x prepareStagedNativeInputs
    -x externalNativeBuildDebug -x externalNativeBuildRelease
    -x buildCMakeDebug -x buildCMakeRelWithDebInfo
    -x configureCMakeDebug -x configureCMakeRelWithDebInfo
)

cd "$ANDROID_DIR"
rm -f "$METADATA"
./gradlew --console=plain --write-verification-metadata sha256 \
    -Pr47.testBuildType=release -Pr47.includeProgramLoadTestBridge=true \
    -Pr47.includeInstrumentationHost=true -Pr47.releaseMinify=false \
    -Pr47.releaseShrinkResources=false \
    lint :app:assembleRelease :app:bundleRelease :app:assembleReleaseAndroidTest \
    :app:testReleaseUnitTest :app:koverXmlReportRelease :app:assembleDebug \
    "${skip_native[@]}"
./gradlew --console=plain --write-verification-metadata sha256 \
    -Pr47.testBuildType=debug \
    :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:detektMain :app:detektTest \
    "${skip_native[@]}"

echo "Wrote $(grep -c '<component ' "$METADATA") components to ${METADATA#"$SCRIPT_DIR/../../"}; review the diff before committing."
