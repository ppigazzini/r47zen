#!/bin/bash

# Contract: the Linux build dependencies are single-sourced. Every CI job must
# install them by calling scripts/android/install_linux_build_deps.sh, never by
# inlining its own apt list -- inline lists drift out of sync (the wget
# discrepancy that prompted this). The guard fails if any workflow reintroduces
# an inline build-dependency apt install. Pure host test, no apt needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
INSTALLER="scripts/android/install_linux_build_deps.sh"

if [ ! -x "$PROJECT_ROOT/$INSTALLER" ]; then
    contract_fail "$INSTALLER is missing or not executable."
fi

# build-essential is the canonical sentinel of the build-dependency list; it
# must appear only in the installer, never inline in a workflow.
offenders="$(grep -rln 'build-essential' "$WORKFLOW_DIR" 2>/dev/null || true)"
if [ -n "$offenders" ]; then
    contract_fail \
        "workflow(s) inline the build-dependency apt list instead of calling $INSTALLER:" \
        "$offenders"
fi

# And every job that installs build deps must actually invoke the installer.
if ! grep -rqF "$INSTALLER" "$WORKFLOW_DIR" 2>/dev/null; then
    contract_fail "no workflow calls $INSTALLER; the single source is unused."
fi

contract_pass "Linux build dependencies are single-sourced through $INSTALLER."
