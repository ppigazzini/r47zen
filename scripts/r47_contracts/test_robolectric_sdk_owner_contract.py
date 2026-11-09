"""Hold the SDK the Robolectric tests run at to its one owner, the targetSdk.

No `robolectric.properties` sets an sdk, and no JVM test names one in `@Config`
but `ApiLevelArmsTest`, so Robolectric runs every other class at the
targetSdkVersion of the merged manifest, which `R47_DEFAULT_ANDROID_TARGET_SDK` in
`android/r47-defaults.properties` sets; a pin copied into another class would
move it off the target unseen. `ApiLevelArmsTest` runs the shell's
`Build.VERSION.SDK_INT` arms at levels the app installs on below the target,
so each level it names must lie from `R47_DEFAULT_ANDROID_MIN_SDK` up to
below the target. `RobolectricSdkTest` proves at run time that a default class
runs the target; this proves the sources, after proving the scanner on seeded
text.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._repo_paths import ANDROID_TEST_RESOURCES_ROOT, REPO_ROOT

_JVM_TEST_ROOT: Final = ANDROID_TEST_RESOURCES_ROOT.parent
_DEFAULTS: Final = REPO_ROOT / "android" / "r47-defaults.properties"
_API_ARMS_TEST: Final = "ApiLevelArmsTest.kt"
# Build.VERSION_CODES names to API levels, for the levels a pin may name.
_VERSION_CODES: Final = {
    "N": 24,
    "N_MR1": 25,
    "O": 26,
    "O_MR1": 27,
    "P": 28,
    "Q": 29,
    "R": 30,
    "S": 31,
    "S_V2": 32,
    "TIRAMISU": 33,
    "UPSIDE_DOWN_CAKE": 34,
    "VANILLA_ICE_CREAM": 35,
    "BAKLAVA": 36,
}
_PINNED_LEVEL: Final = re.compile(r"Build\.VERSION_CODES\.([A-Z_0-9]+)|\b(\d+)\b")
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


def pinned_levels(arguments: str) -> list[int | None]:
    """Return the API level of each level a `sdk = [...]` names; None if unknown."""
    sdk = re.search(r"\bsdk\s*=\s*\[([^\]]*)\]", arguments)
    if sdk is None:
        return [None]
    levels: list[int | None] = []
    for name, number in _PINNED_LEVEL.findall(sdk.group(1)):
        levels.append(_VERSION_CODES.get(name) if name else int(number))
    return levels or [None]


def _property(name: str) -> int:
    match = re.search(
        rf"^{name}=(\d+)",
        _DEFAULTS.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    if match is None:
        message = f"{name} not found in {_DEFAULTS}"
        raise AssertionError(message)
    return int(match.group(1))


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
        """No @Config outside ApiLevelArmsTest names sdk, minSdk, or maxSdk."""
        sources = sorted(_JVM_TEST_ROOT.rglob("*.kt"))
        if not sources:
            message = f"no Kotlin tests under {_JVM_TEST_ROOT}; the scan proves nothing"
            raise AssertionError(message)
        pinned = [
            f"{source.relative_to(REPO_ROOT)}: @Config({args.strip()})"
            for source in sources
            if source.name != _API_ARMS_TEST
            for args in sdk_pins(source.read_text(encoding="utf-8"))
        ]
        if pinned:
            message = (
                "a test pins its own Robolectric SDK; drop it so the class runs"
                " at the targetSdk:\n" + "\n".join(pinned)
            )
            raise AssertionError(message)

    def test_api_arms_pin_only_installable_levels_below_the_target(self) -> None:
        """ApiLevelArmsTest names levels from minSdk up to below the target."""
        sources = list(_JVM_TEST_ROOT.rglob(_API_ARMS_TEST))
        if len(sources) != 1:
            message = f"expected one {_API_ARMS_TEST}, found {sources}"
            raise AssertionError(message)
        low = _property("R47_DEFAULT_ANDROID_MIN_SDK")
        high = _property("R47_DEFAULT_ANDROID_TARGET_SDK")
        pins = sdk_pins(sources[0].read_text(encoding="utf-8"))
        bad = [
            args.strip()
            for args in pins
            if any(
                level is None or not low <= level < high
                for level in pinned_levels(args)
            )
        ]
        if not pins or bad:
            message = (
                f"{_API_ARMS_TEST} pins no level, or one outside [{low}, {high}): {bad}"
            )
            raise AssertionError(message)

    def test_the_level_reader_refuses_seeded_levels(self) -> None:
        """Seeded: an unknown name, minSdk = 21, and no list read as unknown."""
        cases = {
            "sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.R]": [24, 30],
            "sdk = [23]": [23],
            "sdk = [Build.VERSION_CODES.FUTURE]": [None],
            "minSdk = 21": [None],
        }
        for arguments, expected in cases.items():
            if pinned_levels(arguments) != expected:
                message = (
                    f"pinned_levels({arguments!r}) gave {pinned_levels(arguments)}"
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
