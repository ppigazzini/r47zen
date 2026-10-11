#!/bin/bash

# Check commit messages against the commit rules that
# android/docs/dev/10-writing.md states, so the mechanical half of those rules
# no longer rests on a reader:
#
#   - subject: `type(scope): summary` or `type: summary`, a Conventional
#     Commits type, a single-token scope, an optional `!`, at most 72
#     characters;
#   - a blank second line when a body follows;
#   - every body line at most 80 characters;
#   - no Co-authored-by trailer and no agent attribution.
#
# It cannot check that the subject is imperative or that the body carries
# evidence; those stay with the reader.
#
# Usage:
#   check_commit_message.sh MESSAGE_FILE      one message, as git's commit-msg
#                                             hook passes it
#   check_commit_message.sh --range REVS      every non-merge commit in REVS,
#                                             for example origin/main..HEAD
#
# A message file is read the way git cleans it up: comment lines starting with
# `#` and everything below the `--verbose` scissors line are ignored. Subjects
# git writes itself (Merge, fixup!, squash!, amend!) skip the subject checks.
# Exits 1 when any message breaks a rule, after reporting every break.

set -Eeuo pipefail

readonly SUBJECT_MAX=72
readonly BODY_MAX=80
readonly TYPES='build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test'
readonly SUBJECT_RE="^(${TYPES})(\\([A-Za-z0-9][A-Za-z0-9._-]*\\))?!?: [^ ]"

usage() {
    sed -n '3,27p' "$0" | sed 's/^# \{0,1\}//'
}

# Print one line per broken rule for the cleaned message on stdin.
check_message() {
    local -a lines=()
    local line
    while IFS= read -r line || [ -n "$line" ]; do
        lines+=("$line")
    done

    while [ "${#lines[@]}" -gt 0 ] && [ -z "${lines[-1]//[[:space:]]/}" ]; do
        unset 'lines[-1]'
    done
    if [ "${#lines[@]}" -eq 0 ]; then
        echo "the message is empty"
        return
    fi

    local subject="${lines[0]}"
    if [[ ! "$subject" =~ ^(Merge\ |fixup!\ |squash!\ |amend!\ ) ]]; then
        if [ "${#subject}" -gt "$SUBJECT_MAX" ]; then
            echo "subject is ${#subject} characters, over ${SUBJECT_MAX}"
        fi
        if [[ ! "$subject" =~ $SUBJECT_RE ]]; then
            echo "subject is not 'type(scope): summary' with one of" \
                "${TYPES//|/, } and a single-token scope"
        fi
    fi

    if [ "${#lines[@]}" -gt 1 ] && [ -n "${lines[1]}" ]; then
        echo "line 2 is not blank"
    fi

    local index
    for ((index = 1; index < ${#lines[@]}; index++)); do
        line="${lines[index]}"
        if [ "${#line}" -gt "$BODY_MAX" ]; then
            echo "line $((index + 1)) is ${#line} characters, over ${BODY_MAX}"
        fi
        if [[ "${line,,}" =~ ^co-authored-by: ]]; then
            echo "line $((index + 1)) is a Co-authored-by trailer"
        fi
        if [[ "${line,,}" == *noreply@anthropic.com* ||
            "${line,,}" == *"generated with [claude code]"* ]]; then
            echo "line $((index + 1)) is agent attribution"
        fi
    done
}

# Drop what git's default cleanup drops from a commit-msg file.
clean_message_file() {
    awk '/^# -+ >8 -+$/ { exit } /^#/ { next } { print }' "$1"
}

check_range() {
    local revs="$1" commit problems line failed=0 checked=0
    while IFS= read -r commit; do
        checked=$((checked + 1))
        problems="$(git log -1 --format=%B "$commit" | check_message)"
        if [ -n "$problems" ]; then
            failed=$((failed + 1))
            echo "commit-msg: $(git log -1 --format='%h %s' "$commit")" >&2
            while IFS= read -r line; do
                echo "  $line" >&2
            done <<<"$problems"
        fi
    done < <(git rev-list --no-merges "$revs")
    if [ "$failed" -ne 0 ]; then
        echo "commit-msg: ${failed} of ${checked} commit(s) break the commit rules" >&2
        return 1
    fi
    echo "commit-msg: ${checked} commit(s) follow the commit rules"
}

main() {
    export LC_ALL=C.UTF-8
    case "${1:-}" in
        -h | --help)
            usage
            ;;
        --range)
            [ -n "${2:-}" ] || {
                usage >&2
                exit 2
            }
            check_range "$2"
            ;;
        '')
            usage >&2
            exit 2
            ;;
        *)
            [ -f "$1" ] || {
                echo "commit-msg: no such message file: $1" >&2
                exit 2
            }
            local problems line
            problems="$(clean_message_file "$1" | check_message)"
            if [ -n "$problems" ]; then
                while IFS= read -r line; do
                    echo "commit-msg: $line" >&2
                done <<<"$problems"
                echo "commit-msg: rules: android/docs/dev/10-writing.md#commit-messages" >&2
                exit 1
            fi
            ;;
    esac
}

main "$@"
