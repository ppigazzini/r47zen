#!/bin/bash
# Hold what the Android HAL takes from upstream on trust, on two counts.
#
# Who sleeps. hal/keys.c runs service requests only from a sys_sleep at rest, and it
# decides "at rest" from programRunStop, which is sound only while every upstream caller
# on this build is the idle main loop or a program's pause. A new caller could run a
# service inside a computation.
#
# What the copy follows. hal/keys.c is a hand copy of upstream's src/c47-gtk/hal/keys.c,
# written against what the core's loop and polls do with a key. A compile sees a changed
# signature and not a changed meaning, so the files and functions below are hashed, with
# comments and spacing left out. A hash proves only that the text has not moved, not that
# the copy is right: the calculator thread harness is what tests the behavior.
#
# Either count fails until someone reads the change against hal/keys.c. Then update the
# list below, or run this with --print for the new hashes.
#
# Usage: run_upstream_hal_contract.sh [--print | <src dir>]
# With no directory, a clone with no hydrated core checks only the checkers. A lane that
# syncs first names the directory, so a core that is not there fails.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Print "file function" for each sys_sleep caller outside a DMCP-only file or block.
list_sleepers() {
    python3 -I - "$1" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
callers = set()
for path in sorted(root.rglob("*.c")):
    depth_dmcp = []
    function = "?"
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if stripped.startswith("#if"):
            depth_dmcp.append("defined(DMCP_BUILD)" in stripped and "!" not in stripped)
        elif stripped.startswith("#else") and depth_dmcp:
            depth_dmcp[-1] = False
        elif stripped.startswith("#endif") and depth_dmcp:
            depth_dmcp.pop()
        match = re.match(r"\s*(?:static\s+)?[\w*]+\s+[*]*(\w+)\s*\(.*\)\s*\{", line)
        if match and not line.startswith(" " * 4):
            function = match.group(1)
        if re.search(r"\bsys_sleep\s*\(", stripped) and not stripped.startswith("//") and not any(depth_dmcp):
            callers.add(f"{path.name} {function}")
for caller in sorted(callers):
    print(caller)
PY
}

# Print "path name hash" for each "path name" line on stdin, under the directory $1.
# The name * stands for the whole file; a function that upstream defines more than once
# hashes all its definitions. A name that is no longer there hashes as "missing".
hash_upstream_text() {
    python3 -I -c '
import hashlib, pathlib, re, sys

LITERAL = r"\"(?:\\.|[^\"\\\n])*\"|\x27(?:\\.|[^\x27\\\n])*\x27"
COMMENT_OR_LITERAL = re.compile(LITERAL + r"|/\*.*?\*/|//[^\n]*", re.S)
BRACE_OR_LITERAL = re.compile(LITERAL + r"|[{}]")


def without_comments(text):
    return COMMENT_OR_LITERAL.sub(lambda m: " " if m.group(0)[0] == "/" else m.group(0), text)


def definitions(text, name):
    # Braces in a literal do not count. A branch of #if can leave them unbalanced, and
    # then the text runs to the end of the file, which still changes when the code does.
    head = re.compile(r"^[ \t]*[\w \t*]*\b" + re.escape(name) + r"\s*\([^;{}]*\)\s*\{", re.M)
    for match in head.finditer(text):
        depth, at = 1, match.end()
        for token in BRACE_OR_LITERAL.finditer(text, at):
            depth += {"{": 1, "}": -1}.get(token.group(0), 0)
            if depth == 0:
                yield text[match.start() : token.end()]
                break
        else:
            yield text[match.start() :]


root = pathlib.Path(sys.argv[1])
for line in sys.stdin.read().splitlines():
    relative, name = line.split()
    path = root / relative
    digest = "missing"
    if path.is_file():
        text = without_comments(path.read_text(encoding="utf-8", errors="replace"))
        parts = [text] if name == "*" else list(definitions(text, name))
        if parts:
            normalized = "".join(" ".join(parts).split())
            digest = hashlib.sha256(normalized.encode()).hexdigest()[:16]
    print(relative, name, digest)
' "$1"
}

# What the copy follows, from the merge ref of upstream MR !2033.
HASHED='c47-gtk/hal/keys.c * fed19e46b9e2b4c4
c47/hal/keys.h * afef46e29b621316
c47/c47.c mainLoop de01f6fe02f66488
c47/c47.c processKeyCode f891af7d4d427181
c47/c47Extensions/addons.c anyKeyWaiting 0a5844c73ad357f8
c47/c47Extensions/addons.c exitKeyWaiting 8030d46ce0e4b368
c47/c47Extensions/addons.c C47PopKeyNoBuffer 43adde9ef6b15ead
c47/c47Extensions/keyboardTweak.c emptyKeyBuffer 43fb990d79bd524a
c47/c47Extensions/keyboardTweak.c keyBuffer_pop 6479ead70ef827a9
c47/programming/lblGtoXeq.c runProgram c63da3b067a85133
c47/programming/input.c goToSleepForMs c2879fb3431f8f1d
c47/programming/input.c fnPause 094a7959d4c9f5ef
c47/calcMode.c fnOff 6c1a7f7e7332686c'

hashed_names() { awk '{ print $1, $2 }' <<<"$HASHED"; }

if [ "${1:-}" = "--print" ]; then
    hashed_names | hash_upstream_text "$PROJECT_ROOT/src"
    exit 0
fi

expected_sleepers='c47.c mainLoop
input.c goToSleepForMs'

# The checkers must see a new caller and ignore a DMCP-only one, must ignore a comment,
# and must see a changed statement in a function.
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cat >"$fixture/seed.c" <<'C'
void waitForIt(void) {
  sys_sleep();
}
#if defined(DMCP_BUILD)
void firmwareOnly(void) {
  sys_sleep();
}
#endif
C
[ "$(list_sleepers "$fixture")" = "seed.c waitForIt" ] ||
    contract_fail "the caller check did not read its fixture: $(list_sleepers "$fixture")"
seeded_hash() { hash_upstream_text "$fixture" <<<"seed.c waitForIt" | awk '{ print $3 }'; }
before="$(seeded_hash)"
sed -i 's|  sys_sleep();|  // a note, "with a } in it"\n  sys_sleep(  );|' "$fixture/seed.c"
[ "$(seeded_hash)" = "$before" ] || contract_fail "a comment or spacing moved the hash of a function."
sed -i 's|sys_sleep(  );|sys_sleep(  ); sys_delay(1);|' "$fixture/seed.c"
[ "$(seeded_hash)" != "$before" ] || contract_fail "a changed statement did not move the hash of a function."
[ "$(hash_upstream_text "$fixture" <<<"seed.c noSuchFunction" | awk '{ print $3 }')" = "missing" ] ||
    contract_fail "a function that is gone did not read as missing."

SRC_DIR="$PROJECT_ROOT/src"
if [ "$#" -gt 0 ]; then
    SRC_DIR="$1"
    [ -d "$SRC_DIR/c47" ] || contract_fail "no hydrated core at $SRC_DIR."
elif [ ! -d "$SRC_DIR/c47" ]; then
    contract_pass "no hydrated core at $SRC_DIR: checked the checkers only."
fi

actual_sleepers="$(list_sleepers "$SRC_DIR/c47")"
[ "$actual_sleepers" = "$expected_sleepers" ] ||
    contract_fail "upstream's sys_sleep callers changed; read each one against calculatorAtRest in hal/keys.c:" \
        "$actual_sleepers"

moved="$(diff <(printf '%s\n' "$HASHED") <(hashed_names | hash_upstream_text "$SRC_DIR") | sed -n 's/^> //p' || true)"
[ -z "$moved" ] ||
    contract_fail "upstream changed what hal/keys.c follows; read each one against hal/keys.c, then update the list:" \
        "$moved"

contract_pass "sys_sleep has the two upstream callers hal/keys.c assumes, and what the copy follows has not moved."
