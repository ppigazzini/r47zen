"""Lock the JNI native method registration to the Kotlin external declarations.

`jni_registration.c` binds a fixed table of native methods into CalculatorEngine
through RegisterNatives, and CalculatorEngine declares the matching `external fun`s.
If the two sets drift -- a method registered with no Kotlin declaration, or an
`external fun` with no registered binding -- RegisterNatives fails and the app
crashes at startup. This parses both sides and proves the name sets are equal.

The calls the other way, native into CalculatorEngine, are a third agreement: every
`bindMethod` the glue performs must name a `@Keep` CalculatorEngine method with the
same JNI signature. `r47_upcalls_bind` fails the library load when a single lookup
fails, so one renamed callback stops the app at its start.

`r47_force_refresh`, the forced full redraw, is referenced by runtime init, once,
and by the instrumentation bridge, beside its definition and prototype; any other
reference in the glue, in a source or a header, fails here. That is all this holds:
the glue's other redraws, such as the one after any service request, are not its.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._repo_paths import ANDROID_CPP_ROOT, KOTLIN_R47ZEN_ROOT

_JNI_REGISTRATION: Final = ANDROID_CPP_ROOT / "jni_registration.c"
_CALCULATOR_ENGINE: Final = KOTLIN_R47ZEN_ROOT / "CalculatorEngine.kt"
_NATIVE_METHOD: Final = re.compile(r'\{"(\w+)",\s*"\(')
_NATIVE_BINDING: Final = re.compile(
    r'\{"(?P<name>\w+)",\s*"[^"]+",\s*\(void \*\)\s*'
    r"Java_io_github_ppigazzini_r47zen_CalculatorEngine_(?P<impl>\w+)\}",
)
_KOTLIN_EXTERNAL_FUN: Final = re.compile(r"external fun (\w+)")
_NATIVE_METHOD_LOOKUP: Final = re.compile(
    r'bindMethod\(\s*env,\s*engineClass,\s*"(?P<name>\w+)",\s*"(?P<sig>[^"]+)"\)',
)
_KOTLIN_KEEP_FUN: Final = re.compile(
    r"@Keep\s+fun\s+(?P<name>\w+)\((?P<params>[^)]*)\)(?:\s*:\s*(?P<ret>\w+))?",
)
_JNI_TYPE: Final = {
    "Boolean": "Z",
    "Int": "I",
    "Long": "J",
    "Float": "F",
    "Double": "D",
    "String": "Ljava/lang/String;",
    "Unit": "V",
}
_FORCE_REFRESH: Final = re.compile(r"\br47_force_refresh\b")
_FORCE_REFRESH_DECLARATION: Final = re.compile(
    r"\bvoid\s+r47_force_refresh\s*\(\s*void\s*\)",
)
# Path under the glue root, and how many references it may hold; None is any number.
_FORCE_REFRESH_ALLOWED: Final = {"jni_lifecycle.c": 1, "jni_program_load_test.c": None}


def _native_registered_methods() -> set[str]:
    """Return the method names bound by RegisterNatives in jni_registration.c."""
    text = _JNI_REGISTRATION.read_text(encoding="utf-8")
    return set(_NATIVE_METHOD.findall(text))


def _kotlin_external_functions() -> set[str]:
    """Return the `external fun` names declared in CalculatorEngine."""
    text = _CALCULATOR_ENGINE.read_text(encoding="utf-8")
    return set(_KOTLIN_EXTERNAL_FUN.findall(text))


def _native_bindings() -> list[tuple[str, str]]:
    """Return (registered name, implementation suffix) pairs from the table."""
    text = _JNI_REGISTRATION.read_text(encoding="utf-8")
    return [
        (match.group("name"), match.group("impl"))
        for match in _NATIVE_BINDING.finditer(text)
    ]


def _native_method_lookups() -> dict[str, str]:
    """Return {method name: JNI signature} for every bindMethod in the glue."""
    lookups: dict[str, str] = {}
    for source in sorted(ANDROID_CPP_ROOT.glob("*.c")):
        text = source.read_text(encoding="utf-8")
        for match in _NATIVE_METHOD_LOOKUP.finditer(text):
            lookups[match.group("name")] = match.group("sig")
    return lookups


def kotlin_keep_signatures(text: str) -> dict[str, str]:
    """Return {method name: JNI signature} for the `@Keep fun`s in `text`.

    A parameter or return type outside the JNI mapping yields `?`, so it can
    never equal a native signature by accident.
    """
    signatures: dict[str, str] = {}
    for match in _KOTLIN_KEEP_FUN.finditer(text):
        params = [p for p in match.group("params").split(",") if p.strip()]
        types = [p.split(":", 1)[1].strip() if ":" in p else "?" for p in params]
        args = "".join(_JNI_TYPE.get(kind, "?") for kind in types)
        ret = _JNI_TYPE.get(match.group("ret") or "Unit", "?")
        signatures[match.group("name")] = f"({args}){ret}"
    return signatures


def callback_mismatches(lookups: dict[str, str], kotlin: dict[str, str]) -> list[str]:
    """Return one line per native lookup with no matching Kotlin method."""
    return [
        f"{name}{signature}: CalculatorEngine has "
        + (f"{name}{kotlin[name]}" if name in kotlin else "no @Keep fun of that name")
        for name, signature in sorted(lookups.items())
        if kotlin.get(name) != signature
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
                "JNI method drift between jni_registration.c and CalculatorEngine: "
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


class NativeCallbackContractTest(unittest.TestCase):
    """Verify every method native looks up exists in CalculatorEngine as declared."""

    def test_native_callbacks_match_keep_methods(self) -> None:
        """Each GetMethodID must name a @Keep fun with the same JNI signature."""
        lookups = _native_method_lookups()
        if not lookups:
            message = f"no GetMethodID parsed under {ANDROID_CPP_ROOT}"
            raise AssertionError(message)
        mismatches = callback_mismatches(
            lookups,
            kotlin_keep_signatures(_CALCULATOR_ENGINE.read_text(encoding="utf-8")),
        )
        if mismatches:
            raise AssertionError("\n".join(mismatches))

    def test_a_renamed_or_retyped_callback_is_caught(self) -> None:
        """The checker flags a missing method and a changed parameter type."""
        lookups = {"requestFile": "(ZLjava/lang/String;I)V", "quitApp": "()V"}
        seeded = (
            "@Keep\n    fun requestFile(isSave: Boolean, defaultName: String, "
            "fileType: Long) {}\n@Keep fun quitApplication() {}"
        )
        mismatches = callback_mismatches(lookups, kotlin_keep_signatures(seeded))
        if len(mismatches) != len(lookups):
            message = f"the checker missed a seeded drift: {mismatches}"
            raise AssertionError(message)


def force_refresh_references(sources: dict[str, str]) -> dict[str, int]:
    """Count references to `r47_force_refresh`, less its definition and prototype."""
    counts = {
        path: len(_FORCE_REFRESH.findall(text))
        - len(_FORCE_REFRESH_DECLARATION.findall(text))
        for path, text in sources.items()
    }
    return {path: count for path, count in counts.items() if count > 0}


def misplaced_redraws(references: dict[str, int]) -> list[str]:
    """Return the files, but init and the bridge, that reference the forced redraw."""
    misplaced = [path for path in references if path not in _FORCE_REFRESH_ALLOWED]
    init = references.get("jni_lifecycle.c", 0)
    if init != _FORCE_REFRESH_ALLOWED["jni_lifecycle.c"]:
        misplaced.append(f"jni_lifecycle.c ({init} references)")
    return misplaced


class RedrawEntryContractTest(unittest.TestCase):
    """Only runtime init and the instrumentation bridge use the forced full redraw."""

    def test_any_other_reference_is_caught(self) -> None:
        """Seeded: a call, a pointer, a macro, and a header each fail."""
        definition = (
            "void r47_force_refresh(void) {}\nvoid init(void) { r47_force_refresh(); }"
        )
        seeded = {
            "jni_lifecycle.c": definition,
            "jni_input.c": "void b(void) { r47_force_refresh (); }",
            "jni_display.c": "static void (*redraw)(void) = r47_force_refresh;",
            "hal/lcd.h": "#define REDRAW() r47_force_refresh()",
            "keypad_fixture_bridge.h": "void r47_force_refresh(void);",
        }
        found = sorted(misplaced_redraws(force_refresh_references(seeded)))
        if found != ["hal/lcd.h", "jni_display.c", "jni_input.c"]:
            message = f"the redraw check found {found} among seeded references"
            raise AssertionError(message)
        doubled = {"jni_lifecycle.c": definition + " r47_force_refresh();"}
        if not misplaced_redraws(force_refresh_references(doubled)):
            message = "the redraw check missed a second reference in init"
            raise AssertionError(message)

    def test_only_init_and_the_bridge_use_the_forced_redraw(self) -> None:
        """No glue source or header but init and the bridge references it."""
        sources = {
            path.relative_to(ANDROID_CPP_ROOT).as_posix(): path.read_text(
                encoding="utf-8",
            )
            for pattern in ("*.c", "*.h")
            for path in ANDROID_CPP_ROOT.rglob(pattern)
        }
        misplaced = misplaced_redraws(force_refresh_references(sources))
        if misplaced:
            message = f"r47_force_refresh referenced from {misplaced}"
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
