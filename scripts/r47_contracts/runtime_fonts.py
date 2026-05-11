"""Resolve the runtime font assets used by the R47 Python contract suite."""

from __future__ import annotations

from typing import TYPE_CHECKING

from r47_contracts._repo_paths import REPO_ROOT

if TYPE_CHECKING:
    from pathlib import Path


class RuntimeFontPathError(FileNotFoundError):
    """Raised when a required runtime font asset cannot be located."""

    @classmethod
    def missing_font(
        cls,
        font_name: str,
        candidates: tuple[Path, ...],
    ) -> RuntimeFontPathError:
        """Build an error for a missing runtime font asset."""
        search_roots = ", ".join(
            str(candidate.relative_to(REPO_ROOT)) for candidate in candidates
        )
        message = (
            f"Could not find {font_name} under any known runtime font root: "
            f"{search_roots}"
        )
        return cls(message)


def _runtime_font_candidates(font_name: str) -> tuple[Path, ...]:
    return (
        REPO_ROOT / "res" / "fonts" / font_name,
        REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "fonts" / font_name,
        REPO_ROOT
        / "android"
        / "app"
        / "build"
        / "generated"
        / "assets"
        / "runtime"
        / "fonts"
        / font_name,
    )


def resolve_runtime_font_path(font_name: str) -> Path:
    """Return the first existing runtime font path for the requested asset name."""
    candidates = _runtime_font_candidates(font_name)
    for candidate in candidates:
        if candidate.is_file():
            return candidate

    raise RuntimeFontPathError.missing_font(font_name, candidates)
