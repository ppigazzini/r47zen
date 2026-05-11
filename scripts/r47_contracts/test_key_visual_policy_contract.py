"""Lock Kotlin visual-policy constants to the Python visual-policy payload."""

import unittest

from r47_contracts._kotlin_consts import parse_kotlin_const_values_from_paths
from r47_contracts._repo_paths import KOTLIN_R47_ROOT
from r47_contracts.derive_key_visual_policy import build_key_visual_policy_payload

_KOTLIN_VISUAL_POLICY_PATH = KOTLIN_R47_ROOT / "R47KeypadPolicy.kt"


def _assert_float_equal(actual: float, expected: float, *, places: int = 6) -> None:
    tolerance = 10 ** (-places)
    if abs(actual - expected) > tolerance:
        message = f"Expected {expected} but saw {actual} within {places} places"
        raise AssertionError(message)


def _require_mapping(value: object, *, name: str) -> dict[str, object]:
    if not isinstance(value, dict):
        message = f"Expected {name} to be a dict, got {type(value).__name__}"
        raise TypeError(message)
    return {
        key if isinstance(key, str) else str(key): nested_value
        for key, nested_value in value.items()
        if isinstance(key, str)
    }


def _require_number(value: object, *, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        message = f"Expected {name} to be numeric, got {type(value).__name__}"
        raise TypeError(message)
    return float(value)


class KeyVisualPolicyContractTest(unittest.TestCase):
    """Verify that Kotlin visual-policy constants match the Python payload."""

    @classmethod
    def setUpClass(cls) -> None:
        """Load the shared payload and Kotlin constants once for the module."""
        cls.payload: dict[str, object] = build_key_visual_policy_payload()
        cls.kotlin: dict[str, float] = parse_kotlin_const_values_from_paths(
            [_KOTLIN_VISUAL_POLICY_PATH],
        )

    def test_visual_policy_constants_match_python_contract(self) -> None:
        """Keep the Kotlin visual-policy constants aligned with the Python payload."""
        expected = _require_mapping(
            self.payload["visual_policy_constants"],
            name="visual_policy_constants",
        )
        for name, value in expected.items():
            _assert_float_equal(
                self.kotlin[name],
                _require_number(value, name=f"visual_policy_constants.{name}"),
            )


if __name__ == "__main__":
    unittest.main()
