"""Record and check which upstream bytes the derived contract goldens came from.

This repo tracks the latest upstream HEAD, so `src/c47/` and `res/fonts/` change
with no commit here, and the goldens under `scripts/r47_contracts/data/`
re-derive from those live inputs. That makes a freshness guard circular: compare
a committed golden against a fresh re-derivation and the deriver re-blesses
whatever the current tree happens to say, including a wrong change. This ledger
breaks the circle by recording, out of band, the upstream bytes that produced
the goldens as committed, so `did upstream actually move this input?` becomes a
question the tree can answer instead of one the maintainer has to reconstruct.

The ledger keys on CONTENT (sha256), not on commit archaeology. Two reasons.
`upstream.sh` fetches upstream shallow, so the local object store holds a single
upstream commit and `git log -- <path>` cannot attribute a per-file change here.
And content is the thing the derivers actually read - a rewritten-but-identical
file is not drift, while identical bytes arriving from a different commit are
not either. The recorded upstream commit is a watermark for the snapshot as a
whole; drift is therefore reported as a range between the recorded commit and
the current one, never as a per-file blame.

Two failure classes live here and they are deliberately NOT gated the same way:

  DRIFT     an upstream input moved since the ledger was recorded. Under a
            tracks-latest-HEAD policy this is normal and constant, so it is
            REPORTED and never fails a lane on its own. It tells a maintainer
            which goldens an upstream advance puts in question.
  COVERAGE  the contracts read an upstream input the ledger does not list, or
            the ledger lists one nothing reads any more. That is a repo-side
            mistake, fully deterministic, and it FAILS - otherwise the ledger
            silently rots into a comforting but incomplete list.

Drift is cleared one of two ways. `--record` rewrites the ledger in the commit
that re-blesses a golden. `--record-if-unaffected` rewrites it on its own, and
only when the move changed nothing: every golden a moved input feeds still
matches HEAD, and every contract that guards a moved input passes on the live
tree. Without it the same `moved` lines print until a real change arrives, and
then that change looks like all the others.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import unittest
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Annotated, Final, Literal, TypedDict

from valgebra import Regex, ValidationError, Validator

from r47_contracts import _repo_paths
from r47_contracts._repo_paths import DATA_ROOT, REPO_ROOT

if TYPE_CHECKING:
    from collections.abc import Callable, Sequence
    from pathlib import Path

LEDGER_PATH: Final = DATA_ROOT / "upstream_provenance.json"
UPSTREAM_LOCK_PATH: Final = REPO_ROOT / "upstream.lock"
UPSTREAM_SOURCE_PATH: Final = REPO_ROOT / "upstream.source"
SCHEMA_VERSION: Final = 2
UNKNOWN_COMMIT: Final = "unknown"  # `RecordedSnapshot.upstream_commit` spells it too

_UPSTREAM_PATH_PREFIX: Final = "UPSTREAM_R47_"
_UPSTREAM_PATH_SUFFIX: Final = "_PATH"
_FONT_ROOT_PARTS: Final = ("res", "fonts")
_READ_CHUNK_BYTES: Final = 1 << 20

Status = Literal["unchanged", "moved", "missing", "unrecorded", "stale-entry"]


class ProvenanceError(RuntimeError):
    """Raised when the provenance ledger cannot be read or interpreted."""

    @classmethod
    def unreadable(cls, path: Path, reason: str) -> ProvenanceError:
        """Build an error for a ledger that cannot be parsed."""
        message = f"Cannot read the provenance ledger at {path}: {reason}"
        return cls(message)

    @classmethod
    def wrong_schema(cls, path: Path, found: object) -> ProvenanceError:
        """Build an error for a ledger written by an incompatible schema."""
        message = (
            f"{path} declares schema {found!r}, but this tool speaks schema "
            f"{SCHEMA_VERSION}. Re-record the ledger or update the tool."
        )
        return cls(message)


class InputRecord(TypedDict, closed=True):
    """One upstream input and the bytes it held when the ledger was recorded.

    What reads each input, and what to re-examine when it moves, is the surface
    map in this module, not a copy in the ledger.
    """

    path: str
    # `hash_file` writes "" for an input the tree does not hold, so a ledger
    # recorded on an unhydrated tree still reads back.
    sha256: Literal[""] | Annotated[str, Regex(r"[0-9a-f]{64}")]


class RecordedSnapshot(TypedDict, closed=True):
    """The upstream watermark the ledger was recorded against."""

    upstream_url: str
    upstream_commit: Literal["unknown"] | Annotated[str, Regex(r"[0-9a-f]{40}")]
    recorded_on: Annotated[str, Regex(r"\d{4}-\d{2}-\d{2}")]


class Ledger(TypedDict, closed=True):
    """The on-disk provenance ledger; `load_ledger` admits nothing else."""

    schema: int
    recorded: RecordedSnapshot
    inputs: list[InputRecord]


LEDGER: Final = Validator(Ledger)


class Finding(TypedDict):
    """One evaluated upstream input."""

    path: str
    status: Status
    recorded_sha256: str
    current_sha256: str
    contracts: list[str]


# The contract surfaces each upstream input feeds. Keyed by repo-relative path.
# `derivers` read the bytes. `contracts` are the committed goldens derived from
# them and the test modules a maintainer must re-examine when they move; a test
# module that reads the bytes itself is listed there. Every module of the suite
# that names an input is listed against it, which
# test_upstream_provenance_contract.py holds. All paths are repo-relative so the
# report is copy-pasteable into a shell.
_KEYBOARD_LAYOUT_SURFACES: Final = (
    ("scripts/r47_contracts/derive_keyboard_layout_contract.py",),
    (
        "scripts/r47_contracts/data/r47_keyboard_layout_contract.json",
        "scripts/r47_contracts/test_alpha_case_export_contract.py",
        "scripts/r47_contracts/test_keyboard_layout_contract.py",
    ),
)
_FONT_POLICY_SURFACES: Final = (
    ("scripts/r47_contracts/derive_key_font_policy.py",),
    (
        "scripts/r47_contracts/data/r47_key_font_policy_contract.json",
        "scripts/r47_contracts/test_key_font_policy_contract.py",
    ),
)
_INPUT_SURFACES: Final[dict[str, tuple[tuple[str, ...], tuple[str, ...]]]] = {
    "src/c47/assign.c": _KEYBOARD_LAYOUT_SURFACES,
    "src/c47/items.c": _KEYBOARD_LAYOUT_SURFACES,
    "src/c47/programming/input.c": (
        ("scripts/r47_contracts/derive_live_stop_key_policy.py",),
        ("scripts/r47_contracts/test_live_stop_key_policy_contract.py",),
    ),
    "res/fonts/C47__StandardFont.ttf": (
        (
            "scripts/r47_contracts/derive_key_font_policy.py",
            "scripts/r47_contracts/derive_key_visual_policy.py",
        ),
        (
            "scripts/r47_contracts/data/r47_key_font_policy_contract.json",
            "scripts/r47_contracts/test_key_font_policy_contract.py",
            "scripts/r47_contracts/test_key_visual_policy_contract.py",
        ),
    ),
    "res/fonts/C47__NumericFont.ttf": _FONT_POLICY_SURFACES,
    "res/fonts/C47__TinyFont.ttf": _FONT_POLICY_SURFACES,
}
_GOLDEN_SUFFIX: Final = ".json"
_TEST_MODULE_SUFFIX: Final = ".py"


def input_surfaces() -> dict[str, tuple[tuple[str, ...], tuple[str, ...]]]:
    """Return the derivers and contracts listed against each upstream input."""
    return dict(_INPUT_SURFACES)


def _declared_source_inputs() -> set[str]:
    # `_repo_paths` is the single place the suite names an upstream C file, so
    # introspecting it means a newly added UPSTREAM_R47_*_PATH cannot slip into
    # the suite without the coverage check noticing.
    declared: set[str] = set()
    for name, value in vars(_repo_paths).items():
        if not name.startswith(_UPSTREAM_PATH_PREFIX):
            continue
        if not name.endswith(_UPSTREAM_PATH_SUFFIX):
            continue
        declared.add(str(value.relative_to(REPO_ROOT)))
    return declared


def _declared_font_inputs() -> set[str]:
    # Imported lazily: derive_key_font_policy pulls fontTools, and the coverage
    # check should not need the font toolchain just to list asset names.
    from r47_contracts.derive_key_font_policy import (  # noqa: PLC0415
        _FONT_ASSETS,
    )

    return {"/".join((*_FONT_ROOT_PARTS, name)) for name in _FONT_ASSETS.values()}


def declared_upstream_inputs() -> set[str]:
    """Return every upstream input the contract suite reads, repo-relative."""
    return _declared_source_inputs() | _declared_font_inputs()


def hash_file(path: Path) -> str:
    """Return the sha256 of a file, or an empty string when it is absent."""
    if not path.is_file():
        return ""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(_READ_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def _read_key(path: Path, key: str, default: str) -> str:
    if not path.is_file():
        return default
    prefix = f"{key}="
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith(prefix):
            return line.partition("=")[2].strip() or default
    return default


def read_locked_upstream_commit() -> str:
    """Return the upstream commit pinned in the git-ignored upstream.lock."""
    return _read_key(UPSTREAM_LOCK_PATH, "upstream_commit", UNKNOWN_COMMIT)


def read_upstream_url() -> str:
    """Return the upstream URL from the tracked upstream.source."""
    return _read_key(UPSTREAM_SOURCE_PATH, "upstream_url", "")


def load_ledger(path: Path = LEDGER_PATH) -> Ledger:
    """Read and validate the provenance ledger."""
    if not path.is_file():
        raise ProvenanceError.unreadable(path, "file does not exist")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ProvenanceError.unreadable(path, str(error)) from error
    if not isinstance(payload, dict):
        raise ProvenanceError.unreadable(path, "top level is not an object")
    if payload.get("schema") != SCHEMA_VERSION:
        raise ProvenanceError.wrong_schema(path, payload.get("schema"))
    try:
        return LEDGER.ensure(payload)
    except ValidationError as error:
        raise ProvenanceError.unreadable(path, str(error)) from error


def build_ledger(upstream_url: str, upstream_commit: str) -> Ledger:
    """Build a ledger from the currently hydrated upstream tree."""
    inputs = [
        InputRecord(path=path, sha256=hash_file(REPO_ROOT / path))
        for path in sorted(declared_upstream_inputs())
    ]
    return Ledger(
        schema=SCHEMA_VERSION,
        recorded=RecordedSnapshot(
            upstream_url=upstream_url,
            upstream_commit=upstream_commit,
            recorded_on=datetime.now(tz=UTC).date().isoformat(),
        ),
        inputs=inputs,
    )


def evaluate(ledger: Ledger) -> list[Finding]:
    """Compare the ledger against the live tree and the declared input set."""
    declared = declared_upstream_inputs()
    recorded = {entry["path"]: entry for entry in ledger["inputs"]}
    findings: list[Finding] = []

    for path in sorted(declared | set(recorded)):
        entry = recorded.get(path)
        current = hash_file(REPO_ROOT / path)
        if entry is None:
            status: Status = "unrecorded"
        elif path not in declared:
            status = "stale-entry"
        elif not current:
            status = "missing"
        elif current == entry["sha256"]:
            status = "unchanged"
        else:
            status = "moved"
        # A retired input has no surface left to re-examine.
        _, contracts = _INPUT_SURFACES.get(path, ((), ()))
        findings.append(
            Finding(
                path=path,
                status=status,
                recorded_sha256=entry["sha256"] if entry else "",
                current_sha256=current,
                contracts=list(contracts),
            ),
        )
    return findings


def coverage_failures(findings: Sequence[Finding]) -> list[Finding]:
    """Return findings that mean the ledger no longer matches the suite."""
    return [
        finding
        for finding in findings
        if finding["status"] in {"unrecorded", "stale-entry"}
    ]


def drift_failures(findings: Sequence[Finding]) -> list[Finding]:
    """Return findings whose upstream bytes moved since the ledger was recorded."""
    return [finding for finding in findings if finding["status"] == "moved"]


def unaffected_record_refusal(
    findings: Sequence[Finding],
    *,
    golden_changed: Callable[[str], bool],
    contracts_pass: Callable[[Sequence[str]], bool],
) -> str | None:
    """Return why the moved inputs cannot be re-recorded as harmless, or None.

    A move is harmless when every golden it feeds is still the one HEAD holds
    and every test module that guards it passes on the live tree, which runs
    each golden's freshness guard against the moved bytes.
    """
    if coverage_failures(findings):
        return (
            "the ledger and the suite disagree about which upstream inputs exist;"
            " fix the coverage, then re-record with --record"
        )
    moved = drift_failures(findings)
    if not moved:
        return None
    unguarded = [
        finding["path"]
        for finding in moved
        if not any(c.endswith(_TEST_MODULE_SUFFIX) for c in finding["contracts"])
    ]
    if unguarded:
        return f"no test module guards {', '.join(unguarded)}"
    goldens = sorted(
        {c for f in moved for c in f["contracts"] if c.endswith(_GOLDEN_SUFFIX)},
    )
    changed = [golden for golden in goldens if golden_changed(golden)]
    if changed:
        return (
            f"{', '.join(changed)} differ from HEAD; record a re-blessed golden"
            " with --record in the commit that changes it"
        )
    tests = sorted(
        {c for f in moved for c in f["contracts"] if c.endswith(_TEST_MODULE_SUFFIX)},
    )
    if not contracts_pass(tests):
        return "a contract that guards a moved input fails on the live tree"
    return None


def _golden_differs_from_head(path: str) -> bool:
    git = shutil.which("git")
    if git is None:
        return True
    # Fixed arguments; the path comes from this module's own surface map.
    committed = subprocess.run(  # noqa: S603
        [git, "-C", str(REPO_ROOT), "show", f"HEAD:{path}"],
        capture_output=True,
        check=False,
    )
    current = REPO_ROOT / path
    return (
        committed.returncode != 0
        or not current.is_file()
        or current.read_bytes() != committed.stdout
    )


def _run_test_modules(paths: Sequence[str]) -> bool:
    names = [
        "r47_contracts." + path.rsplit("/", 1)[-1].removesuffix(_TEST_MODULE_SUFFIX)
        for path in paths
    ]
    suite = unittest.defaultTestLoader.loadTestsFromNames(names)
    result = unittest.TextTestRunner(stream=sys.stderr, verbosity=1).run(suite)
    return result.wasSuccessful() and result.testsRun > 0


def _hydrated(findings: Sequence[Finding]) -> bool:
    return any(finding["current_sha256"] for finding in findings)


def format_report(ledger: Ledger, findings: Sequence[Finding]) -> str:
    """Render the human-readable provenance report."""
    recorded = ledger["recorded"]
    current = read_locked_upstream_commit()
    lines = [
        "Upstream provenance report",
        "",
        (
            f"  ledger recorded at upstream {recorded['upstream_commit']}"
            f" on {recorded['recorded_on']}"
        ),
        f"  upstream.lock currently pins  {current}",
        "",
    ]

    if not _hydrated(findings):
        lines.extend(
            [
                "  Upstream is not hydrated, so no input could be hashed.",
                "  Run: bash ./scripts/upstream-sync/upstream.sh sync --auto",
                "",
            ],
        )

    for finding in findings:
        lines.append(f"  {finding['status']:<12} {finding['path']}")
        if finding["status"] == "moved":
            lines.append(f"{'':<16}recorded {finding['recorded_sha256'][:12]}")
            lines.append(f"{'':<16}current  {finding['current_sha256'][:12]}")
            lines.extend(
                f"{'':<16}re-examine {contract}" for contract in finding["contracts"]
            )

    moved = drift_failures(findings)
    coverage = coverage_failures(findings)
    lines.append("")
    if coverage:
        lines.append(
            f"  COVERAGE: {len(coverage)} input(s) out of sync with the suite."
            " Re-record the ledger.",
        )
    if moved:
        lines.append(
            f"  DRIFT: {len(moved)} upstream input(s) moved since"
            f" {recorded['upstream_commit'][:8]}. Root-cause each golden above"
            " before re-blessing it.",
        )
    if not moved and not coverage:
        lines.append("  Every recorded upstream input still matches the live tree.")
    lines.append("")
    return "\n".join(lines)


def write_ledger(ledger: Ledger, path: Path = LEDGER_PATH) -> None:
    """Write the ledger to disk with a stable, diff-friendly layout."""
    path.write_text(
        json.dumps(ledger, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Report or re-record the provenance of the upstream inputs that the "
            "R47 contract goldens derive from."
        ),
    )
    record = parser.add_mutually_exclusive_group()
    record.add_argument(
        "--record",
        action="store_true",
        help=(
            "Rewrite the ledger from the hydrated tree. A deliberate maintainer "
            "act: do it only after root-causing the drift it erases, in the "
            "commit that re-blesses the golden it moved."
        ),
    )
    record.add_argument(
        "--record-if-unaffected",
        action="store_true",
        help=(
            "Rewrite the ledger only when the drift changed nothing: every "
            "golden a moved input feeds matches HEAD and every test module "
            "that guards one passes. Prints the evidence for the commit body."
        ),
    )
    parser.add_argument(
        "--check-coverage",
        action="store_true",
        help=(
            "Exit non-zero when the ledger and the suite disagree about which "
            "upstream inputs exist. Safe to gate CI on."
        ),
    )
    parser.add_argument(
        "--check-drift",
        action="store_true",
        help=(
            "Also exit non-zero when an upstream input moved. Do NOT gate CI on "
            "this: the repo tracks upstream HEAD, so drift is the normal state."
        ),
    )
    parser.add_argument(
        "--upstream-commit",
        default="",
        help=(
            "Upstream commit to stamp with --record or --record-if-unaffected. "
            "Defaults to the pin in the git-ignored upstream.lock."
        ),
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    """Run the provenance report or re-record the ledger."""
    args = _parse_args(argv)

    if args.record:
        commit = args.upstream_commit or read_locked_upstream_commit()
        ledger = build_ledger(read_upstream_url(), commit)
        write_ledger(ledger)
        sys.stdout.write(
            f"Recorded {len(ledger['inputs'])} upstream input(s) at {commit}\n",
        )
        return 0

    if args.record_if_unaffected:
        return _record_if_unaffected(args.upstream_commit)

    ledger = load_ledger()
    findings = evaluate(ledger)
    sys.stdout.write(format_report(ledger, findings))

    if args.check_coverage and coverage_failures(findings):
        return 1
    if args.check_drift and drift_failures(findings):
        return 1
    return 0


def _record_if_unaffected(upstream_commit: str) -> int:
    ledger = load_ledger()
    findings = evaluate(ledger)
    moved = drift_failures(findings)
    if not moved and not coverage_failures(findings):
        sys.stdout.write("Nothing moved; the ledger is unchanged.\n")
        return 0
    commit = upstream_commit or read_locked_upstream_commit()
    if commit == UNKNOWN_COMMIT:
        sys.stderr.write(
            "Refusing: no upstream commit to stamp. Pin upstream.lock or pass"
            " --upstream-commit.\n",
        )
        return 1
    refusal = unaffected_record_refusal(
        findings,
        golden_changed=_golden_differs_from_head,
        contracts_pass=_run_test_modules,
    )
    if refusal is not None:
        sys.stderr.write(f"Refusing: {refusal}.\n")
        return 1
    write_ledger(build_ledger(read_upstream_url(), commit))
    recorded = ledger["recorded"]["upstream_commit"]
    lines = [f"Re-recorded at {commit}; moved since {recorded}, goldens unchanged:"]
    for finding in moved:
        lines.append(
            f"  {finding['path']}: {finding['recorded_sha256'][:12]}"
            f" -> {finding['current_sha256'][:12]}",
        )
        lines.extend(
            f"    {'unchanged' if c.endswith(_GOLDEN_SUFFIX) else 'passed'} {c}"
            for c in finding["contracts"]
        )
    sys.stdout.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
