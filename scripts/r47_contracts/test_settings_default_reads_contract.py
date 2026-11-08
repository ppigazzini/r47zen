"""Hold every read of a setting to a named default.

`root_preferences.xml` gives each setting the default Settings shows while
nothing is stored, and the shell reads the same key with a default of its own.
`SettingsPreferenceSummaryTest` holds each named `DEFAULT_` constant to the XML
value; this holds every read to such a constant, so no read carries a literal of
its own that the test never sees. A read is `getBoolean`, `getInt`, `getLong`,
`getFloat`, or `getString` whose key is a setting's `KEY_` constant, or the
variable `key` of a change handler, which in this tree names only settings.

Limits: a read through another variable, or through a helper that takes the
default as a parameter, is not seen.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._repo_paths import ANDROID_RES_ROOT, KOTLIN_R47ZEN_ROOT, REPO_ROOT

_PREFERENCES: Final = ANDROID_RES_ROOT / "xml" / "root_preferences.xml"
_ELEMENT: Final = re.compile(r"<[A-Za-z][\w.]*\b(?P<attrs>[^>]*)>", re.DOTALL)
_KEY_ATTR: Final = re.compile(r'\b(?:app|android):key="(?P<key>[^"]+)"')
_DEFAULT_ATTR: Final = re.compile(r'\b(?:app|android):defaultValue="')
_KEY_CONST: Final = re.compile(r'\bconst val (?P<name>KEY_\w+)\s*=\s*"(?P<key>[^"]+)"')
_READ: Final = re.compile(
    r"\.get(?:Boolean|Int|Long|Float|String)\(\s*(?P<key>[\w.]+)\s*,\s*"
    r"(?P<default>[^,()]+?)\s*,?\s*\)",
    re.DOTALL,
)
_NAMED_DEFAULT: Final = re.compile(r"\bDEFAULT\w*\b")


def settings_with_defaults(xml: str) -> set[str]:
    """Return the keys of the preferences that declare a default."""
    keys: set[str] = set()
    for element in _ELEMENT.finditer(xml):
        attrs = element.group("attrs")
        key = _KEY_ATTR.search(attrs)
        if key is not None and _DEFAULT_ATTR.search(attrs):
            keys.add(key.group("key"))
    return keys


def key_constants(kotlin: str, settings: set[str]) -> set[str]:
    """Return the names of the `KEY_` constants whose value is a setting."""
    return {
        match.group("name")
        for match in _KEY_CONST.finditer(kotlin)
        if match.group("key") in settings
    }


def literal_reads(kotlin: str, label: str, constants: set[str]) -> list[str]:
    """Return one finding per read of a setting whose default is not named."""
    findings: list[str] = []
    for read in _READ.finditer(kotlin):
        key = read.group("key").rsplit(".", 1)[-1]
        if key not in constants and key != "key":
            continue
        default = read.group("default").strip()
        if _NAMED_DEFAULT.search(default) is None:
            line = kotlin.count("\n", 0, read.start()) + 1
            findings.append(
                f"{label}:{line}: {key} is read with the literal default {default}",
            )
    return findings


class SettingsDefaultReadsContractTest(unittest.TestCase):
    """Every read of a setting names its default."""

    def _sources(self) -> dict[str, str]:
        sources = {
            str(path.relative_to(REPO_ROOT)): path.read_text(encoding="utf-8")
            for path in sorted(KOTLIN_R47ZEN_ROOT.rglob("*.kt"))
        }
        if not sources:
            message = f"found no Kotlin under {KOTLIN_R47ZEN_ROOT}"
            raise AssertionError(message)
        return sources

    def test_every_read_of_a_setting_names_its_default(self) -> None:
        """No read of a setting's key passes a literal default."""
        settings = settings_with_defaults(_PREFERENCES.read_text(encoding="utf-8"))
        sources = self._sources()
        constants = set().union(
            *(key_constants(text, settings) for text in sources.values()),
        )
        missing = settings - {
            match.group("key")
            for text in sources.values()
            for match in _KEY_CONST.finditer(text)
        }
        if missing:
            message = f"settings with a default and no KEY_ constant: {sorted(missing)}"
            raise AssertionError(message)
        findings = [
            finding
            for label, text in sources.items()
            for finding in literal_reads(text, label, constants)
        ]
        if findings:
            raise AssertionError("\n".join(findings))

    def test_scanner_finds_a_seeded_literal_in_either_form(self) -> None:
        """The scanner flags a literal default on one line or across lines."""
        seeded = (
            "preferences.getBoolean(KEY_FULLSCREEN_MODE, true)\n"
            "prefs.getInt(\n    Owner.KEY_BEEPER_VOLUME,\n    20,\n)\n"
            "preferences.getBoolean(key, false)\n"
            "preferences.getBoolean(KEY_FULLSCREEN_MODE, DEFAULT_FULLSCREEN_MODE)\n"
            "store.getInt(KEY_SLOT_COUNT, 0)\n"
        )
        constants = {"KEY_FULLSCREEN_MODE", "KEY_BEEPER_VOLUME"}
        findings = literal_reads(seeded, "seed", constants)
        expected = [
            "seed:1: KEY_FULLSCREEN_MODE is read with the literal default true",
            "seed:2: KEY_BEEPER_VOLUME is read with the literal default 20",
            "seed:6: key is read with the literal default false",
        ]
        if findings != expected:
            message = f"the scanner found {findings}, want {expected}"
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
