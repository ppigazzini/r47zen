#!/bin/bash

# Meta-test for scripts/lib/ci_contract.sh: the comment-safe helpers must treat a
# directive that appears only in a YAML, shell, C, Kotlin, or Groovy comment as
# absent, and a real (non-comment) occurrence as present. This guards the class
# of bug where a "# ... gh attestation verify ..." comment matches as if it were
# a real step. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
fixture="$fixture_dir/wf.yml"

# strip_hash_comments: a directive only in a comment must be dropped.
commented='      # run: gh attestation verify "$artifact"'
real='        run: gh attestation verify "$artifact"'
printf '%s\n%s\n' "$commented" "$real" >"$fixture"

stripped="$(printf '%s\n' "$commented" | strip_hash_comments || true)"
if grep -q 'gh attestation verify' <<<"$stripped"; then
    echo "FAIL: strip_hash_comments did not drop a comment-only directive." >&2
    exit 1
fi
stripped="$(printf '%s\n' "$real" | strip_hash_comments)"
if ! grep -q 'gh attestation verify' <<<"$stripped"; then
    echo "FAIL: strip_hash_comments dropped a real directive line." >&2
    exit 1
fi

# strip_c_comments: // and /* */ comments are blanked, live code is kept, and
# every input line yields one output line.
cat >"$fixture_dir/source.gradle" <<'GROOVY'
// languageVersion = JavaLanguageVersion.of(buildJdkVersion)
/* languageVersion =
   JavaLanguageVersion.of(buildJdkVersion) */
def x = 1 /* JavaLanguageVersion.of(buildJdkVersion) */ + 2
languageVersion = JavaLanguageVersion.of(17) // JavaLanguageVersion.of(buildJdkVersion)
GROOVY
stripped="$(strip_c_comments "$fixture_dir/source.gradle")"
if grep -q 'buildJdkVersion' <<<"$stripped"; then
    echo "FAIL: strip_c_comments kept text that only a comment held:" >&2
    printf '%s\n' "$stripped" >&2
    exit 1
fi
if ! grep -qx 'def x = 1  + 2' <<<"$stripped" ||
    ! grep -qx 'languageVersion = JavaLanguageVersion.of(17) ' <<<"$stripped"; then
    echo "FAIL: strip_c_comments dropped live code next to a comment:" >&2
    printf '%s\n' "$stripped" >&2
    exit 1
fi
if [ "$(wc -l <<<"$stripped")" -ne 5 ]; then
    echo "FAIL: strip_c_comments did not keep one output line per input line." >&2
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

echo "OK: ci_contract.sh helpers treat comment-only directives as absent, keep live code beside a comment, and isolate job blocks."
