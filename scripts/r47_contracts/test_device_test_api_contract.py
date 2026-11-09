"""Hold the emulator the instrumented tests run on to the platform the app targets.

`R47_DEFAULT_ANDROID_TEST_API_LEVEL` names the system image (`37.0`), and
`R47_DEFAULT_ANDROID_TARGET_SDK` the platform the app targets. A device test below
the target runs the app under compatibility behavior it does not ship with, so the
image's major must reach the target.

The app also installs from `R47_DEFAULT_ANDROID_MIN_SDK`, so android-ci
keeps a second row that runs the same instrumented set on an image at that level,
under its own AVD name, on Sunday's nightly and on a dispatch that asks.
"""

from __future__ import annotations

import re
import unittest
from typing import Final

from r47_contracts._repo_paths import REPO_ROOT

_DEFAULTS: Final = REPO_ROOT / "android" / "r47-defaults.properties"
_ANDROID_CI: Final = REPO_ROOT / ".github" / "workflows" / "android-ci.yml"
_MIN_SDK_STEP: Final = "      - name: Run the device tests at minSdk on emulator"
_LEVEL: Final = re.compile(r"^(\d+)(?:\.\d+)?$")


def _property(text: str, name: str) -> str:
    """Return the value of `name=` in properties text."""
    match = re.search(rf"^{name}=(.*)$", text, re.MULTILINE)
    if match is None:
        message = f"{name} not found"
        raise AssertionError(message)
    return match.group(1).strip()


def reaches_target(test_level: str, target_sdk: str) -> bool:
    """Return whether an image level such as `37.0` reaches an integer target SDK."""
    match = _LEVEL.match(test_level)
    if match is None:
        message = f"not an API level: {test_level!r}"
        raise AssertionError(message)
    return int(match.group(1)) >= int(target_sdk)


class DeviceTestApiContractTest(unittest.TestCase):
    """The emulator's major API level reaches the target SDK."""

    def test_the_check_refuses_a_level_below_the_target(self) -> None:
        """Seeded: 36 under a target of 37 fails, 37.0 and 37.1 pass."""
        if reaches_target("36", "37") or not reaches_target("37.0", "37"):
            message = "reaches_target misjudged a seeded level"
            raise AssertionError(message)
        if not reaches_target("37.1", "37"):
            message = "reaches_target refused a minor of the target"
            raise AssertionError(message)

    def test_the_emulator_reaches_the_target_sdk(self) -> None:
        """The pinned image's major is at least the target SDK."""
        text = _DEFAULTS.read_text(encoding="utf-8")
        level = _property(text, "R47_DEFAULT_ANDROID_TEST_API_LEVEL")
        target = _property(text, "R47_DEFAULT_ANDROID_TARGET_SDK")
        if not reaches_target(level, target):
            message = f"the emulator runs API {level}, below the target SDK {target}"
            raise AssertionError(message)

    def test_a_row_runs_the_device_tests_at_min_sdk(self) -> None:
        """android-ci runs the instrumented set on a minSdk image, gated and named."""
        text = _ANDROID_CI.read_text(encoding="utf-8")
        if _MIN_SDK_STEP not in text:
            message = "android-ci has no device-test row at minSdk"
            raise AssertionError(message)
        step = text.split(_MIN_SDK_STEP, 1)[1].split("\n      - name:", 1)[0]
        for needed in (
            "if: steps.min-sdk.outputs.run == 'true'",
            "api-level: ${{ steps.defaults.outputs.android_min_sdk }}",
            "avd-name: test-min-sdk",
            "run_connected_android_tests.sh",
        ):
            if needed not in step:
                message = f"the minSdk row lacks {needed!r}"
                raise AssertionError(message)
        wiring = 'echo "android_min_sdk=$R47_DEFAULT_ANDROID_MIN_SDK"'
        if wiring not in text:
            message = (
                "android-ci does not read the minSdk image level from the defaults"
            )
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
