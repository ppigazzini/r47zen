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
)

cd "$PROJECT_ROOT"

export PYTHONPATH="$PROJECT_ROOT/scripts"

uv run --group dev python -V
uv run --group dev ruff check --no-cache --select ALL scripts/r47_contracts
uv run --group dev ty check scripts/r47_contracts
uv run --group dev python -m r47_contracts.validate_geometry_dataset

for module in "${DERIVE_MODULES[@]}"; do
    uv run --group dev python -m "$module" >/dev/null
done

uv run --group dev python -m unittest "${TEST_MODULES[@]}"
