#!/bin/bash
# Report how the keypad scenes the core exports today differ from the tracked ones the
# JVM tests read. The tracked scenes name the upstream commit they came from, and this
# repo tracks upstream HEAD, so drift from an upstream move is expected: it is reported
# and the run passes. Drift with the same upstream commit on both sides came from this
# repo, a change to the glue left without its re-export, and fails the run, as do an
# export that fails and a scene it cannot read. Upstream moves most days, so that
# check holds only until upstream next moves after a re-export.
# test_keypad_snapshot_wire_contract.py holds the scene's shape.
#
# When a scene drifts, re-export with export_upstream_keypad_fixtures.sh, then re-derive
# the goldens that read the fixtures (page 08 of android/docs/dev, "Python Contract
# Suite"), each move root-caused, and run the JVM tests on the new scenes.
#
# Usage: report_keypad_fixture_drift.sh [<exported dir>]
# With no directory it exports to android/build/keypad-fixtures-export, which needs
# what the exporter needs, and logs the exporter to keypad-fixtures-export.log beside
# it; with one it compares that directory, which is how a seeded copy is checked.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TRACKED_DIR="$PROJECT_ROOT/android/app/src/test/resources/keypad-fixtures"

if [ "$#" -gt 0 ]; then
    EXPORTED_DIR="$1"
else
    EXPORTED_DIR="$PROJECT_ROOT/android/build/keypad-fixtures-export"
    log="$EXPORTED_DIR.log"
    rm -rf "$EXPORTED_DIR"
    mkdir -p "$(dirname "$EXPORTED_DIR")"
    if ! bash "$SCRIPT_DIR/export_upstream_keypad_fixtures.sh" --output-dir "$EXPORTED_DIR" >"$log" 2>&1; then
        # Its errors, not the core's hundreds of warnings, or else its last lines.
        grep -E 'error|ERROR|undefined reference' "$log" | tail -n 20 >&2 || tail -n 40 "$log" >&2
        echo "FAIL: the keypad fixture export failed; the full log is $log"
        exit 1
    fi
fi

python3 -I - "$TRACKED_DIR" "$EXPORTED_DIR" <<'PY'
import json, sys
from pathlib import Path

tracked, exported = (Path(arg) for arg in sys.argv[1:3])


def load(directory, name):
    return json.loads((directory / name).read_text(encoding="utf-8"))


def show(value):
    # Labels come from upstream: quoted, so none can start a line GitHub reads as a command.
    return json.dumps(value, ensure_ascii=False)


old, new = load(tracked, "manifest.json"), load(exported, "manifest.json")
print(f"keypad fixtures: tracked from upstream {old['upstreamCommit']}, "
      f"exported from {new['upstreamCommit']}")
lanes = ("primary", "f", "g", "letter", "aux")
per_key = old["labelsPerKey"]
drifted, manifest_drifts = set(), False
for field in sorted((old.keys() | new.keys()) - {"upstreamCommit", "scenarios"}):
    if old.get(field) != new.get(field):
        manifest_drifts = True
        print(f"DRIFT manifest: {field}: {show(old.get(field))} -> {show(new.get(field))}")
old_files = {scene["name"]: scene["file"] for scene in old["scenarios"]}
new_files = {scene["name"]: scene["file"] for scene in new["scenarios"]}
for name in sorted(old_files.keys() ^ new_files.keys()):
    drifted.add(name)
    print(f"DRIFT {name}: only {'tracked' if name in old_files else 'exported'}")
for name in sorted(old_files.keys() & new_files.keys()):
    before, after = load(tracked, old_files[name]), load(exported, new_files[name])
    changes = [f"{field}: {show(before.get(field))} -> {show(after.get(field))}"
               for field in sorted((before.keys() | after.keys()) - {"labels", "meta"})
               if before.get(field) != after.get(field)]
    changes += [f"labels[{i}] key {i // per_key + 1} {lanes[i % per_key] if i % per_key < len(lanes) else i % per_key}: "
                f"{show(a)} -> {show(b)}"
                for i, (a, b) in enumerate(zip(before["labels"], after["labels"])) if a != b]
    changes += [f"meta[{i}]: {a} -> {b}"
                for i, (a, b) in enumerate(zip(before["meta"], after["meta"])) if a != b]
    changes += [f"{field}: {len(before[field])} entries -> {len(after[field])}"
                for field in ("labels", "meta") if len(before[field]) != len(after[field])]
    if changes:
        drifted.add(name)
    for change in changes[:12]:
        print(f"DRIFT {name}: {change}")
    if len(changes) > 12:
        print(f"DRIFT {name}: and {len(changes) - 12} more")
if not drifted and not manifest_drifts:
    print("SUMMARY: no scene drifts")
    sys.exit(0)
print(f"SUMMARY: {len(drifted)} of {len(old_files.keys() | new_files.keys())} scenes drift"
      f"{', and the manifest' if manifest_drifts else ''}: re-export with "
      "scripts/keypad-fixtures/export_upstream_keypad_fixtures.sh and re-derive the goldens that read them")
if old["upstreamCommit"] == new["upstreamCommit"]:
    print("FAIL: the scenes moved with no upstream move: re-export them in the commit that changed them")
    sys.exit(1)
PY
