"""Lock the JNI native method registration to the Kotlin external declarations.

`jni_registration.c` binds a fixed table of native methods into MainActivity
through RegisterNatives, and MainActivity declares the matching `external fun`s.
If the two sets drift -- a method registered with no Kotlin declaration, or an
`external fun` with no registered binding -- RegisterNatives fails and the app
crashes at startup. This parses both sides and proves the name sets are equal.
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


if __name__ == "__main__":
    unittest.main()
