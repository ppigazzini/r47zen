"""Hold the two 16 KB page checks the packaging evidence runs on what ships.

page_alignment_16k.py reads a library's GNU_RELRO segment from `objdump -p` text
and a bundle's BundleConfig.pb from its protobuf wire format. The good
fixtures are the dry run's own values: the 0.3.0 dry-run library, whose RELRO ends
at 0x336000 where its LOAD ends, and a BundleConfig whose uncompressed native
libraries are PAGE_ALIGNMENT_16K, as bundletool 1.18.3's `dump config` shows the
real one. Each check must also fail its seeded case, and
collect_packaging_evidence.sh must run both.
"""

from __future__ import annotations

import unittest
from typing import Final

from r47_contracts._repo_paths import REPO_ROOT
from r47_contracts.page_alignment_16k import bundle_config_problem, relro_problem

_EVIDENCE: Final = REPO_ROOT / "scripts" / "android" / "collect_packaging_evidence.sh"
_LOAD_RW: Final = """\
    LOAD off    0x0000000000000000 vaddr 0x0000000000000000 paddr 0x0 align 2**14
         filesz 0x00000000002f0948 memsz 0x00000000002f0948 flags r-x
    LOAD off    0x00000000002f0948 vaddr 0x00000000002f4948 paddr 0x0 align 2**14
         filesz 0x0000000000040798 memsz {load_memsz} flags rw-
"""
_RELRO: Final = """\
   RELRO off    0x00000000002f0948 vaddr 0x00000000002f4948 paddr 0x0 align 2**0
         filesz 0x0000000000040798 memsz {relro_memsz} flags r--
"""


def _headers(load_memsz: str, relro_memsz: str) -> str:
    return _LOAD_RW.format(load_memsz=load_memsz) + _RELRO.format(
        relro_memsz=relro_memsz,
    )


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _length_field(number: int, payload: bytes) -> bytes:
    return _varint(number << 3 | 2) + _varint(len(payload)) + payload


def _bundle_config(alignment: int | None) -> bytes:
    native = _varint(1 << 3) + _varint(1)
    if alignment is not None:
        native += _varint(2 << 3) + _varint(alignment)
    bundletool = _length_field(1, b"\x0a\x061.18.3")
    optimizations = _length_field(2, _length_field(1, b"") + _length_field(2, native))
    return bundletool + optimizations


class PageAlignment16kContractTest(unittest.TestCase):
    """The RELRO and bundle-config checks pass what ships and fail their seeds."""

    def test_relro_ending_with_its_load_passes(self) -> None:
        """The dry-run library: RELRO ends where its LOAD ends, 0x336000."""
        if relro_problem(_headers("0x00000000000416b8", "0x00000000000416b8")):
            raise AssertionError

    def test_relro_ending_on_a_16k_boundary_passes(self) -> None:
        """A RELRO that ends on a 16 KB boundary, inside a longer LOAD, passes."""
        if relro_problem(_headers("0x0000000000050000", "0x00000000000436b8")):
            raise AssertionError

    def test_relro_ending_mid_page_fails(self) -> None:
        """A RELRO that ends mid-page, before its LOAD ends, fails."""
        if relro_problem(_headers("0x0000000000050000", "0x00000000000416b8")) is None:
            raise AssertionError

    def test_relro_outside_every_load_fails(self) -> None:
        """A RELRO no LOAD holds fails rather than passing unread."""
        text = _LOAD_RW.format(load_memsz="0x10").replace("0x00000000002f4948", "0x1")
        if relro_problem(text + _RELRO.format(relro_memsz="0x10")) is None:
            raise AssertionError

    def test_bundle_config_at_16k_passes(self) -> None:
        """PAGE_ALIGNMENT_16K (2), as bundletool dumps the dry-run bundle."""
        if bundle_config_problem(_bundle_config(2)):
            raise AssertionError

    def test_bundle_config_at_4k_or_unset_fails(self) -> None:
        """PAGE_ALIGNMENT_4K (1) fails, and so does an unset alignment."""
        for alignment in (1, None):
            if bundle_config_problem(_bundle_config(alignment)) is None:
                raise AssertionError(alignment)

    def test_bundle_config_without_native_settings_fails(self) -> None:
        """A BundleConfig with no optimizations at all fails."""
        if bundle_config_problem(_length_field(1, b"\x0a\x061.18.3")) is None:
            raise AssertionError

    def test_evidence_runs_both_checks(self) -> None:
        """collect_packaging_evidence.sh runs the RELRO and bundle-config checks."""
        text = _EVIDENCE.read_text(encoding="utf-8")
        for check in (
            "r47_contracts/page_alignment_16k.py",
            '"$page_alignment_check" relro',
            '"$page_alignment_check" bundle-config',
        ):
            if check not in text:
                raise AssertionError(check)


if __name__ == "__main__":
    unittest.main()
