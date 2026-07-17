#!/bin/bash

# JVM unit-test coverage gate. Reads the Kover release
# XML report and fails if overall line coverage drops below the floor, or if the
# hardened live program-stop routing seam loses full line coverage.
#
# This parses the report Kover already publishes rather than using Kover's
# `verify` DSL: under AGP 9.2 the per-Android-variant `verify` bounds did not
# enforce here (a 100 % floor still passed), so a self-contained, testable gate
# over the same deterministic XML is used instead.
#
# The total-line threshold is a maintainer-ratcheted FLOOR, not an auto-ratchet:
# it is sourced from R47_DEFAULT_COVERAGE_MIN_TOTAL_LINE_PERCENT in
# android/r47-defaults.properties, set just below the current measurement so a
# regression fails the gate without freezing the instrumented-only gaps (for
# example MainActivity). Bump the default up when coverage rises durably. The
# seam rule separately locks the routing-policy classes at
# 100 %.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
DEFAULTS_PATH="$ANDROID_DIR/r47-defaults.properties"
REPORT_XML="${R47_COVERAGE_REPORT_XML:-$ANDROID_DIR/app/build/reports/kover/reportRelease.xml}"
DEFAULT_MIN_TOTAL_LINE_PERCENT="$(sed -n 's/^R47_DEFAULT_COVERAGE_MIN_TOTAL_LINE_PERCENT=//p' "$DEFAULTS_PATH" 2>/dev/null | head -1)"
MIN_TOTAL_LINE_PERCENT="${R47_COVERAGE_MIN_TOTAL_LINE_PERCENT:-${DEFAULT_MIN_TOTAL_LINE_PERCENT:-80}}"
SEAM_CLASSES=(
    "io/github/ppigazzini/r47zen/LiveKeyRouter"
    "io/github/ppigazzini/r47zen/LiveProgramStopKeyPolicy"
)

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

command -v python3 >/dev/null 2>&1 || fail "python3 is required"

if [[ ! -f "$REPORT_XML" ]]; then
    echo "INFO: $REPORT_XML not found; generating it with Kover" >&2
    (cd "$ANDROID_DIR" && ./gradlew :app:koverXmlReportRelease \
        -Pr47.testBuildType=release --console=plain)
fi

python3 - "$REPORT_XML" "$MIN_TOTAL_LINE_PERCENT" "${SEAM_CLASSES[@]}" <<'PY'
import sys
import xml.etree.ElementTree as ET

report_xml = sys.argv[1]
min_total = float(sys.argv[2])
seam_classes = set(sys.argv[3:])

root = ET.parse(report_xml).getroot()


def line_counter(element):
    for counter in element.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("missed")), int(counter.get("covered"))
    return None


def percent(missed, covered):
    total = missed + covered
    return 100.0 if total == 0 else 100.0 * covered / total


failures = []

total = line_counter(root)
if total is None:
    sys.exit("no report-level LINE counter found in " + report_xml)
total_missed, total_covered = total
total_pct = percent(total_missed, total_covered)
print(f"total line coverage: {total_pct:.2f}% (floor {min_total:.0f}%)")
if total_pct < min_total:
    failures.append(
        f"total line coverage {total_pct:.2f}% is below the {min_total:.0f}% floor"
    )

found = set()
for cls in root.iter("class"):
    name = cls.get("name")
    if name not in seam_classes:
        continue
    found.add(name)
    counter = line_counter(cls)
    missed, covered = counter if counter else (0, 0)
    pct = percent(missed, covered)
    print(f"seam {name}: {pct:.2f}% line ({covered}/{covered + missed})")
    if missed != 0:
        failures.append(f"{name} is not fully line-covered ({pct:.2f}%, {missed} missed)")

for name in seam_classes - found:
    failures.append(f"seam class {name} not found in the coverage report")

if failures:
    for failure in failures:
        print("VIOLATION: " + failure, file=sys.stderr)
    sys.exit(1)

print("Coverage gate passed.")
PY
