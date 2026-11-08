#!/bin/bash

# Mutation spot-check for the native glue the host contracts link alone: hal/io.c
# under run_hal_io_contract.sh and hal/lcd.c under run_keypad_generation_contract.sh.
# Each mutant must compile and make its contract print a FAIL line; a mutant the
# contract passes marks an assertion gap, and one that does not compile cannot be
# scored, so both fail the run. The sources are restored on every exit.
#
# Needs a host C compiler, as the two contracts do; no SDK, no device.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GLUE="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
IO="$GLUE/hal/io.c"
LCD="$GLUE/hal/lcd.c"
IO_CONTRACT="$SCRIPT_DIR/run_hal_io_contract.sh"
LCD_CONTRACT="$SCRIPT_DIR/run_keypad_generation_contract.sh"

MUT_FILES=("$IO" "$IO" "$IO" "$LCD")
MUT_OLD=(
    "    openedFileWriteFailed = true;"
    'snprintf(defaultName, sizeof(defaultName), "%s", "state.s47");'
    "if (fsync(fileno(openedFile)) != 0 && errno != EINVAL) {"
    $'  atomic_fetch_add_explicit(&keypadSnapshotGeneration, 1u,\n                            memory_order_relaxed);\n  pthread_mutex_unlock(&packedDisplayMutex);\n}'
)
MUT_NEW=(
    "    openedFileWriteFailed = false;"
    'snprintf(defaultName, sizeof(defaultName), "%s", "data.d47");'
    "if (fsync(fileno(openedFile)) != 0) {"
    $'  pthread_mutex_unlock(&packedDisplayMutex);\n}'
)
MUT_CONTRACTS=("$IO_CONTRACT" "$IO_CONTRACT" "$IO_CONTRACT" "$LCD_CONTRACT")
MUT_DESC=(
    "hal/io.c: a short write is never reported"
    "hal/io.c: a state save offers the data file's name"
    "hal/io.c: a pipe's fsync EINVAL counts as a failed save"
    "hal/lcd.c: a screen write leaves the keypad generation as it was"
)

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

backup_dir="$(mktemp -d)"
restore() {
    cp "$backup_dir/io.c" "$IO"
    cp "$backup_dir/lcd.c" "$LCD"
    rm -rf "$backup_dir"
}
cp "$IO" "$backup_dir/io.c"
cp "$LCD" "$backup_dir/lcd.c"
trap restore EXIT

for contract in "$IO_CONTRACT" "$LCD_CONTRACT"; do
    bash "$contract" >/dev/null 2>&1 || fail "$(basename "$contract") fails before any mutant"
done

log="$(mktemp)"
killed=0
for index in "${!MUT_FILES[@]}"; do
    file="${MUT_FILES[$index]}"
    description="${MUT_DESC[$index]}"
    echo "--- mutant: $description"
    python3 - "$file" "${MUT_OLD[$index]}" "${MUT_NEW[$index]}" <<'PY'
import sys

path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
count = text.count(old)
if count != 1:
    sys.exit(f"the mutant's text occurs {count} times in {path}, not once")
open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
PY
    if bash "${MUT_CONTRACTS[$index]}" >"$log" 2>&1; then
        echo "survived: $description"
    elif grep -q ': error:' "$log"; then
        cat "$log" >&2
        fail "the mutant does not compile, so it cannot be scored: $description"
    elif grep -q '^FAIL' "$log"; then
        echo "killed:   $description"
        killed=$((killed + 1))
    else
        cat "$log" >&2
        fail "the contract failed without a FAIL line: $description"
    fi
    cp "$backup_dir/$(basename "$file")" "$file"
done
rm -f "$log"

total=${#MUT_FILES[@]}
echo "Native mutation spot-check: $killed/$total mutants killed."
[ "$killed" -eq "$total" ] || fail "$((total - killed)) native mutant(s) survived"
