#!/bin/bash

# Contract: no CI workflow may fetch a script over the network and feed it
# straight into a shell -- and least of all a root shell. Piping
# `wget`/`curl` output into `bash`/`sudo bash` (or `bash -c "$(wget ...)"`)
# runs whatever the remote host serves, unreviewed, with the runner's
# privileges; a compromised or merely changed upstream becomes remote code
# execution in CI. The safe pattern is to run only reviewed commands: inline the
# handful of steps the upstream installer performs (or pin + verify a vendored
# copy), never to execute whatever the remote host serves at run time.
#
# This guard fails if any workflow reintroduces the fetch-into-shell anti
# pattern. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKFLOW_DIR="$PROJECT_ROOT/.github/workflows"

# A remote fetch (wget/curl) whose output is executed by a shell, either piped
# (`... | bash`, `... | sudo sh`) or substituted into one
# (`bash -c "$(wget ...)"`). Matches with or without sudo; the strict rule is
# "never run un-verified fetched scripts", root or not.
pattern='(wget|curl)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(bash|sh)\b|(sudo[[:space:]]+)?(bash|sh)[[:space:]]+-c[[:space:]]+"\$\((wget|curl)'

# Grep all workflows, then drop YAML comment lines (so a documented example
# does not trip the guard).
offenders="$(
    grep -rnE "$pattern" "$WORKFLOW_DIR" 2>/dev/null |
        grep -vE ':[0-9]+:[[:space:]]*#' || true
)"

if [ -n "$offenders" ]; then
    echo "FAIL: workflow pipes a fetched script into a shell (download + verify a pinned checksum instead):" >&2
    echo "$offenders" >&2
    exit 1
fi

echo "OK: no workflow feeds a network-fetched script directly into a shell."
