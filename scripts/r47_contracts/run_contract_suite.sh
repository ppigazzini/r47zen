#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DERIVE_MODULES=(
    r47_contracts.derive_touch_grid
    r47_contracts.derive_shell_geometry
    r47_contracts.derive_key_label_geometry
    r47_contracts.derive_key_visual_policy
    r47_contracts.derive_key_font_policy
    r47_contracts.derive_top_label_lane_layout
    r47_contracts.derive_keyboard_layout_contract
    r47_contracts.derive_live_stop_key_policy
)

TEST_MODULES=(
    r47_contracts.test_shell_geometry_contract
    r47_contracts.test_key_label_geometry_contract
    r47_contracts.test_key_visual_policy_contract
    r47_contracts.test_key_font_policy_contract
    r47_contracts.test_top_label_lane_layout_contract
    r47_contracts.test_keyboard_layout_contract
    r47_contracts.test_alpha_case_export_contract
    r47_contracts.test_live_stop_key_policy_contract
    r47_contracts.test_lcd_packed_row_contract
    r47_contracts.test_keypad_snapshot_wire_contract
    r47_contracts.test_jni_registration_contract
    r47_contracts.test_upstream_provenance_contract
)

cd "$PROJECT_ROOT"

export PYTHONPATH="$PROJECT_ROOT/scripts"

uv run --group dev python -V
# No --select here: a CLI selector overrides the config's `select` AND discards
# its `ignore`, which silently re-enabled a rule pyproject.toml deliberately
# turns off. pyproject.toml already sets `select = ["ALL"]`, so let it own the
# rule set and keep this lane identical to the pre-commit hook.
uv run --group dev ruff check --no-cache scripts/r47_contracts
uv run --group dev ty check scripts/r47_contracts
uv run --group dev python -m r47_contracts.validate_geometry_dataset

for module in "${DERIVE_MODULES[@]}"; do
    uv run --group dev python -m "$module" >/dev/null
done

uv run --group dev python -m unittest "${TEST_MODULES[@]}"

# Provenance REPORT, not a gate. test_upstream_provenance_contract already fails
# the run when the ledger and the suite disagree about which upstream inputs
# exist. Drift is different: this repo tracks upstream HEAD, so an input moving
# is the normal state and must never fail a lane by itself. Printing it here
# gives whoever reads the log the one thing the goldens cannot say on their own
# - which upstream bytes moved, and therefore which golden to root-cause before
# re-blessing it.
uv run --group dev python -m r47_contracts.upstream_provenance
