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
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Final, Literal, TypedDict

from r47_contracts import _repo_paths
from r47_contracts._repo_paths import DATA_ROOT, REPO_ROOT

if TYPE_CHECKING:
    from collections.abc import Sequence
    from pathlib import Path

LEDGER_PATH: Final = DATA_ROOT / "upstream_provenance.json"
UPSTREAM_LOCK_PATH: Final = REPO_ROOT / "upstream.lock"
UPSTREAM_SOURCE_PATH: Final = REPO_ROOT / "upstream.source"
SCHEMA_VERSION: Final = 1
UNKNOWN_COMMIT: Final = "unknown"

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


class InputRecord(TypedDict):
    """One upstream input and the contract surfaces that derive from it."""

    path: str
    sha256: str
    derivers: list[str]
    contracts: list[str]


class RecordedSnapshot(TypedDict):
    """The upstream watermark the ledger was recorded against."""

    upstream_url: str
    upstream_commit: str
    recorded_on: str


class Ledger(TypedDict):
    """The on-disk provenance ledger."""

    schema: int
    recorded: RecordedSnapshot
    inputs: list[InputRecord]


class Finding(TypedDict):
    """One evaluated upstream input."""

    path: str
    status: Status
    recorded_sha256: str
    current_sha256: str
    contracts: list[str]


# The contract surfaces each upstream input feeds. Keyed by repo-relative path.
# `derivers` is what reads the bytes; `contracts` is what a maintainer must
# re-examine when those bytes move. Both are repo-relative so the report is
# copy-pasteable into a shell.
_INPUT_SURFACES: Final[dict[str, tuple[tuple[str, ...], tuple[str, ...]]]] = {
    "src/c47/assign.c": (
        ("scripts/r47_contracts/derive_keyboard_layout_contract.py",),
        ("scripts/r47_contracts/data/r47_keyboard_layout_contract.json",),
    ),
    "src/c47/items.c": (
        ("scripts/r47_contracts/derive_keyboard_layout_contract.py",),
        ("scripts/r47_contracts/data/r47_keyboard_layout_contract.json",),
    ),
    "src/c47/programming/input.c": (
        ("scripts/r47_contracts/derive_live_stop_key_policy.py",),
        ("scripts/r47_contracts/test_live_stop_key_policy_contract.py",),
    ),
    "res/fonts/C47__StandardFont.ttf": (
        (
            "scripts/r47_contracts/derive_key_font_policy.py",
            "scripts/r47_contracts/derive_key_visual_policy.py",
            "scripts/r47_contracts/derive_top_label_lane_layout.py",
        ),
        ("scripts/r47_contracts/data/r47_key_font_policy_contract.json",),
    ),
    "res/fonts/C47__NumericFont.ttf": (
        ("scripts/r47_contracts/derive_key_font_policy.py",),
        ("scripts/r47_contracts/data/r47_key_font_policy_contract.json",),
    ),
    "res/fonts/C47__TinyFont.ttf": (
        ("scripts/r47_contracts/derive_key_font_policy.py",),
        ("scripts/r47_contracts/data/r47_key_font_policy_contract.json",),
    ),
}


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
    return cast_ledger(payload)


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value]


def cast_ledger(payload: dict[str, object]) -> Ledger:
    """Narrow a parsed JSON object to the ledger shape."""
    recorded = payload.get("recorded")
    inputs = payload.get("inputs")
    if not isinstance(recorded, dict) or not isinstance(inputs, list):
        raise ProvenanceError.unreadable(
            LEDGER_PATH,
            "missing a 'recorded' object or an 'inputs' array",
        )
    return Ledger(
        schema=SCHEMA_VERSION,
        recorded=RecordedSnapshot(
            upstream_url=str(recorded.get("upstream_url", "")),
            upstream_commit=str(recorded.get("upstream_commit", UNKNOWN_COMMIT)),
            recorded_on=str(recorded.get("recorded_on", "")),
        ),
        inputs=[
            InputRecord(
                path=str(entry.get("path", "")),
                sha256=str(entry.get("sha256", "")),
                derivers=_string_list(entry.get("derivers")),
                contracts=_string_list(entry.get("contracts")),
            )
            for entry in inputs
            if isinstance(entry, dict)
        ],
    )


def build_ledger(upstream_url: str, upstream_commit: str) -> Ledger:
    """Build a ledger from the currently hydrated upstream tree."""
    inputs: list[InputRecord] = []
    for path in sorted(declared_upstream_inputs()):
        derivers, contracts = _INPUT_SURFACES.get(path, ((), ()))
        inputs.append(
            InputRecord(
                path=path,
                sha256=hash_file(REPO_ROOT / path),
                derivers=list(derivers),
                contracts=list(contracts),
            ),
        )
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
        findings.append(
            Finding(
                path=path,
                status=status,
                recorded_sha256=entry["sha256"] if entry else "",
                current_sha256=current,
                contracts=list(entry["contracts"]) if entry else [],
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
    parser.add_argument(
        "--record",
        action="store_true",
        help=(
            "Rewrite the ledger from the hydrated tree. A deliberate maintainer "
            "act: do it only after root-causing the drift it erases."
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
            "Upstream commit to stamp with --record. Defaults to the pin in the "
            "git-ignored upstream.lock."
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

    ledger = load_ledger()
    findings = evaluate(ledger)
    sys.stdout.write(format_report(ledger, findings))

    if args.check_coverage and coverage_failures(findings):
        return 1
    if args.check_drift and drift_failures(findings):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
