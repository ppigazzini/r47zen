"""Lock the Kotlin live program-stop key policy to the upstream run-loop codes.

This is a genuine cross-source oracle, not a self-snapshot: it parses the real
upstream `src/c47/programming/input.c` run-loop stop clause and the real
`LiveProgramStopKeyPolicy.kt` constants and proves they agree. It fails if the
Android policy drifts from the upstream EXIT(33)/R-S(36) stop codes or grows
broader than the upstream rule -- the exact class of divergence behind the
swallowed-live-key regression (REPORT-24 W1/W3).
"""

from __future__ import annotations

import unittest
from typing import Final

from r47_contracts.derive_live_stop_key_policy import (
    build_live_stop_key_policy_payload,
)

_EXPECTED_STOP_KEY_CODES: Final = [33, 36]
_EXPECTED_RUN_STATE_GUARD: Final = "PGM_RUNNING"
_EXPECTED_KOTLIN_CONSTANTS: Final = {"EXIT_KEY_CODE": 33, "RUN_STOP_KEY_CODE": 36}


class LiveStopKeyPolicyContractTest(unittest.TestCase):
    """Verify the Android live-stop key policy matches upstream input.c."""

    def test_upstream_stop_keys_are_exit_and_run_stop(self) -> None:
        """The upstream run loop must treat exactly EXIT(33) and R/S(36) as stop."""
        payload = build_live_stop_key_policy_payload()
        if payload["upstream_stop_key_codes"] != _EXPECTED_STOP_KEY_CODES:
            message = (
                "Upstream input.c run-loop stop keys drifted from EXIT(33)/R-S(36): "
                f"{payload['upstream_stop_key_codes']}"
            )
            raise AssertionError(message)

    def test_kotlin_policy_matches_upstream_stop_keys(self) -> None:
        """The Kotlin policy stop codes must equal the upstream run-loop codes."""
        payload = build_live_stop_key_policy_payload()
        if payload["kotlin_stop_key_codes"] != payload["upstream_stop_key_codes"]:
            message = (
                "LiveProgramStopKeyPolicy stop codes "
                f"{payload['kotlin_stop_key_codes']} diverged from upstream "
                f"{payload['upstream_stop_key_codes']}"
            )
            raise AssertionError(message)

    def test_kotlin_constants_keep_upstream_names_and_values(self) -> None:
        """The Kotlin constants must keep the upstream EXIT/R-S code values."""
        payload = build_live_stop_key_policy_payload()
        if payload["kotlin_constants"] != _EXPECTED_KOTLIN_CONSTANTS:
            message = (
                "LiveProgramStopKeyPolicy constants drifted from the upstream "
                f"contract: {payload['kotlin_constants']}"
            )
            raise AssertionError(message)

    def test_upstream_stop_keys_guard_on_running_state(self) -> None:
        """The stop-key set is a stop request only while the program is RUNNING."""
        payload = build_live_stop_key_policy_payload()
        if payload["upstream_run_state_guard"] != _EXPECTED_RUN_STATE_GUARD:
            message = (
                "Upstream run-loop stop guard changed from PGM_RUNNING: "
                f"{payload['upstream_run_state_guard']}"
            )
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
