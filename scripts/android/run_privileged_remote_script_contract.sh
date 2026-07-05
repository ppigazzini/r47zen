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
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# A remote fetch (wget/curl) whose output is executed by a shell, either piped
# (`... | bash`, `... | sudo sh`) or substituted into one
# (`bash -c "$(wget ...)"`). Matches with or without sudo; the strict rule is
# "never run un-verified fetched scripts", root or not. workflow_grep drops
# comment lines so a documented example does not trip the guard.
pattern='(wget|curl)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(bash|sh)\b|(sudo[[:space:]]+)?(bash|sh)[[:space:]]+-c[[:space:]]+"\$\((wget|curl)'
# Scan both the workflows and the scripts they invoke. This guard file itself
# embeds the pattern as a string literal, so exclude it from the scripts scan.
offenders="$(
    {
        workflow_grep "$pattern"
        scripts_grep "$pattern" "$(basename "${BASH_SOURCE[0]}")"
    } || true
)"

if [ -n "$offenders" ]; then
    contract_fail \
        "a workflow or script pipes a fetched script into a shell (inline reviewed commands or pin + verify a vendored copy instead):" \
        "$offenders"
fi

contract_pass "no workflow or script feeds a network-fetched script directly into a shell."
