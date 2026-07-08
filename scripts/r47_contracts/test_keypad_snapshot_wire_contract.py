"""Lock the native keypad-snapshot wire format to the Kotlin decoder and fixtures.

The native bridge fills a keypad-snapshot meta lane array and a per-key label
grid whose dimensions the Kotlin `KeypadSnapshot` decoder and the exported test
fixtures must agree on, byte for byte, across the JNI boundary. A drift in the
key count, labels-per-key, scene-contract version, label-slot order, or total
meta length silently misdecodes every key's labels and metadata.

This is a genuine three-source oracle: it parses the `R47_KEYPAD_*` enum in
`keypad_fixture_bridge.h`, the `KeypadSnapshot` `const val`s, and the exported
`manifest.json`, and proves they still agree. Nothing else pinned the native
producer to the Kotlin consumer.
"""

from __future__ import annotations

import json
import re
import unittest
from typing import Final

from r47_contracts._repo_paths import (
    ANDROID_CPP_ROOT,
    KEYPAD_FIXTURES_ROOT,
    KOTLIN_R47ZEN_ROOT,
)

_NATIVE_HEADER: Final = ANDROID_CPP_ROOT / "keypad_fixture_bridge.h"
_KOTLIN_SNAPSHOT: Final = KOTLIN_R47ZEN_ROOT / "KeypadSnapshot.kt"
_FIXTURE_MANIFEST: Final = KEYPAD_FIXTURES_ROOT / "manifest.json"

_NATIVE_PREFIX: Final = "R47_KEYPAD_"
_ENUM_ENTRY: Final = re.compile(r"R47_KEYPAD_(?P<name>\w+)\s*=\s*(?P<expr>[^,}]+)")
_KOTLIN_CONST: Final = re.compile(r"const val (?P<name>\w+)\s*=\s*(?P<expr>.+)")
_LABEL_SLOT_NAMES: Final = (
    "LABEL_PRIMARY",
    "LABEL_F",
    "LABEL_G",
    "LABEL_LETTER",
    "LABEL_AUX",
)


def _native_constants() -> dict[str, int]:
    """Resolve every R47_KEYPAD_* enumerator by ordered additive evaluation."""
    text = _NATIVE_HEADER.read_text(encoding="utf-8")
    start = text.find("enum {")
    end = text.find("};", start)
    if start < 0 or end < 0:
        message = f"keypad enum block not found in {_NATIVE_HEADER}"
        raise AssertionError(message)
    values: dict[str, int] = {}
    for match in _ENUM_ENTRY.finditer(text[start:end]):
        total = 0
        for term in match.group("expr").split("+"):
            token = term.strip()
            if token.isdigit():
                total += int(token)
            else:
                total += values[token.removeprefix(_NATIVE_PREFIX)]
        values[match.group("name")] = total
    return values


def _kotlin_int(name: str) -> int:
    """Read a direct integer `const val` from the Kotlin snapshot source."""
    text = _KOTLIN_SNAPSHOT.read_text(encoding="utf-8")
    match = re.search(rf"const val {name}\s*=\s*(-?\d+)", text)
    if match is None:
        message = f"const val {name} not found in {_KOTLIN_SNAPSHOT}"
        raise AssertionError(message)
    return int(match.group(1))


def _manifest() -> dict[str, object]:
    """Load the exported keypad fixture manifest."""
    return json.loads(_FIXTURE_MANIFEST.read_text(encoding="utf-8"))


def _kotlin_offset_constants() -> dict[str, int]:
    """Resolve the additively-defined Kotlin const vals in snapshot file order.

    Only integer literals and sums of already-resolved names are evaluated, so
    bit-shift expressions (the SCENE_FLAG_* flags) are skipped rather than
    parsed. This yields the KEY_COUNT-derived META_* lane offsets without the
    `shl` handling the shared const parser lacks.
    """
    values: dict[str, int] = {}
    for line in _KOTLIN_SNAPSHOT.read_text(encoding="utf-8").splitlines():
        match = _KOTLIN_CONST.search(line)
        if match is None:
            continue
        total = 0
        resolved = True
        for term in match.group("expr").split("+"):
            token = term.strip()
            if token.isdigit():
                total += int(token)
            elif token in values:
                total += values[token]
            else:
                resolved = False
                break
        if resolved:
            values[match.group("name")] = total
    return values


class KeypadSnapshotWireContractTest(unittest.TestCase):
    """Verify the native producer, Kotlin decoder, and fixtures share one format."""

    def test_key_count_agrees_across_sources(self) -> None:
        """Native, Kotlin, and fixture key counts must be identical."""
        native = _native_constants()["KEY_COUNT"]
        kotlin = _kotlin_int("KEY_COUNT")
        manifest = _manifest()["keyCount"]
        if not native == kotlin == manifest:
            message = (
                "keypad KEY_COUNT drift: native="
                f"{native} kotlin={kotlin} manifest={manifest}"
            )
            raise AssertionError(message)

    def test_labels_per_key_agrees_across_sources(self) -> None:
        """Native, Kotlin, and fixture labels-per-key must be identical."""
        native = _native_constants()["LABELS_PER_KEY"]
        kotlin = _kotlin_int("LABELS_PER_KEY")
        manifest = _manifest()["labelsPerKey"]
        if not native == kotlin == manifest:
            message = (
                "keypad LABELS_PER_KEY drift: native="
                f"{native} kotlin={kotlin} manifest={manifest}"
            )
            raise AssertionError(message)

    def test_scene_contract_version_agrees_across_sources(self) -> None:
        """Native, Kotlin, and fixture scene-contract versions must be identical."""
        native = _native_constants()["SCENE_CONTRACT_VERSION"]
        kotlin = _kotlin_int("SCENE_CONTRACT_VERSION")
        manifest = _manifest()["sceneContractVersion"]
        if not native == kotlin == manifest:
            message = (
                "keypad SCENE_CONTRACT_VERSION drift: native="
                f"{native} kotlin={kotlin} manifest={manifest}"
            )
            raise AssertionError(message)

    def test_label_slot_indices_match_and_are_sequential(self) -> None:
        """The five label slots must share index 0..4 in native and Kotlin."""
        native = _native_constants()
        native_slots = [native[name] for name in _LABEL_SLOT_NAMES]
        kotlin_slots = [_kotlin_int(name) for name in _LABEL_SLOT_NAMES]
        expected = list(range(len(_LABEL_SLOT_NAMES)))
        if native_slots != expected or kotlin_slots != expected:
            message = (
                "keypad label-slot indices drift: native="
                f"{native_slots} kotlin={kotlin_slots} expected={expected}"
            )
            raise AssertionError(message)

    def test_meta_length_matches_fixture(self) -> None:
        """The native total meta lane length must equal the exported manifest."""
        native = _native_constants()["META_LENGTH"]
        manifest = _manifest()["metaLength"]
        if native != manifest:
            message = (
                f"keypad META_LENGTH drift: native={native} manifest={manifest}"
            )
            raise AssertionError(message)

    def test_meta_lane_offsets_match_native(self) -> None:
        """Every META_* lane offset must share its index in native and Kotlin."""
        native = _native_constants()
        kotlin = _kotlin_offset_constants()
        meta_names = sorted(name for name in native if name.startswith("META_"))
        if not meta_names:
            message = "no META_* offsets parsed from the native enum"
            raise AssertionError(message)
        mismatches = {
            name: (native[name], kotlin.get(name))
            for name in meta_names
            if native[name] != kotlin.get(name)
        }
        if mismatches:
            message = f"keypad meta lane offset drift (native, kotlin): {mismatches}"
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
