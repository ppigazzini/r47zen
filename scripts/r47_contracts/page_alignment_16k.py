"""Check what a 16 KB page device needs from a shipped library and bundle.

Google Play requires apps that target Android 15 and up to support 16 KB page
sizes for updates from 2027-02-01. The packaging evidence already holds every
LOAD segment to a 16 KB alignment and the APK to `zipalign -P 16`. This adds the
two checks below, each read from what ships:

- relro: the GNU_RELRO segment of a library ends on a 16 KB boundary, or exactly
  where the LOAD segment holding it ends, so the read-only page the loader makes
  of it never takes in a writable neighbor. Read from `objdump -p` text.
- bundle-config: the bundle's BundleConfig.pb keeps
  optimizations.uncompress_native_libraries.alignment at PAGE_ALIGNMENT_16K, the
  setting Play builds each device's APK from. Read from the protobuf's wire
  format, fields 2, 2, and 2, as bundletool 1.18.3's `dump config` shows them.

Usage:
    python3 scripts/r47_contracts/page_alignment_16k.py relro <objdump-p-output>
    python3 scripts/r47_contracts/page_alignment_16k.py bundle-config <BundleConfig.pb>

test_page_alignment_16k_contract.py holds both on fixtures.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Iterator

PAGE = 16384
PAGE_ALIGNMENT_16K = 2
_OPTIMIZATIONS = 2
_UNCOMPRESS_NATIVE_LIBRARIES = 2
_ALIGNMENT = 2
_HEADER = re.compile(
    r"^\s*(?P<kind>\S+) off\s+0x[0-9a-f]+ vaddr 0x(?P<vaddr>[0-9a-f]+) .*\n"
    r"\s+filesz 0x[0-9a-f]+ memsz 0x(?P<memsz>[0-9a-f]+)",
    re.MULTILINE,
)
_WIRE_VARINT = 0
_WIRE_FIXED64 = 1
_WIRE_LENGTH = 2
_WIRE_FIXED32 = 5


def relro_problem(objdump_text: str) -> str | None:
    """Return why a library's RELRO end fails a 16 KB page, or None."""
    loads: list[tuple[int, int]] = []
    relros: list[tuple[int, int]] = []
    for match in _HEADER.finditer(objdump_text):
        start = int(match["vaddr"], 16)
        end = start + int(match["memsz"], 16)
        if match["kind"] == "LOAD":
            loads.append((start, end))
        elif match["kind"] == "RELRO":
            relros.append((start, end))
    if not loads:
        return "no LOAD segment in the program headers"
    for start, end in relros:
        holder = [
            load_end for load_start, load_end in loads if load_start <= start < load_end
        ]
        if not holder:
            return f"RELRO at {start:#x} lies in no LOAD segment"
        if end % PAGE and end != holder[0]:
            return (
                f"RELRO ends at {end:#x}, neither on a 16 KB boundary nor where its"
                f" LOAD segment ends ({holder[0]:#x})"
            )
    return None


def _varint(data: bytes, index: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[index]
        index += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:  # noqa: PLR2004 - the varint continuation bit
            return value, index


def _fields(data: bytes) -> Iterator[tuple[int, int, int | bytes]]:
    index = 0
    while index < len(data):
        key, index = _varint(data, index)
        number, wire = key >> 3, key & 7
        if wire == _WIRE_VARINT:
            value, index = _varint(data, index)
            yield number, wire, value
        elif wire == _WIRE_LENGTH:
            length, index = _varint(data, index)
            yield number, wire, data[index : index + length]
            index += length
        elif wire == _WIRE_FIXED64:
            index += 8
        elif wire == _WIRE_FIXED32:
            index += 4
        else:
            message = f"protobuf wire type {wire} is not one BundleConfig uses"
            raise ValueError(message)


def _message(data: bytes, number: int) -> bytes | None:
    for field, wire, value in _fields(data):
        if field == number and wire == _WIRE_LENGTH and isinstance(value, bytes):
            return value
    return None


def bundle_config_problem(config: bytes) -> str | None:
    """Return why a BundleConfig.pb fails 16 KB pages, or None."""
    optimizations = _message(config, _OPTIMIZATIONS)
    native = (
        _message(optimizations, _UNCOMPRESS_NATIVE_LIBRARIES)
        if optimizations is not None
        else None
    )
    if native is None:
        return "BundleConfig.pb sets no optimizations.uncompress_native_libraries"
    alignment = next(
        (
            value
            for field, wire, value in _fields(native)
            if field == _ALIGNMENT and wire == _WIRE_VARINT
        ),
        0,
    )
    if alignment != PAGE_ALIGNMENT_16K:
        return (
            f"uncompress_native_libraries.alignment is {alignment},"
            f" not PAGE_ALIGNMENT_16K ({PAGE_ALIGNMENT_16K})"
        )
    return None


def main(argv: list[str]) -> int:
    """Run one check and print the finding."""
    usage = "usage: page_alignment_16k.py (relro | bundle-config) <file>"
    expected_args = 3
    if len(argv) != expected_args or argv[1] not in {"relro", "bundle-config"}:
        sys.stderr.write(usage + "\n")
        return 2
    path = Path(argv[2])
    if argv[1] == "relro":
        problem = relro_problem(path.read_text(encoding="utf-8"))
    else:
        problem = bundle_config_problem(path.read_bytes())
    if problem is not None:
        sys.stderr.write(f"{path}: {problem}\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
