#!/bin/bash
# Contract: Gradle verifies every artifact it resolves, strictly.
#
# android/gradle/verification-metadata.xml holds a SHA-256 for each artifact;
# its presence turns verification on, and a missing or differing artifact fails
# the build. This holds that the file exists, verifies metadata, records SHA-256
# checksums, and that nothing loosens it on the shared path: no gradle.properties
# entry and no workflow or script flag sets lenient or off, and only
# update_dependency_verification.sh writes the file. The kotlin-stdlib entry is
# held too, so an emptied file cannot pass. Pure host text check.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
METADATA="$PROJECT_ROOT/android/gradle/verification-metadata.xml"

[ -f "$METADATA" ] || contract_fail "missing android/gradle/verification-metadata.xml: Gradle verifies nothing"
grep -q '<verify-metadata>true</verify-metadata>' "$METADATA" ||
    contract_fail "verification-metadata.xml does not verify metadata"
grep -q '<sha256 value="[0-9a-f]\{64\}"' "$METADATA" ||
    contract_fail "verification-metadata.xml records no SHA-256 checksum"
grep -q '<component group="org.jetbrains.kotlin" name="kotlin-stdlib"' "$METADATA" ||
    contract_fail "verification-metadata.xml has no kotlin-stdlib entry; was it regenerated over the full task graph?"

if grep -Eq '^[[:space:]]*org\.gradle\.dependency\.verification[[:space:]]*=' "$PROJECT_ROOT/android/gradle.properties"; then
    contract_fail "android/gradle.properties sets org.gradle.dependency.verification; verification must stay strict"
fi
loose="$(workflow_grep '--dependency-verification|org\.gradle\.dependency\.verification' || true)"
loose="${loose}$(scripts_grep '--dependency-verification|org\.gradle\.dependency\.verification' run_dependency_verification_contract.sh || true)"
[ -z "$loose" ] || contract_fail "a workflow or script loosens dependency verification:" "$loose"
writers="$(scripts_grep '--write-verification-metadata' run_dependency_verification_contract.sh update_dependency_verification.sh || true)"
writers="${writers}$(workflow_grep '--write-verification-metadata' || true)"
[ -z "$writers" ] || contract_fail "something other than update_dependency_verification.sh writes the metadata:" "$writers"

contract_pass "Gradle verifies every resolved artifact against SHA-256 checksums, strictly."
