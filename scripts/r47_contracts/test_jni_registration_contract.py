"""Lock the JNI native method registration to the Kotlin external declarations.

`jni_registration.c` binds a fixed table of native methods into MainActivity
through RegisterNatives, and MainActivity declares the matching `external fun`s.
If the two sets drift -- a method registered with no Kotlin declaration, or an
`external fun` with no registered binding -- RegisterNatives fails and the app
crashes at startup. This parses both sides and proves the name sets are equal.

The shell codes Kotlin passes to sendSimMenuNative and sendSimFuncNative are the
other JNI-level agreement: `jni_bridge.h` defines them, `NativeShellCodes.kt`
mirrors them, and `jni_input.c` resolves each to an upstream item symbol, never
a number, since items.h renumbers between upstream revisions.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._repo_paths import ANDROID_CPP_ROOT, KOTLIN_R47ZEN_ROOT

_JNI_REGISTRATION: Final = ANDROID_CPP_ROOT / "jni_registration.c"
_MAIN_ACTIVITY: Final = KOTLIN_R47ZEN_ROOT / "MainActivity.kt"
_NATIVE_METHOD: Final = re.compile(r'\{"(\w+)",\s*"\(')
_NATIVE_BINDING: Final = re.compile(
    r'\{"(?P<name>\w+)",\s*"[^"]+",\s*\(void \*\)'
    r"Java_com_example_r47_MainActivity_(?P<impl>\w+)\}",
)
_KOTLIN_EXTERNAL_FUN: Final = re.compile(r"external fun (\w+)")
_JNI_BRIDGE: Final = ANDROID_CPP_ROOT / "jni_bridge.h"
_JNI_INPUT: Final = ANDROID_CPP_ROOT / "jni_input.c"
_NATIVE_SHELL_CODES: Final = KOTLIN_R47ZEN_ROOT / "NativeShellCodes.kt"
_C_SHELL_CODE: Final = re.compile(r"^#define R47_SHELL_(\w+) (\d+)$", re.MULTILINE)
_KOTLIN_SHELL_CODE: Final = re.compile(r"const val (\w+) = (\d+)")
_C_SHELL_CASE: Final = re.compile(
    r"case R47_SHELL_(\w+):\s*return (-?)([A-Za-z_]\w*|\d+);",
)


def _native_registered_methods() -> set[str]:
    """Return the method names bound by RegisterNatives in jni_registration.c."""
    text = _JNI_REGISTRATION.read_text(encoding="utf-8")
    return set(_NATIVE_METHOD.findall(text))


def _kotlin_external_functions() -> set[str]:
    """Return the `external fun` names declared in MainActivity."""
    text = _MAIN_ACTIVITY.read_text(encoding="utf-8")
    return set(_KOTLIN_EXTERNAL_FUN.findall(text))


def _native_bindings() -> list[tuple[str, str]]:
    """Return (registered name, implementation suffix) pairs from the table."""
    text = _JNI_REGISTRATION.read_text(encoding="utf-8")
    return [
        (match.group("name"), match.group("impl"))
        for match in _NATIVE_BINDING.finditer(text)
    ]


def _c_shell_codes() -> dict[str, int]:
    """Return the R47_SHELL_* codes jni_bridge.h defines, without the prefix."""
    text = _JNI_BRIDGE.read_text(encoding="utf-8")
    return {name: int(value) for name, value in _C_SHELL_CODE.findall(text)}


def _kotlin_shell_codes() -> dict[str, int]:
    """Return the constants NativeShellCodes.kt declares."""
    text = _NATIVE_SHELL_CODES.read_text(encoding="utf-8")
    return {name: int(value) for name, value in _KOTLIN_SHELL_CODE.findall(text)}


def _c_shell_resolutions() -> dict[str, str]:
    """Return each shell code's resolved upstream target from jni_input.c."""
    text = _JNI_INPUT.read_text(encoding="utf-8")
    return {name: sign + target for name, sign, target in _C_SHELL_CASE.findall(text)}


class JniRegistrationContractTest(unittest.TestCase):
    """Verify the native registration table and Kotlin externals stay in sync."""

    def test_registration_table_parses(self) -> None:
        """The parser must find a non-empty table, guarding against regex rot."""
        if not _native_registered_methods():
            message = f"no native methods parsed from {_JNI_REGISTRATION}"
            raise AssertionError(message)

    def test_native_and_kotlin_method_sets_match(self) -> None:
        """Every registered native method must have one Kotlin external fun."""
        native = _native_registered_methods()
        kotlin = _kotlin_external_functions()
        if native != kotlin:
            message = (
                "JNI method drift between jni_registration.c and MainActivity: "
                f"registered-only={sorted(native - kotlin)} "
                f"external-only={sorted(kotlin - native)}"
            )
            raise AssertionError(message)

    def test_registered_names_bind_matching_implementations(self) -> None:
        """Each entry must bind its name to the Java_..._<name> implementation."""
        bindings = _native_bindings()
        if len(bindings) != len(_native_registered_methods()):
            message = (
                "JNI binding parse mismatch: parsed "
                f"{len(bindings)} bindings for "
                f"{len(_native_registered_methods())} registered methods"
            )
            raise AssertionError(message)
        mismatched = [(name, impl) for name, impl in bindings if name != impl]
        if mismatched:
            message = f"JNI methods bound to a mismatched implementation: {mismatched}"
            raise AssertionError(message)


class NativeShellCodeContractTest(unittest.TestCase):
    """Verify the shell codes agree across C and Kotlin and resolve to symbols."""

    def test_shell_codes_parse(self) -> None:
        """The parsers must find codes on both sides, guarding against regex rot."""
        if not _c_shell_codes() or not _kotlin_shell_codes():
            message = (
                f"no shell codes parsed from {_JNI_BRIDGE} or {_NATIVE_SHELL_CODES}"
            )
            raise AssertionError(message)

    def test_c_and_kotlin_shell_codes_match(self) -> None:
        """Every R47_SHELL_* code must have the same name and value in Kotlin."""
        native = _c_shell_codes()
        kotlin = _kotlin_shell_codes()
        if native != kotlin:
            message = (
                f"shell code drift: jni_bridge.h={native} NativeShellCodes.kt={kotlin}"
            )
            raise AssertionError(message)

    def test_each_shell_code_resolves_to_an_upstream_symbol(self) -> None:
        """jni_input.c must resolve every code, and to a symbol, not a number."""
        resolutions = _c_shell_resolutions()
        missing = sorted(set(_c_shell_codes()) - set(resolutions))
        if missing:
            message = f"jni_input.c resolves no case for shell codes {missing}"
            raise AssertionError(message)
        numeric = {
            name: target
            for name, target in resolutions.items()
            if target.lstrip("-").isdigit()
        }
        if numeric:
            message = (
                "shell codes resolved to raw item numbers, not items.h symbols: "
                f"{numeric}"
            )
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
