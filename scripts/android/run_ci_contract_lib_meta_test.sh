#!/bin/bash

# Meta-test for scripts/lib/ci_contract.sh: the comment-safe helpers must treat a
# directive that appears only in a YAML comment as absent, and a real (non
# -comment) occurrence as present. This guards the class of bug where a
# "# ... gh attestation verify ..." comment matches as if it were a real step.
# Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
fixture="$fixture_dir/wf.yml"

# strip_yaml_comments: a directive only in a comment must be dropped.
commented='      # run: gh attestation verify "$artifact"'
real='        run: gh attestation verify "$artifact"'
printf '%s\n%s\n' "$commented" "$real" >"$fixture"

if printf '%s\n' "$commented" | strip_yaml_comments | grep -q 'gh attestation verify'; then
    echo "FAIL: strip_yaml_comments did not drop a comment-only directive." >&2
    exit 1
fi
if ! printf '%s\n' "$real" | strip_yaml_comments | grep -q 'gh attestation verify'; then
    echo "FAIL: strip_yaml_comments dropped a real directive line." >&2
    exit 1
fi

# workflow_job_block: extract a named job, stop at the next job header.
cat >"$fixture" <<'YAML'
jobs:
  alpha:
    steps:
      - run: echo alpha
  beta:
    steps:
      - run: echo beta
YAML
block="$(workflow_job_block "$fixture" alpha)"
if ! grep -q 'echo alpha' <<<"$block" || grep -q 'echo beta' <<<"$block"; then
    echo "FAIL: workflow_job_block did not isolate the alpha job." >&2
    printf '%s\n' "$block" >&2
    exit 1
fi

echo "OK: ci_contract.sh helpers treat comment-only directives as absent and isolate job blocks."
