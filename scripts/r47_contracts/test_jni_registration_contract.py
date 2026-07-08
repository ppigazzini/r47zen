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
_KOTLIN_EXTERNAL_FUN: Final = re.compile(r"external fun (\w+)")


def _native_registered_methods() -> set[str]:
    """Return the method names bound by RegisterNatives in jni_registration.c."""
    text = _JNI_REGISTRATION.read_text(encoding="utf-8")
    return set(_NATIVE_METHOD.findall(text))


def _kotlin_external_functions() -> set[str]:
    """Return the `external fun` names declared in MainActivity."""
    text = _MAIN_ACTIVITY.read_text(encoding="utf-8")
    return set(_KOTLIN_EXTERNAL_FUN.findall(text))


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


if __name__ == "__main__":
    unittest.main()
