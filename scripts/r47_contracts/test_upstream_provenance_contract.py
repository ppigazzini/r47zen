"""Lock the upstream provenance ledger against the inputs the suite really reads.

The ledger only earns trust if it cannot quietly fall behind the suite. These
tests hold the halves of that: the committed ledger and the surface map cover
exactly the upstream inputs the contract modules declare, every module that
names an input is listed against it, the evaluator still sorts a tampered
ledger into the right buckets, and `--record-if-unaffected` refuses every move
that changed a golden, its derivation, or a contract. The drift cases run
against synthetic ledgers on purpose - a test that asserted "nothing drifted"
would fail every time upstream moved, which is the normal state for a repo
tracking HEAD.
"""

from __future__ import annotations

import importlib
import sys
import unittest
from typing import TYPE_CHECKING, ClassVar, Final
from unittest import mock

from r47_contracts import _repo_paths, upstream_provenance
from r47_contracts._repo_paths import PACKAGE_ROOT, REPO_ROOT
from r47_contracts.upstream_provenance import (
    LEDGER,
    SCHEMA_VERSION,
    Finding,
    InputRecord,
    Ledger,
    RecordedSnapshot,
    coverage_failures,
    declared_upstream_inputs,
    drift_failures,
    drift_verdict,
    evaluate,
    golden_derivers,
    hal_surfaces,
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
_ANDROID_HAL: Final = "android/app/src/main/cpp/r47zen/hal/"
# Upstream's HAL headers, every port's contract, at the hydrated commit: a header
# upstream adds fails the test below until the ledger watches it.
_UPSTREAM_HAL: Final = REPO_ROOT / "src/c47/hal"


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
    # The text a module must hold to read an input the suite reads: its
    # _repo_paths constant for an upstream source file, its file name for a
    # font. The HAL files are read by the Android HAL, not by the suite.
    tokens = {
        str(value.relative_to(REPO_ROOT)): name
        for name, value in vars(_repo_paths).items()
        if name.startswith("UPSTREAM_R47_") and name.endswith("_PATH")
    }
    for path in input_surfaces():
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


def _never_called_reason(_: object) -> str | None:
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

    def test_surface_map_covers_exactly_the_declared_inputs(self) -> None:
        """Every declared input has surfaces listed, in one map, and no other does."""
        suite, hal = set(input_surfaces()), set(hal_surfaces())
        _assert_equal(suite & hal, set())
        _assert_equal(suite | hal, declared_upstream_inputs())

    def test_every_input_names_a_deriver_and_a_contract(self) -> None:
        """No input is listed without the module that reads it and what it feeds."""
        orphans = sorted(
            path
            for path, (derivers, contracts) in input_surfaces().items()
            if not derivers or not contracts
        )
        _assert_equal(orphans, [])

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


class HalInputTest(unittest.TestCase):
    """Verify the ledger watches the upstream HAL files the Android HAL follows.

    Upstream redefined BLT_SET and the lcd_buffer polarity with every signature
    unchanged; only a changed hash says such a file moved.
    """

    def test_every_upstream_hal_header_is_watched(self) -> None:
        """Each header of upstream's HAL, which every port implements, is listed."""
        headers = {f"src/c47/hal/{header.name}" for header in _UPSTREAM_HAL.glob("*.h")}
        if not headers:
            message = f"no header under {_UPSTREAM_HAL}: hydrate upstream first"
            raise AssertionError(message)
        _assert_equal(headers - set(hal_surfaces()), set())

    def test_the_simulator_hals_the_android_hal_follows_are_watched(self) -> None:
        """GTK's and the suite's lcd.c and io.c show a redefinition first."""
        for simulator in ("src/c47-gtk/hal", "src/testSuite/hal"):
            for name in ("lcd.c", "io.c"):
                if f"{simulator}/{name}" not in hal_surfaces():
                    message = f"{simulator}/{name} is not watched"
                    raise AssertionError(message)

    def test_each_names_android_hal_code_and_a_guard_that_exist(self) -> None:
        """A move names the Android file to re-read and the contract that holds it."""
        for path, surfaces in hal_surfaces().items():
            code = [s for s in surfaces if s.startswith(_ANDROID_HAL)]
            guards = [s for s in surfaces if s.startswith("scripts/")]
            missing = [s for s in surfaces if not (REPO_ROOT / s).is_file()]
            if not code or not guards or missing:
                message = f"{path}: code {code}, guards {guards}, missing {missing}"
                raise AssertionError(message)

    def test_a_moved_hal_input_is_never_cleared_unread(self) -> None:
        """--record-if-unaffected refuses, naming the Android HAL file to re-read."""
        path = "src/c47/hal/lcd.h"
        refusal = unaffected_record_refusal(
            [_moved(path, list(hal_surfaces()[path]))],
            golden_changed=_never_called,
            contracts_pass=_never_called,
            derivation_mismatch=_never_called_reason,
        )
        _assert_refused_naming(refusal, f"{_ANDROID_HAL}lcd.c")

    def test_the_verdict_says_a_hal_move_matters(self) -> None:
        """The job summary's verdict on a moved HAL file asks for a reader."""
        path = "src/testSuite/hal/io.c"
        verdict = drift_verdict(
            [_moved(path, list(hal_surfaces()[path]))],
            golden_changed=_never_called,
            contracts_pass=_never_called,
            derivation_mismatch=_never_called_reason,
        )
        if "matters" not in verdict or f"{_ANDROID_HAL}io.c" not in verdict:
            message = f"a moved {path} should ask for {_ANDROID_HAL}io.c: {verdict!r}"
            raise AssertionError(message)


class UpstreamProvenanceLedgerSchemaTest(unittest.TestCase):
    """Hold the ledger schema to the values `build_ledger` writes."""

    def test_recorded_on_is_an_ascii_date(self) -> None:
        """A date in Arabic-Indic digits is refused; ASCII digits pass."""
        if not LEDGER.is_valid(_synthetic_ledger([])):
            message = "an ASCII recorded_on was refused"
            raise AssertionError(message)
        arabic_indic = _synthetic_ledger([])
        arabic_indic["recorded"]["recorded_on"] = "\u0662\u0660\u0662\u0666-01-01"
        if LEDGER.is_valid(arabic_indic):
            message = "a recorded_on in Arabic-Indic digits was admitted"
            raise AssertionError(message)


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
                InputRecord(path="src/c47/retired.c", sha256="0" * 64),
            ],
        )
        findings = evaluate(ledger)
        _assert_equal(_statuses(findings)["src/c47/retired.c"], "stale-entry")
        _assert_equal(drift_failures(findings), [])

    def test_wrong_hash_is_drift_not_coverage(self) -> None:
        """A recorded hash that no longer matches is drift, and never a coverage gap."""
        entries = [
            InputRecord(path=path, sha256="0" * 64)
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
            derivation_mismatch=_never_called_reason,
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
            derivation_mismatch=_never_called_reason,
        )
        _assert_refused_naming(refusal, "coverage")

    def test_an_unguarded_move_is_refused(self) -> None:
        """A moved input with no test module listed cannot be proven harmless."""
        refusal = unaffected_record_refusal(
            [_moved("src/c47/assign.c", [_GOLDEN])],
            golden_changed=_never_called,
            contracts_pass=_never_called,
            derivation_mismatch=_never_called_reason,
        )
        _assert_refused_naming(refusal, "src/c47/assign.c")

    def test_a_golden_that_differs_from_head_is_refused(self) -> None:
        """A golden re-blessed in the working tree is not laundered as unaffected."""
        refusal = unaffected_record_refusal(
            [_moved("src/c47/assign.c", [_GOLDEN, _GUARD])],
            golden_changed=lambda path: path == _GOLDEN,
            contracts_pass=_never_called,
            derivation_mismatch=_never_called_reason,
        )
        _assert_refused_naming(refusal, _GOLDEN)

    def test_a_golden_its_deriver_would_change_is_refused(self) -> None:
        """A move that changes a golden's derivation is not harmless, guard or no guard.

        The font guard passes a code point a font gains, so the derivation is
        checked here.
        """
        refusal = unaffected_record_refusal(
            [_moved("src/c47/assign.c", [_GOLDEN, _GUARD])],
            golden_changed=lambda _: False,
            contracts_pass=_never_called,
            derivation_mismatch=lambda path: f"{path} differs from its derivation",
        )
        _assert_refused_naming(refusal, _GOLDEN)

    def test_every_golden_names_its_deriver(self) -> None:
        """Each golden the surface map names has a deriver to check it against."""
        goldens = {
            contract
            for _, contracts in input_surfaces().values()
            for contract in contracts
            if contract.endswith(".json")
        }
        _assert_equal(goldens, set(golden_derivers()))

    def test_each_golden_deriver_can_be_called(self) -> None:
        """Each listed deriver names a function its module defines.

        Not a comparison with the live tree: that fails whenever upstream moves.
        """
        missing = [
            f"{module_name}.{function_name}"
            for module_name, function_name in golden_derivers().values()
            if not callable(
                getattr(importlib.import_module(module_name), function_name, None),
            )
        ]
        _assert_equal(missing, [])

    def test_record_refuses_a_golden_that_is_not_its_derivation(self) -> None:
        """`--record` writes nothing while a golden differs from its derivation."""
        with (
            mock.patch.object(
                upstream_provenance,
                "derivation_mismatch",
                lambda path: f"{path} differs from its derivation",
            ),
            mock.patch.object(upstream_provenance, "write_ledger") as write,
            mock.patch.object(sys, "stderr"),
        ):
            status = upstream_provenance.main(["--record"])
        _assert_equal(status, 1)
        _assert_equal(write.call_count, 0)

    def test_a_failing_contract_is_refused(self) -> None:
        """A move that breaks a guarding test is not harmless."""
        refusal = unaffected_record_refusal(
            [_moved("src/c47/assign.c", [_GOLDEN, _GUARD])],
            golden_changed=lambda _: False,
            contracts_pass=lambda _: False,
            derivation_mismatch=lambda _: None,
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
            derivation_mismatch=lambda _: None,
        )
        _assert_equal(refusal, None)
        _assert_equal(ran, [[_GUARD, other_guard]])


class DriftVerdictTest(unittest.TestCase):
    """Verify --classify-drift tells a harmless move from one that matters.

    The DRIFT line prints on most runs, since upstream moves daily; the verdict
    is what makes a move that changed a golden stand out.
    """

    def test_no_drift_has_no_verdict(self) -> None:
        """With nothing moved there is nothing to judge, and nothing runs."""
        verdict = drift_verdict(
            [],
            golden_changed=_never_called,
            contracts_pass=_never_called,
            derivation_mismatch=_never_called_reason,
        )
        _assert_equal(verdict, "")

    def test_a_harmless_move_says_the_goldens_are_unaffected(self) -> None:
        """Unchanged goldens and passing guards read as unaffected."""
        verdict = drift_verdict(
            [_moved("src/c47/items.c", [_GOLDEN, _GUARD])],
            golden_changed=lambda _: False,
            contracts_pass=lambda _: True,
            derivation_mismatch=lambda _: None,
        )
        if "goldens unaffected" not in verdict:
            message = f"a harmless move should read as unaffected: {verdict!r}"
            raise AssertionError(message)

    def test_a_move_that_changes_a_golden_names_it(self) -> None:
        """A move its deriver would turn into a new golden reads as mattering."""
        verdict = drift_verdict(
            [_moved("res/fonts/C47__StandardFont.ttf", [_GOLDEN, _GUARD])],
            golden_changed=lambda _: False,
            contracts_pass=_never_called,
            derivation_mismatch=lambda path: f"{path} differs from its derivation",
        )
        if "matters" not in verdict or _GOLDEN not in verdict:
            message = f"a move that changes {_GOLDEN} should say so: {verdict!r}"
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
