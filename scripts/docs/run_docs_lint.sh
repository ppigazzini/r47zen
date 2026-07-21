#!/usr/bin/env bash
# scripts/docs/run_docs_lint.sh
#
# Docs rot gate. The tracked docs must not make a claim this repo contradicts.
#
# Tracked docs are accurate when written and rot where the thing under them
# moves -- especially upstream, which moves with no commit here. This settles the
# rot classes a machine can settle; whether a sentence is TRUE still needs a
# reader. It needs no upstream clone, no Android SDK, and no Python: it only reads
# this repo, so it runs in seconds on every push.
#
# Each check is paid for by a real failure mode in this repo:
#
#   * The maintainer set was renumbered NN- -> 0N- and a writing page was added,
#     rewriting every cross-reference in one pass. A typo in any of them is a dead
#     link the reader hits and the author never does.               -> check 1
#   * Docs name their owner constantly ("build_android.sh", "upstream.sh"). A
#     rename leaves the prose reading perfectly and pointing at nothing.
#                                                                    -> check 2
#   * "ASCII by default" is a non-negotiable that nothing enforced; an em dash or
#     smart quote arrives by paste and survives review.              -> check 3
#   * __DEV/ is gitignored, so a pointer to a file inside it is a dangling
#     reference for every reader who is not its author.              -> check 4
#   * Claude Code reads CLAUDE.md and never AGENTS.md, so CLAUDE.md's @AGENTS.md
#     import is the only thing that loads the contract at all.       -> check 5
#
# NOT checked, deliberately: whether a sentence is true. A fluent invented
# rationale parses, links, and names no dead path. Only a reader catches that.
#
# Usage:  bash scripts/docs/run_docs_lint.sh     # from anywhere
# Exit:   0 all checks pass, 1 a tracked doc contradicts the repo.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
fail=0
note() {
    printf 'docs-lint: %s\n' "$*"
    fail=1
}

# The one non-ASCII codepoint tracked docs may carry: U+00B7 MIDDLE DOT, the
# product's visible-space legend placeholder (rendered as the literal glyph in
# the keypad and its contract pages). Any other non-ASCII byte is a paste error.
readonly ALLOWED_NON_ASCII='·'

mapfile -t DOCS < <(git ls-files '*.md')
# A run that finds no tracked markdown (broken .git, exported tarball, wrong cwd)
# would iterate every check over nothing and report PASS. That is a fail-open on
# a gate; refuse to pass instead.
if [[ ${#DOCS[@]} -eq 0 ]]; then
    echo "docs rot gate: no tracked markdown found (is this a git checkout?)" >&2
    exit 1
fi
log "docs rot gate over ${#DOCS[@]} tracked markdown files"

# --- 1. every internal link resolves ------------------------------------------
# Resolve relative to the LINKING FILE's directory, not the cwd: README.md links
# to "01-build-and-source-layout.md", which only exists as
# android/docs/dev/01-build-and-source-layout.md. Checking these from the repo
# root reports every one of them as broken.
n=0
for f in "${DOCS[@]}"; do
    dir="$(dirname "$f")"
    while IFS= read -r target; do
        case "$target" in http* | mailto* | "") continue ;; esac
        path="${target%%#*}"         # strip the #anchor
        [[ -n "$path" ]] || continue # a bare #anchor is intra-file
        [[ -e "$dir/$path" ]] || {
            note "BROKEN LINK   $f -> $target"
            n=$((n + 1))
        }
    done < <(grep -oE '\]\([^) ]+\)' "$f" | sed 's/^](//; s/)$//')
done
log "check 1: internal links resolve ($n broken)"

# --- 2. every repo path named in prose exists ---------------------------------
# Only backticked paths under the directories this repo owns. A bare filename
# ("build_android.sh") is not checked -- write the path if you want the gate to
# hold it. An ellipsis marks a placeholder ("scripts/..."), not a claim that a
# file exists, so those are skipped: prose has to be able to name a shape.
n=0
while IFS= read -r p; do
    case "$p" in *...*) continue ;; esac
    [[ -e "$p" ]] || {
        note "DEAD PATH     $p (named in a tracked doc, not in this repo)"
        n=$((n + 1))
    }
done < <(grep -ohE '`(scripts|\.github)/[A-Za-z0-9_/.-]+`' "${DOCS[@]}" |
    tr -d '`' | grep -vE '/$' | sort -u)
log "check 2: repo paths named in prose exist ($n dead)"

# --- 3. tracked docs are ASCII, bar the documented placeholder ----------------
# Strip the one allowed codepoint, then any remaining non-ASCII is a violation.
n=0
for f in "${DOCS[@]}"; do
    offenders="$(LC_ALL=C.UTF-8 grep -oP '[^\x00-\x7F]' "$f" 2>/dev/null |
        grep -vFx "$ALLOWED_NON_ASCII" | sort -u | tr -d '\n')"
    [[ -z "$offenders" ]] || {
        note "NON-ASCII     $f: $offenders"
        n=$((n + 1))
    }
done
log "check 3: tracked docs are ASCII bar U+00B7 ($n violations)"

# --- 4. no tracked doc points at a file under __DEV/ --------------------------
# __DEV/ is gitignored and maintainer-only. Naming the directory to mark it
# ignored ("__DEV/", "__DEV/reports/", the __DEV/__sign backlog item) is allowed;
# pointing at a specific file inside it -- anything with an extension -- rests a
# claim on content only its author can see. That is what this bans.
n=0
while IFS= read -r hit; do
    note "CITES __DEV/  $hit"
    n=$((n + 1))
done < <(grep -nE '__DEV/[A-Za-z0-9_./-]*\.[A-Za-z0-9]+' "${DOCS[@]}")
log "check 4: no tracked doc points at a file under __DEV/ ($n citations)"

# --- 5. the agent contract is loadable ----------------------------------------
# AGENTS.md is the cross-tool convention; Claude Code reads CLAUDE.md and never
# AGENTS.md, so CLAUDE.md's `@AGENTS.md` import is the only thing that makes the
# contract load at all. Claude Code's import parser SKIPS code spans and fenced
# blocks, so backticking or fencing that line silently loads nothing -- it still
# renders fine on GitHub, which is what makes it worth a gate.
n=0
for f in AGENTS.md CLAUDE.md; do
    [[ -f "$f" ]] || {
        note "MISSING       $f (the agent contract)"
        n=$((n + 1))
    }
done
if [[ -f CLAUDE.md ]]; then
    if ! awk '/^```/{f=!f; next} !f' CLAUDE.md |
        grep -qE '^[[:space:]]*@AGENTS\.md[[:space:]]*$'; then
        note "IMPORT DEAD   CLAUDE.md has no bare '@AGENTS.md' line (backticked, fenced or gone: Claude Code would load nothing)"
        n=$((n + 1))
    fi
fi
log "check 5: the AGENTS.md/CLAUDE.md contract is loadable ($n problems)"

if [[ "$fail" -ne 0 ]]; then
    log "DOCS GATE FAILED"
    exit 1
fi
log "docs gate PASSED"
