#!/bin/bash
# Hold what hal/keys.c assumes about who sleeps. It runs service requests only from a
# sys_sleep at rest, and it decides "at rest" from programRunStop, which is sound only
# while every upstream caller on this build is the idle main loop or a program's pause.
# A new caller could run a service inside a computation, so this fails until someone
# reads it and either extends calculatorAtRest or adds it to the list below.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

CORE_DIR="${1:-$PROJECT_ROOT/src/c47}"

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

expected='c47.c mainLoop
input.c goToSleepForMs'

# The checker must see a new caller and ignore a DMCP-only one.
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
    contract_fail "the checker did not read its fixture: $(list_sleepers "$fixture")"

# A clone with no hydrated core has nothing to read; every lane that builds has one.
[ -d "$CORE_DIR" ] || contract_pass "no hydrated core at $CORE_DIR: checked the checker only."
actual="$(list_sleepers "$CORE_DIR")"
[ "$actual" = "$expected" ] ||
    contract_fail "upstream's sys_sleep callers changed; read each one against calculatorAtRest in hal/keys.c:
$actual"

contract_pass "sys_sleep has the two upstream callers hal/keys.c assumes."
