"""Lock the upstream provenance ledger against the inputs the suite really reads.

The ledger only earns trust if it cannot quietly fall behind the suite. These
tests hold the halves of that: the committed ledger covers exactly the upstream
inputs the contract modules declare, every module that names an input is listed
against it, the evaluator still sorts a tampered ledger into the right buckets,
and `--record-if-unaffected` refuses every move that changed a golden or a
contract. The drift cases run against synthetic ledgers on purpose - a test that
asserted "nothing drifted" would fail every time upstream moved, which is the
normal state for a repo tracking HEAD.
"""

from __future__ import annotations

import unittest
from typing import TYPE_CHECKING, ClassVar, Final

from r47_contracts import _repo_paths
from r47_contracts._repo_paths import PACKAGE_ROOT, REPO_ROOT
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
    input_surfaces,
    load_ledger,
    unaffected_record_refusal,
)

if TYPE_CHECKING:
    from collections.abc import Sequence

# Modules that name every input without reading one: the path table, the ledger
# tool, and this test.
_NON_READERS: Final = frozenset(
    {
        "_repo_paths.py",
        "upstream_provenance.py",
        "test_upstream_provenance_contract.py",
    },
)
_GOLDEN: Final = "scripts/r47_contracts/data/golden.json"
_GUARD: Final = "scripts/r47_contracts/test_guard.py"


def _assert_equal(actual: object, expected: object) -> None:
    if actual != expected:
        message = f"Expected {expected!r}, got {actual!r}"
        raise AssertionError(message)


def _assert_refused_naming(refusal: str | None, text: str) -> None:
    if refusal is None or text not in refusal:
        message = f"Expected a refusal naming {text!r}, got {refusal!r}"
        raise AssertionError(message)


def _statuses(findings: list[Finding]) -> dict[str, str]:
    return {finding["path"]: finding["status"] for finding in findings}


def _input_tokens() -> dict[str, str]:
    # The text a module must hold to read an input: its _repo_paths constant
    # for an upstream source file, its file name for a font.
    tokens = {
        str(value.relative_to(REPO_ROOT)): name
        for name, value in vars(_repo_paths).items()
        if name.startswith("UPSTREAM_R47_") and name.endswith("_PATH")
    }
    for path in declared_upstream_inputs():
        tokens.setdefault(path, path.rsplit("/", 1)[-1])
    return tokens


def _moved(path: str, contracts: list[str]) -> Finding:
    return Finding(
        path=path,
        status="moved",
        recorded_sha256="0" * 64,
        current_sha256="1" * 64,
        contracts=contracts,
    )


def _never_called(_: object) -> bool:
    message = "a refusal earlier in the order must stop before this check"
    raise AssertionError(message)


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

    def test_ledger_records_the_live_surface_map(self) -> None:
        """The ledger's derivers and contracts are the ones the tool reports."""
        recorded = {
            entry["path"]: (tuple(entry["derivers"]), tuple(entry["contracts"]))
            for entry in self.ledger["inputs"]
        }
        _assert_equal(recorded, input_surfaces())

    def test_every_listed_surface_exists(self) -> None:
        """A deriver or contract listed against an input is a file in the repo."""
        missing = sorted(
            path
            for derivers, contracts in input_surfaces().values()
            for path in (*derivers, *contracts)
            if not (REPO_ROOT / path).is_file()
        )
        _assert_equal(missing, [])

    def test_every_module_naming_an_input_is_listed_against_it(self) -> None:
        """A module that names an upstream input cannot be missing from its report."""
        surfaces = input_surfaces()
        unlisted: list[str] = []
        for path, token in sorted(_input_tokens().items()):
            derivers, contracts = surfaces[path]
            listed = {*derivers, *contracts}
            for module in sorted(PACKAGE_ROOT.glob("*.py")):
                if module.name in _NON_READERS:
                    continue
                if token not in module.read_text(encoding="utf-8"):
                    continue
                relative = str(module.relative_to(REPO_ROOT))
                if relative not in listed:
                    unlisted.append(f"{relative} names {path}")
        _assert_equal(unlisted, [])

    def test_every_input_is_guarded_by_a_test_module(self) -> None:
        """Every input lists a test module, so a move can be proven harmless."""
        unguarded = sorted(
            path
            for path, (_, contracts) in input_surfaces().items()
            if not any(contract.endswith(".py") for contract in contracts)
        )
        _assert_equal(unguarded, [])


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


class RecordIfUnaffectedTest(unittest.TestCase):
    """Verify --record-if-unaffected refuses every move that changed something."""

    def test_nothing_moved_needs_no_check(self) -> None:
        """With no drift there is nothing to prove and nothing is refused."""
        refusal = unaffected_record_refusal(
            [],
            golden_changed=_never_called,
            contracts_pass=_never_called,
        )
        _assert_equal(refusal, None)

    def test_a_coverage_failure_is_refused(self) -> None:
        """A ledger out of sync with the suite is re-recorded with --record."""
        finding = _moved("src/c47/retired.c", [_GUARD])
        finding["status"] = "unrecorded"
        refusal = unaffected_record_refusal(
            [finding],
            golden_changed=_never_called,
            contracts_pass=_never_called,
        )
        _assert_refused_naming(refusal, "coverage")

    def test_an_unguarded_move_is_refused(self) -> None:
        """A moved input with no test module listed cannot be proven harmless."""
        refusal = unaffected_record_refusal(
            [_moved("src/c47/assign.c", [_GOLDEN])],
            golden_changed=_never_called,
            contracts_pass=_never_called,
        )
        _assert_refused_naming(refusal, "src/c47/assign.c")

    def test_a_golden_that_differs_from_head_is_refused(self) -> None:
        """A golden re-blessed in the working tree is not laundered as unaffected."""
        refusal = unaffected_record_refusal(
            [_moved("src/c47/assign.c", [_GOLDEN, _GUARD])],
            golden_changed=lambda path: path == _GOLDEN,
            contracts_pass=_never_called,
        )
        _assert_refused_naming(refusal, _GOLDEN)

    def test_a_failing_contract_is_refused(self) -> None:
        """A move that breaks a guarding test is not harmless."""
        refusal = unaffected_record_refusal(
            [_moved("src/c47/assign.c", [_GOLDEN, _GUARD])],
            golden_changed=lambda _: False,
            contracts_pass=lambda _: False,
        )
        _assert_refused_naming(refusal, "fails")

    def test_a_harmless_move_runs_each_guard_once(self) -> None:
        """Unchanged goldens and passing guards clear the move."""
        ran: list[Sequence[str]] = []

        def record_run(tests: Sequence[str]) -> bool:
            ran.append(tests)
            return True

        other_guard = "scripts/r47_contracts/test_other.py"
        refusal = unaffected_record_refusal(
            [
                _moved("src/c47/assign.c", [_GOLDEN, _GUARD]),
                _moved("src/c47/items.c", [_GOLDEN, _GUARD, other_guard]),
            ],
            golden_changed=lambda _: False,
            contracts_pass=record_run,
        )
        _assert_equal(refusal, None)
        _assert_equal(ran, [[_GUARD, other_guard]])


if __name__ == "__main__":
    unittest.main()
