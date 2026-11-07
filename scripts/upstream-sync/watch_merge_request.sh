#!/bin/bash
# Keep a branch that waits on an upstream merge request proven against it. Each
# named workflow runs from the branch's own tree on the request's merge ref, once
# for each pair of branch commit and merge commit, so a push to the branch or a
# move of the request is what starts a run. An open request passes, and so does a
# locked one, which GitLab shows for a moment while it merges. A merged one means the
# branch must land now, and a closed one means its plan has gone, so both fail for a
# person to read.
#
# Usage: watch_merge_request.sh <request number> <branch> <workflow file>...
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

[ "$#" -ge 3 ] || {
    echo "usage: $0 <request number> <branch> <workflow file>..." >&2
    exit 2
}
request="$1"
branch="$2"
shift 2

: "${GITHUB_REPOSITORY:?this script dispatches workflows in GITHUB_REPOSITORY}"

upstream_url="$(sed -n 's/^upstream_url=//p' "$PROJECT_ROOT/upstream.source")"
project_path="${upstream_url#https://gitlab.com/}"
project_path="${project_path%.git}"

state="$(curl --fail --silent --show-error --retry 3 --max-time 30 \
    "https://gitlab.com/api/v4/projects/${project_path//\//%2F}/merge_requests/$request" |
    jq -r .state)"
case "$state" in
    opened | locked) ;;
    merged)
        echo "Upstream merged !$request: land $branch on today's upstream HEAD." >&2
        exit 1
        ;;
    *)
        echo "Upstream !$request is '$state': $branch has no merge to wait for." >&2
        exit 1
        ;;
esac

merge_commit="$(git ls-remote "$upstream_url" "refs/merge-requests/$request/merge" | awk 'NR == 1 { print $1 }')"
[[ "$merge_commit" =~ ^[0-9a-f]{40}$ ]] || {
    echo "No merge ref for !$request at $upstream_url: got '$merge_commit'." >&2
    exit 1
}

branch_commit="$(gh api "repos/$GITHUB_REPOSITORY/commits/$branch" --jq .sha)"

for workflow in "$@"; do
    # A dispatched lane names the upstream commit in its run title.
    last_run="$(gh run list --repo "$GITHUB_REPOSITORY" --workflow "$workflow" --branch "$branch" \
        --event workflow_dispatch --limit 1 --json headSha,displayTitle \
        --jq '.[0] | "\(.headSha) \(.displayTitle)"')"
    if [[ "$last_run" == "$branch_commit "*"$merge_commit"* ]]; then
        echo "$workflow already ran $branch ${branch_commit:0:10} on merge ref ${merge_commit:0:10}."
        continue
    fi
    echo "Dispatching $workflow: $branch ${branch_commit:0:10} on merge ref ${merge_commit:0:10}."
    gh workflow run "$workflow" --repo "$GITHUB_REPOSITORY" --ref "$branch" -f "upstream_commit=$merge_commit"
done
