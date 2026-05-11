"""Load canonical physical-R47 and Android-app contract data from JSON."""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

from r47_contracts._repo_paths import (
    R47_ANDROID_UI_CONTRACT_PATH,
    R47_PHYSICAL_GEOMETRY_DATA_PATH,
)

if TYPE_CHECKING:
    from pathlib import Path


class ContractDataError(ValueError):
    """Raised when the canonical contract JSON is missing required data."""


def require_mapping(value: object, *, label: str) -> dict[str, object]:
    """Return a JSON object or raise a contract-data error."""
    if not isinstance(value, dict):
        message = f"Expected {label} to be an object, got {value!r}"
        raise ContractDataError(message)
    return {
        require_string(key, label=f"{label}.key"): nested_value
        for key, nested_value in value.items()
    }


def require_string(value: object, *, label: str) -> str:
    """Return a non-empty string or raise a contract-data error."""
    if not isinstance(value, str) or not value:
        message = f"Expected {label} to be a non-empty string, got {value!r}"
        raise ContractDataError(message)
    return value


def require_number(value: object, *, label: str) -> float:
    """Return a numeric JSON value as float or raise a contract-data error."""
    if isinstance(value, bool) or not isinstance(value, int | float):
        message = f"Expected {label} to be numeric, got {value!r}"
        raise ContractDataError(message)
    return float(value)


def mapping_member(
    mapping: dict[str, object],
    key: str,
    *,
    label: str,
) -> dict[str, object]:
    """Return a required mapping member from a loaded contract mapping."""
    return require_mapping(mapping.get(key), label=f"{label}.{key}")


def string_member(mapping: dict[str, object], key: str, *, label: str) -> str:
    """Return a required string member from a loaded contract mapping."""
    return require_string(mapping.get(key), label=f"{label}.{key}")


def number_member(mapping: dict[str, object], key: str, *, label: str) -> float:
    """Return a required numeric member from a loaded contract mapping."""
    return require_number(mapping.get(key), label=f"{label}.{key}")


def load_contract_document(
    path: Path,
) -> dict[str, object]:
    """Load the canonical R47 contract JSON document."""
    with path.open("r", encoding="utf-8") as handle:
        payload = json.load(handle)
    return require_mapping(payload, label="geometry document")


def load_physical_geometry(
    path: Path = R47_PHYSICAL_GEOMETRY_DATA_PATH,
) -> dict[str, object]:
    """Load the canonical measured R47 physical geometry document."""
    return load_contract_document(path)


def load_android_ui_contract(
    path: Path = R47_ANDROID_UI_CONTRACT_PATH,
) -> dict[str, object]:
    """Load the canonical Android UI geometry and policy document."""
    return load_contract_document(path)
