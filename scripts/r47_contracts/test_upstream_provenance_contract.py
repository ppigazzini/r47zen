"""Lock the upstream provenance ledger against the inputs the suite really reads.

The ledger only earns trust if it cannot quietly fall behind the suite. These
tests hold the two halves of that: the committed ledger covers exactly the
upstream inputs the contract modules declare, and the evaluator still sorts a
tampered ledger into the right buckets. The drift cases run against synthetic
ledgers on purpose - a test that asserted "nothing drifted" would fail every
time upstream moved, which is the normal state for a repo tracking HEAD.
"""

from __future__ import annotations

import unittest
from typing import ClassVar

from r47_contracts.upstream_provenance import (
    SCHEMA_VERSION,
    Finding,
    InputRecord,
    Ledger,
    RecordedSnapshot,
    coverage_failures,
    declared_upstream_inputs,
    drift_failures,
    evaluate,
    load_ledger,
)


def _assert_equal(actual: object, expected: object) -> None:
    if actual != expected:
        message = f"Expected {expected!r}, got {actual!r}"
        raise AssertionError(message)


def _statuses(findings: list[Finding]) -> dict[str, str]:
    return {finding["path"]: finding["status"] for finding in findings}


def _synthetic_ledger(inputs: list[InputRecord]) -> Ledger:
    return Ledger(
        schema=SCHEMA_VERSION,
        recorded=RecordedSnapshot(
            upstream_url="https://example.invalid/c43.git",
            upstream_commit="0" * 40,
            recorded_on="2026-01-01",
        ),
        inputs=inputs,
    )


class UpstreamProvenanceLedgerTest(unittest.TestCase):
    """Verify the committed ledger still describes the live contract suite."""

    ledger: ClassVar[Ledger]

    @classmethod
    def setUpClass(cls) -> None:
        """Load the committed provenance ledger once for the whole case."""
        cls.ledger = load_ledger()

    def test_schema_is_current(self) -> None:
        """The committed ledger speaks the schema this tool implements."""
        _assert_equal(self.ledger["schema"], SCHEMA_VERSION)

    def test_ledger_covers_every_declared_upstream_input(self) -> None:
        """Every upstream input the suite reads has a ledger entry, and vice versa."""
        recorded = {entry["path"] for entry in self.ledger["inputs"]}
        _assert_equal(recorded, declared_upstream_inputs())

    def test_committed_ledger_has_no_coverage_failure(self) -> None:
        """The committed ledger is in sync with the suite, so coverage is clean."""
        _assert_equal(coverage_failures(evaluate(self.ledger)), [])

    def test_every_entry_names_a_contract_surface(self) -> None:
        """No entry is recorded without saying which surface it puts at risk."""
        orphans = [
            entry["path"] for entry in self.ledger["inputs"] if not entry["contracts"]
        ]
        _assert_equal(orphans, [])

    def test_every_entry_names_a_deriver(self) -> None:
        """No entry is recorded without saying which module reads the bytes."""
        orphans = [
            entry["path"] for entry in self.ledger["inputs"] if not entry["derivers"]
        ]
        _assert_equal(orphans, [])


class UpstreamProvenanceEvaluatorTest(unittest.TestCase):
    """Verify the evaluator sorts a tampered ledger into the right buckets."""

    def test_missing_entry_is_unrecorded(self) -> None:
        """An upstream input the suite reads but the ledger omits is a coverage gap."""
        findings = evaluate(_synthetic_ledger([]))
        statuses = set(_statuses(findings).values())
        _assert_equal(statuses, {"unrecorded"})
        _assert_equal(len(coverage_failures(findings)), len(declared_upstream_inputs()))

    def test_unknown_entry_is_stale(self) -> None:
        """A ledger entry nothing reads any more is a coverage gap, not drift."""
        ledger = _synthetic_ledger(
            [
                InputRecord(
                    path="src/c47/retired.c",
                    sha256="0" * 64,
                    derivers=["scripts/r47_contracts/derive_gone.py"],
                    contracts=["scripts/r47_contracts/data/gone.json"],
                ),
            ],
        )
        findings = evaluate(ledger)
        _assert_equal(_statuses(findings)["src/c47/retired.c"], "stale-entry")
        _assert_equal(drift_failures(findings), [])

    def test_wrong_hash_is_drift_not_coverage(self) -> None:
        """A recorded hash that no longer matches is drift, and never a coverage gap."""
        entries = [
            InputRecord(
                path=path,
                sha256="0" * 64,
                derivers=["scripts/r47_contracts/derive_placeholder.py"],
                contracts=["scripts/r47_contracts/data/placeholder.json"],
            )
            for path in sorted(declared_upstream_inputs())
        ]
        findings = evaluate(_synthetic_ledger(entries))
        _assert_equal(coverage_failures(findings), [])
        # An unhydrated tree reports `missing`, never a false `moved`.
        for finding in findings:
            expected = "moved" if finding["current_sha256"] else "missing"
            _assert_equal(finding["status"], expected)


if __name__ == "__main__":
    unittest.main()
