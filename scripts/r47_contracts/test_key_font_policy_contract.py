"""Lock the keypad font-policy JSON to live fonts, fixtures, and Kotlin owners."""

import unittest

from r47_contracts._contract_data import load_key_font_policy_contract
from r47_contracts.derive_key_font_policy import build_key_font_policy_payload


class KeyFontPolicyContractTest(unittest.TestCase):
    """Verify that the checked-in keypad font contract matches the live payload."""

    def test_key_font_policy_payload_matches_contract_json(self) -> None:
        """Keep the canonical font-policy JSON aligned with current inputs."""
        actual = build_key_font_policy_payload()
        expected = load_key_font_policy_contract()
        if actual != expected:
            message = "Keypad font-policy contract drifted from the live payload"
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
