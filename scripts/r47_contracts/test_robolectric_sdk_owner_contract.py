"""Hold the SDK the Robolectric tests run at to its one owner, the targetSdk.

No JVM test names an sdk in `@Config` and no `robolectric.properties` sets one,
so Robolectric runs every class at the targetSdkVersion of the merged manifest,
which `R47_DEFAULT_ANDROID_TARGET_SDK` in `android/r47-defaults.properties`
sets. A class that pins its own SDK tests framework behavior the app does not
run on, and the next class copied from it inherits the pin. `RobolectricSdkTest`
proves at run time that the framework under test is the target; this proves no
source overrides it, after proving the scanner on seeded text.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._repo_paths import ANDROID_TEST_RESOURCES_ROOT, REPO_ROOT

_JVM_TEST_ROOT: Final = ANDROID_TEST_RESOURCES_ROOT.parent
_CONFIG_START: Final = re.compile(r"@Config\(")
_SDK_ARGUMENT: Final = re.compile(r"\b(?:sdk|minSdk|maxSdk)\s*=")
_SDK_PROPERTY: Final = re.compile(r"^\s*(?:sdk|minSdk|maxSdk)\s*[=:]", re.MULTILINE)


def _config_arguments(text: str) -> list[str]:
    """Return the argument text of every `@Config(...)`, across lines."""
    arguments: list[str] = []
    for start in _CONFIG_START.finditer(text):
        depth = 1
        index = start.end()
        while index < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[index], 0)
            index += 1
        arguments.append(text[start.end() : index - 1])
    return arguments


def sdk_pins(text: str) -> list[str]:
    """Return every `@Config` argument list in Kotlin text that sets an SDK."""
    return [args for args in _config_arguments(text) if _SDK_ARGUMENT.search(args)]


class RobolectricSdkScannerSelfTest(unittest.TestCase):
    """Prove the scanner on seeded text before trusting its verdict on the tree."""

    def test_finds_a_pin_alone_with_qualifiers_and_across_lines(self) -> None:
        """Each shape a pin can take is found."""
        seeded = [
            "@Config(sdk = [34])\nclass A",
            '@Config(sdk = [34], qualifiers = "xxhdpi")\nclass B',
            '@Config(\n    qualifiers = "notnight",\n    minSdk = 35,\n)\nclass C',
        ]
        missed = [text for text in seeded if not sdk_pins(text)]
        if missed:
            message = f"the scanner missed a seeded SDK pin: {missed}"
            raise AssertionError(message)

    def test_passes_a_config_without_an_sdk(self) -> None:
        """Qualifiers alone, and a nested call in them, are not a pin."""
        text = '@Config(qualifiers = "w720dp-h1024dp-notnight")\nclass D'
        if sdk_pins(text):
            message = f"the scanner flagged a qualifier-only @Config: {text!r}"
            raise AssertionError(message)


class RobolectricSdkOwnerTest(unittest.TestCase):
    """Verify no JVM test source or properties file overrides the target SDK."""

    def test_no_test_class_pins_an_sdk(self) -> None:
        """No @Config in the JVM tests names sdk, minSdk, or maxSdk."""
        sources = sorted(_JVM_TEST_ROOT.rglob("*.kt"))
        if not sources:
            message = f"no Kotlin tests under {_JVM_TEST_ROOT}; the scan proves nothing"
            raise AssertionError(message)
        pinned = [
            f"{source.relative_to(REPO_ROOT)}: @Config({args.strip()})"
            for source in sources
            for args in sdk_pins(source.read_text(encoding="utf-8"))
        ]
        if pinned:
            message = (
                "a test pins its own Robolectric SDK; drop it so the class runs"
                " at the targetSdk:\n" + "\n".join(pinned)
            )
            raise AssertionError(message)

    def test_no_robolectric_properties_sets_an_sdk(self) -> None:
        """No robolectric.properties under the JVM tests sets an SDK."""
        pinned = [
            str(path.relative_to(REPO_ROOT))
            for path in sorted(_JVM_TEST_ROOT.rglob("robolectric.properties"))
            if _SDK_PROPERTY.search(path.read_text(encoding="utf-8"))
        ]
        if pinned:
            message = f"robolectric.properties sets an SDK: {pinned}"
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
