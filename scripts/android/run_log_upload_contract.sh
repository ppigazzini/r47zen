#!/bin/bash
# Contract: a job that writes a log under ci-artifacts/logs uploads it, even
# when the job fails.
#
# A failed job's console is cut and kept for a short time; the log a step tees
# is what tells a maintainer why it failed, so it has to leave the runner in the
# case that matters most. So every job whose
# live text tees into ci-artifacts/logs has an actions/upload-artifact step with
# `if: always()` whose path takes ci-artifacts/logs.
#
# The checker first proves it can fail on a seeded job, then scans every
# workflow. Pure host text check, no SDK.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Print "<file>: job <name> ..." for each job that tees a log and uploads none.
find_unuploaded_logs() {
    awk '
        function flush_step() {
            if (step != "" && step ~ /uses:[[:space:]]*actions\/upload-artifact@/ &&
                step ~ /if:[[:space:]]*always\(\)/ && step ~ /ci-artifacts\/logs/) {
                uploads = 1
            }
            step = ""
        }
        function flush_job() {
            flush_step()
            if (job != "" && tees && !uploads) {
                printf "%s: job %s tees a log into ci-artifacts/logs and uploads none with if: always()\n", FILENAME, job
            }
            tees = uploads = 0
        }
        /^[[:space:]]*#/ { next }
        /^jobs:/ { in_jobs = 1; next }
        !in_jobs { next }
        /^  [A-Za-z0-9_-]+:/ { flush_job(); job = $1; sub(/:$/, "", job); next }
        /^      - / { flush_step() }
        /tee[[:space:]].*ci-artifacts\/logs/ { tees = 1 }
        { step = step $0 "\n" }
        END { flush_job() }
    ' "$1"
}

fixture="$(mktemp)"
trap 'rm -f "$fixture" "$fixture.seeded"' EXIT
cat >"$fixture" <<'EOF'
jobs:
  build:
    steps:
      - name: Build
        run: ./build.sh 2>&1 | tee ci-artifacts/logs/build.log

      - name: Upload logs
        if: always()
        uses: actions/upload-artifact@0000000000000000000000000000000000000000 # v7.0.2
        with:
          path: ci-artifacts/logs/
EOF
sed 's/        if: always()//' "$fixture" >"$fixture.seeded"
[ -z "$(find_unuploaded_logs "$fixture")" ] ||
    contract_fail "the log checker flagged a job that uploads its logs"
[ -n "$(find_unuploaded_logs "$fixture.seeded")" ] ||
    contract_fail "the log checker missed a log upload that does not run on failure"

violations=""
for workflow in "$WORKFLOW_DIR"/*.yml; do
    violations="${violations}$(find_unuploaded_logs "$workflow")"
done
if [ -n "$violations" ]; then
    mapfile -t lines <<<"$violations"
    contract_fail "a job writes a log it does not upload when it fails:" "${lines[@]}"
fi
contract_pass "every job that tees a log into ci-artifacts/logs uploads it with if: always()."
