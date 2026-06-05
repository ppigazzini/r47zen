"""Derive the live program-stop key contract from upstream input.c and Kotlin.

The Android live keypad publishes an out-of-band direct stop only for the key
codes the upstream run loop treats as a stop request. `src/c47/programming/
input.c` is the source of truth: it stops a program when `key == 36 || key == 33`
while `*prevStop == PGM_RUNNING`. `LiveProgramStopKeyPolicy.kt` mirrors those
codes. This module parses both sides so the cross-source contract test can prove
the Android policy never drifts from, or grows broader than, the upstream rule.
"""

from __future__ import annotations

import json
import re
import sys
from typing import Final, TypedDict

from r47_contracts._kotlin_consts import parse_kotlin_const_values
from r47_contracts._repo_paths import (
    KOTLIN_R47ZEN_ROOT,
    UPSTREAM_R47_INPUT_PATH,
)

_LIVE_STOP_POLICY_PATH: Final = KOTLIN_R47ZEN_ROOT / "LiveProgramStopKeyPolicy.kt"
_RUN_LOOP_STOP_CLAUSE_PATTERN: Final = re.compile(
    r"\(([^()]*\bkey\s*==[^()]*)\)\s*&&\s*\*prevStop\s*==\s*(?P<guard>PGM_RUNNING)",
)
_KEY_CODE_PATTERN: Final = re.compile(r"key\s*==\s*(\d+)")
_EXIT_CONST_NAME: Final = "EXIT_KEY_CODE"
_RUN_STOP_CONST_NAME: Final = "RUN_STOP_KEY_CODE"


class LiveStopKeyPolicyPayload(TypedDict):
    """Cross-source view of the live program-stop key contract."""

    upstream_stop_key_codes: list[int]
    upstream_run_state_guard: str
    kotlin_stop_key_codes: list[int]
    kotlin_constants: dict[str, int]


def _derive_upstream_stop_keys() -> tuple[list[int], str]:
    source = UPSTREAM_R47_INPUT_PATH.read_text(encoding="utf-8")
    match = _RUN_LOOP_STOP_CLAUSE_PATTERN.search(source)
    if match is None:
        message = (
            "Could not locate the run-loop stop-key clause "
            f"(key == NN ... && *prevStop == PGM_RUNNING) in {UPSTREAM_R47_INPUT_PATH}"
        )
        raise ValueError(message)
    key_codes = sorted(
        {int(code) for code in _KEY_CODE_PATTERN.findall(match.group(1))},
    )
    return key_codes, match.group("guard")


def _derive_kotlin_stop_keys() -> dict[str, int]:
    values = parse_kotlin_const_values(_LIVE_STOP_POLICY_PATH)
    constants: dict[str, int] = {}
    for name in (_EXIT_CONST_NAME, _RUN_STOP_CONST_NAME):
        if name not in values:
            message = f"Missing const {name} in {_LIVE_STOP_POLICY_PATH}"
            raise ValueError(message)
        constants[name] = int(values[name])
    return constants


def build_live_stop_key_policy_payload() -> LiveStopKeyPolicyPayload:
    """Build the cross-source live program-stop key contract payload."""
    upstream_codes, guard = _derive_upstream_stop_keys()
    kotlin_constants = _derive_kotlin_stop_keys()
    return LiveStopKeyPolicyPayload(
        upstream_stop_key_codes=upstream_codes,
        upstream_run_state_guard=guard,
        kotlin_stop_key_codes=sorted(set(kotlin_constants.values())),
        kotlin_constants=dict(sorted(kotlin_constants.items())),
    )


def main() -> int:
    """Write the live program-stop key payload to standard output as JSON."""
    json.dump(
        build_live_stop_key_policy_payload(),
        sys.stdout,
        indent=2,
        sort_keys=True,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
