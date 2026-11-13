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

# Print "file function" for each function outside a DMCP-only block that sleeps: one that
# calls sys_sleep, or a function of its own file that does, to a fixed point.
list_sleepers() {
    python3 -I - "$1" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
callers = set()
for path in sorted(root.rglob("*.c")):
    depth_dmcp = []
    function = "?"
    calls = {}
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
            calls.setdefault(function, set())
            continue
        if stripped.startswith("//") or any(depth_dmcp):
            continue
        calls.setdefault(function, set()).update(re.findall(r"\b(\w+)\s*\(", stripped))
    sleepers = {name for name, called in calls.items() if "sys_sleep" in called}
    grown = True
    while grown:
        grown = False
        for name, called in calls.items():
            if name not in sleepers and called & sleepers:
                sleepers.add(name)
                grown = True
    callers.update(f"{path.name} {name}" for name in sleepers)
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


def body_from(text, start, at):
    # Braces in a literal do not count. A branch of #if can leave them unbalanced, and
    # then the text runs to the end of the file, which still changes when the code does.
    depth = 1
    for token in BRACE_OR_LITERAL.finditer(text, at):
        depth += {"{": 1, "}": -1}.get(token.group(0), 0)
        if depth == 0:
            return text[start : token.end()]
    return text[start:]


def definitions(text, name):
    head = re.compile(r"^[ \t]*[\w \t*]*\b" + re.escape(name) + r"\s*\([^;{}]*\)\s*\{", re.M)
    found = [body_from(text, match.start(), match.end()) for match in head.finditer(text)]
    if found:
        return found
    # Heads that #if branches choose between, over one body: from the first head through
    # the body after them.
    bare = re.compile(r"^[ \t]*[\w \t*]*\b" + re.escape(name) + r"\s*\([^;{}]*\)[ \t]*$", re.M)
    first = bare.search(text)
    if first is None:
        return []
    brace = text.find("{", first.end())
    return [] if brace < 0 else [body_from(text, first.start(), brace + 1)]


root = pathlib.Path(sys.argv[1])
for line in sys.stdin.read().splitlines():
    relative, name = line.split()
    path = root / relative
    digest = "missing"
    if path.is_file():
        text = without_comments(path.read_text(encoding="utf-8", errors="replace"))
        parts = [text] if name == "*" else definitions(text, name)
        if parts:
            normalized = "".join(" ".join(parts).split())
            digest = hashlib.sha256(normalized.encode()).hexdigest()[:16]
    print(relative, name, digest)
' "$1"
}

# Print each function of the core under $2 that the glue file $1 calls.
list_core_calls() {
    python3 -I - "$1" "$2" <<'PY'
import pathlib, re, sys

STRIP = re.compile(r"/\*.*?\*/|//[^\n]*|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'", re.S)
KEYWORDS = {"if", "for", "while", "switch", "return", "sizeof", "defined", "__typeof__"}
glue = STRIP.sub(" ", pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
own = set(re.findall(r"^[\w \t*]*\b(\w+)\s*\([^;{}]*\)\s*\{", glue, re.M))
called = set(re.findall(r"\b([A-Za-z_]\w*)\s*\(", glue)) - own - KEYWORDS
core = ""
for path in sorted(pathlib.Path(sys.argv[2]).rglob("*.c")):
    core += STRIP.sub(" ", path.read_text(encoding="utf-8", errors="replace")) + "\n"
for name in sorted(called):
    head = r"^[ \t]*[\w \t*]*\b" + re.escape(name) + r"\s*\([^;{}]*\)\s*(\{|$)"
    if re.search(head, core, re.M):
        print(name)
PY
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
c47/c47Extensions/keyboardTweak.c inKeyBuffer ac51f1439cfad3c6
c47/c47Extensions/keyboardTweak.c outKeyBuffer 05ef2d737a0863eb
c47/c47Extensions/keyboardTweak.c clearKeyBuffer fe721def0ea0839c
c47/programming/lblGtoXeq.c runProgram ca641aea3dfced04
c47/programming/input.c goToSleepForMs c2879fb3431f8f1d
c47/programming/input.c fnPause 094a7959d4c9f5ef
c47/programming/input.c pauseKeyExit 1f3695f2085f6935
c47/calcMode.c fnOff 6c1a7f7e7332686c'

hashed_names() { awk '{ print $1, $2 }' <<<"$HASHED"; }

# Core functions hal/keys.c calls without following what they do, each with why.
CALLED_UNREAD='getSystemFlag reads one flag'

if [ "${1:-}" = "--print" ]; then
    hashed_names | hash_upstream_text "$PROJECT_ROOT/src"
    exit 0
fi

# fnPause sleeps through goToSleepForMs: both are a program's pause.
expected_sleepers='c47.c mainLoop
input.c fnPause
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
# A caller sleeps through another function of its file, as fnPause does through
# goToSleepForMs.
mkdir "$fixture/indirect"
cat >"$fixture/indirect/through.c" <<'C'
static void wait(void) {
  sys_sleep();
}
void outer(void) {
  wait();
}
void waitress(void) {
  waitForIt();
}
C
[ "$(list_sleepers "$fixture/indirect")" = $'through.c outer\nthrough.c wait' ] ||
    contract_fail "the caller check did not follow a call inside a file: $(list_sleepers "$fixture/indirect")"
seeded_hash() { hash_upstream_text "$fixture" <<<"seed.c waitForIt" | awk '{ print $3 }'; }
before="$(seeded_hash)"
sed -i 's|  sys_sleep();|  // a note, "with a } in it"\n  sys_sleep(  );|' "$fixture/seed.c"
[ "$(seeded_hash)" = "$before" ] || contract_fail "a comment or spacing moved the hash of a function."
sed -i 's|sys_sleep(  );|sys_sleep(  ); sys_delay(1);|' "$fixture/seed.c"
[ "$(seeded_hash)" != "$before" ] || contract_fail "a changed statement did not move the hash of a function."
[ "$(hash_upstream_text "$fixture" <<<"seed.c noSuchFunction" | awk '{ print $3 }')" = "missing" ] ||
    contract_fail "a function that is gone did not read as missing."
# Heads that #if branches choose between, over one body, as outKeyBuffer's.
cat >"$fixture/split.c" <<'C'
#if defined(TWO)
  int pick(int a, int b)
#else
  int pick(int a)
#endif
{
  return a;
}
C
split_hash() { hash_upstream_text "$fixture" <<<"split.c pick" | awk '{ print $3 }'; }
before="$(split_hash)"
[ "$before" != "missing" ] || contract_fail "a definition with its heads in #if branches read as missing."
sed -i 's|  return a;|  return a + 1;|' "$fixture/split.c"
[ "$(split_hash)" != "$before" ] || contract_fail "a changed body under split heads did not move its hash."

# The call check must see a core function the glue calls, and nothing else.
mkdir -p "$fixture/calls/c47"
cat >"$fixture/calls/c47/core.c" <<'C'
void coreThing(int key) {
  (void)key;
}
C
cat >"$fixture/calls/keys.c" <<'C'
static void own(void) {}
void glue(void) {
  own();
  coreThing(1); // and not "notCalled()"
  memset(0, 0, 0);
}
C
[ "$(list_core_calls "$fixture/calls/keys.c" "$fixture/calls")" = "coreThing" ] ||
    contract_fail "the call check did not read its fixture: $(list_core_calls "$fixture/calls/keys.c" "$fixture/calls")"

# Print each core function the glue calls that is neither followed nor named unread.
unread_core_calls() {
    local followed
    followed="$(
        awk '{ print $2 }' <<<"$HASHED"
        awk '{ print $1 }' <<<"$CALLED_UNREAD"
    )"
    list_core_calls "$PROJECT_ROOT/android/app/src/main/cpp/r47zen/hal/keys.c" "$1" |
        grep -vxF -f <(printf '%s\n' "$followed") || true
}

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

unread="$(unread_core_calls "$SRC_DIR/c47")"
[ -z "$unread" ] ||
    contract_fail "hal/keys.c calls core functions nothing here follows; hash each, or name it in CALLED_UNREAD with why:" \
        "$unread"

moved="$(diff <(printf '%s\n' "$HASHED") <(hashed_names | hash_upstream_text "$SRC_DIR") | sed -n 's/^> //p' || true)"
[ -z "$moved" ] ||
    contract_fail "upstream changed what hal/keys.c follows; read each one against hal/keys.c, then update the list:" \
        "$moved"

contract_pass "sys_sleep has the upstream callers hal/keys.c assumes, it calls no core function unread, and what the copy follows has not moved."
