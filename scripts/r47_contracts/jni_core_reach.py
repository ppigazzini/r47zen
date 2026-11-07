"""Fail when a JNI entry can reach core code off the calculator thread.

The app's threading rule is that only the calculator thread runs core code, and JNI
entries run on whatever thread called them. A harness only proves the paths it ran, so
this walks every path the linker can see: each glue function's relocations name the
functions and data it refers to, a function pointer taken or a global read included.
The glue is compiled with -ffunction-sections and -fdata-sections so that each
relocation section belongs to one definition.

Usage: jni_core_reach.py <glue object dir> <core object dir> [allowed symbol ...]
Each argument after the two directories is a core symbol an entry may refer to.
build_calculator_thread_harness.sh runs it, since it needs the staged core's objects.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

# What the build runs on the calculator thread itself.
CALCULATOR_THREAD_ENTRY = (
    "Java_io_github_ppigazzini_r47zen_CalculatorEngine_runCalculatorNative"
)
_SECTION_PREFIX = re.compile(
    r"^\.(?:text|data\.rel\.ro\.local|data\.rel\.ro|data\.rel\.local|data\.rel"
    r"|data|bss|rodata)\.",
)
_RELOCATION_SECTION = re.compile(r"^Relocation section '\.rela(\S+)'")
_RELOCATION_OFFSET = re.compile(r"[0-9a-f]+")
_NM_FIELDS = 3
_RELOCATION_SYMBOL_FIELD = 4
_DIRECTORY_ARGUMENTS = 2


def run_tool(tool: str, path: Path, *flags: str) -> str:
    """Return a binutils tool's output for one object."""
    executable = shutil.which(tool)
    if executable is None:
        message = f"{tool} is not on PATH"
        raise SystemExit(message)
    # Fixed arguments; the path is an object this build just compiled.
    result = subprocess.run(  # noqa: S603
        [executable, *flags, str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def definition_of(section_or_symbol: str) -> str:
    """Name the definition a section or a symbol stands for."""
    return _SECTION_PREFIX.sub("", section_or_symbol)


def defined_symbols(path: Path) -> list[tuple[str, str]]:
    """Return each symbol the object defines, with nm's type letter."""
    output = run_tool("nm", path, "--defined-only")
    rows = (line.split() for line in output.splitlines())
    return [(row[2], row[1]) for row in rows if len(row) == _NM_FIELDS]


def references(path: Path) -> dict[str, set[str]]:
    """Map each definition in the object to what its relocations name."""
    edges: dict[str, set[str]] = {}
    source = None
    for line in run_tool("readelf", path, "-rW").splitlines():
        header = _RELOCATION_SECTION.match(line)
        if header:
            section = header.group(1)
            named = _SECTION_PREFIX.match(section) is not None
            source = definition_of(section) if named else None
            continue
        fields = line.split()
        if (
            source is None
            or len(fields) <= _RELOCATION_SYMBOL_FIELD
            or not _RELOCATION_OFFSET.fullmatch(fields[0])
        ):
            continue
        target = definition_of(fields[_RELOCATION_SYMBOL_FIELD])
        edges.setdefault(source, set()).add(target)
    return edges


def core_symbols(core_dir: Path) -> set[str]:
    """Return every global the upstream objects define."""
    symbols: set[str] = set()
    for path in sorted(core_dir.glob("*.o")):
        symbols.update(name for name, kind in defined_symbols(path) if kind.isupper())
    return symbols


def violations(glue_dir: Path, core: set[str], allowed: set[str]) -> list[str]:
    """Return each route from a JNI entry to a core symbol."""
    objects = sorted(glue_dir.glob("*.o"))
    graph = {path: references(path) for path in objects}
    symbols = {path: defined_symbols(path) for path in objects}
    local = {path: {name for name, _ in symbols[path]} for path in objects}
    owner = {
        name: path for path in objects for name, kind in symbols[path] if kind.isupper()
    }

    found = []
    for path in objects:
        for entry in sorted(local[path]):
            if entry == CALCULATOR_THREAD_ENTRY or not entry.startswith(
                ("Java_", "JNI_OnLoad"),
            ):
                continue
            seen = {(path, entry)}
            walk: list[tuple[Path, str, tuple[str, ...]]] = [(path, entry, (entry,))]
            while walk:
                unit, name, route = walk.pop()
                for target in sorted(graph[unit].get(name, ())):
                    # A table of natives takes each entry's address and calls none.
                    if target.startswith("Java_"):
                        continue
                    home = unit if target in local[unit] else owner.get(target)
                    if home is None:
                        if target in core and target not in allowed:
                            found.append(" -> ".join((*route, target)))
                    elif (home, target) not in seen:
                        seen.add((home, target))
                        walk.append((home, target, (*route, target)))
    return sorted(found)


def main() -> int:
    """Report every route from a JNI entry to core code."""
    if len(sys.argv) <= _DIRECTORY_ARGUMENTS:
        sys.stderr.write(
            f"usage: {sys.argv[0]} <glue object dir> <core object dir> [allowed ...]\n",
        )
        return 2
    glue_dir, core_dir = Path(sys.argv[1]), Path(sys.argv[2])
    core = core_symbols(core_dir)
    if not core:
        sys.stderr.write(f"no core symbols in {core_dir}: nothing to check against\n")
        return 2
    found = violations(glue_dir, core, set(sys.argv[3:]))
    for route in found:
        sys.stdout.write(f"{route}\n")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
