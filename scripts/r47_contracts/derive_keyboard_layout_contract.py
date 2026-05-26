"""Derive the maintained R47 keyboard-layout contract from live repo sources."""

from __future__ import annotations

import json
import re
import sys
from typing import TYPE_CHECKING, Final

from r47_contracts._repo_paths import (
    ANDROID_CPP_ROOT,
    KEYPAD_FIXTURES_ROOT,
    REPO_ROOT,
    UPSTREAM_R47_ASSIGN_PATH,
    UPSTREAM_R47_ITEMS_PATH,
)

if TYPE_CHECKING:
    from pathlib import Path

EXPECTED_VARIANT_KEY_COUNT: Final = 37
R47_VARIANTS: Final[tuple[str, ...]] = (
    "R47",
    "R47bkfg",
    "R47fgbk",
    "R47fg_g",
)
ASSIGN_VARIANT_TABLE_NAMES: Final[dict[str, str]] = {
    "R47": "kbd_std_R47f_g",
    "R47bkfg": "kbd_std_R47bk_fg",
    "R47fgbk": "kbd_std_R47fg_bk",
    "R47fg_g": "kbd_std_R47fg_g",
}
REPRESENTATIVE_ASSIGN_KEYS: Final[tuple[int, ...]] = (51, 52, 61, 81, 84, 85)
REPRESENTATIVE_ANDROID_FIXTURES: Final[tuple[str, ...]] = (
    "default-keypad",
    "shift-f-preview",
    "shift-g-preview",
    "static-single-scene",
)
REPRESENTATIVE_ANDROID_KEY_CODES: Final[tuple[int, ...]] = (11, 12, 35, 36, 37)
FIXTURE_LABEL_SLOT_COUNT: Final = 5
JNI_DISPLAY_PATH: Final = ANDROID_CPP_ROOT / "jni_display.c"
_ASSIGN_TABLE_PATTERN: Final = re.compile(
    r"TO_QSPI const calcKey_t (?P<name>kbd_std_R47(?:f_g|bk_fg|fg_bk|fg_g))\[37\] = \{"
    r"(?P<body>.*?)"
    r"\n\};",
    re.DOTALL,
)
_ASSIGN_ROW_PATTERN: Final = re.compile(r"\{(?P<fields>[^{}]+)\}")
_ITEMS_SOFT_LABEL_PATTERNS: Final[dict[str, re.Pattern[str]]] = {
    "ITM_SHIFTf": re.compile(
        r"\{\s*fnSHIFTf\b[^}]*?\"f\"\s*,\s*\"(?P<label>f)\"\s*,",
        re.DOTALL,
    ),
    "ITM_SHIFTg": re.compile(
        r"\{\s*fnSHIFTg\b[^}]*?\"g\"\s*,\s*\"(?P<label>g)\"\s*,",
        re.DOTALL,
    ),
    "KEY_fg": re.compile(
        r"\{\s*fnSHIFTfg\b[^}]*?\"f/g\"\s*,\s*\"(?P<label>f/g)\"\s*,",
        re.DOTALL,
    ),
    "MNU_HOME": re.compile(
        r"\{\s*itemToBeCoded\b[^}]*?\"HOME\"\s*,\s*\"(?P<label>HOME)\"\s*,",
        re.DOTALL,
    ),
    "MNU_MyMenu": re.compile(
        r"\{\s*(?:fnBaseMenu|itemToBeCoded)\b[^}]*?\"MyMenu\"\s*,\s*\"(?P<label>MyM)\"\s*,",
        re.DOTALL,
    ),
    "ITM_XEQ": re.compile(
        r"\{\s*fnExecute\b[^}]*?\"XEQ\"\s*,\s*\"(?P<label>XEQ)\"\s*,",
        re.DOTALL,
    ),
    "ITM_GTO": re.compile(
        r"\{\s*fnGoto\b[^}]*?\"GTO\"\s*,\s*\"(?P<label>GTO)\"\s*,",
        re.DOTALL,
    ),
    "ITM_7": re.compile(
        r"\{\s*addItemToBuffer\b[^}]*?ITM_7\s*,\s*\"\"\s*,\s*\"(?P<label>7)\"\s*,",
        re.DOTALL,
    ),
    "ITM_sin": re.compile(
        r"\{\s*fnSin\b[^}]*?\"SIN\"\s*,\s*\"(?P<label>SIN)\"\s*,",
        re.DOTALL,
    ),
    "ITM_arcsin": re.compile(
        r"\{\s*fnArcsin\b[^}]*?\"ARCSIN\"\s*,\s*\"(?P<label>ASIN)\"\s*,",
        re.DOTALL,
    ),
    "ITM_EXIT1": re.compile(
        r"\{\s*fnKeyExit\b[^}]*?\"EXIT\"\s*,\s*\"(?P<label>EXIT)\"\s*,",
        re.DOTALL,
    ),
    "ITM_OFF": re.compile(
        r"\{\s*fnOff\b[^}]*?\"OFF\"\s*,\s*\"(?P<label>OFF)\"\s*,",
        re.DOTALL,
    ),
    "ITM_SNAP": re.compile(
        r"\{\s*fnSNAP\b[^}]*?\"SNAP\"\s*,\s*\"(?P<label>SNAP)\"\s*,",
        re.DOTALL,
    ),
    "ITM_RS": re.compile(
        r"\{\s*fnRunProgram\b[^}]*?\"R/S\"\s*,\s*\"(?P<label>R/S)\"\s*,",
        re.DOTALL,
    ),
    "ITM_PR": re.compile(
        r"\{\s*fnPem\b[^}]*?\"PRGM\"\s*,\s*\"(?P<label>PRGM)\"\s*,",
        re.DOTALL,
    ),
    "MNU_PFN": re.compile(
        r"\{\s*itemToBeCoded\b[^}]*?\"P\.FN\"\s*,\s*\"(?P<label>P\.FN)\"\s*,",
        re.DOTALL,
    ),
    "ITM_ADD": re.compile(
        r"\{\s*fnAdd\b[^}]*?\"\+\"\s*,\s*\"(?P<label>\+)\"\s*,",
        re.DOTALL,
    ),
    "MNU_CATALOG": re.compile(
        r"\{\s*itemToBeCoded\b[^}]*?\"CATALOG\"\s*,\s*\"(?P<label>CAT)\"\s*,",
        re.DOTALL,
    ),
    "MNU_CONST": re.compile(
        r"\{\s*itemToBeCoded\b[^}]*?\"CNST\"\s*,\s*\"(?P<label>CNST)\"\s*,",
        re.DOTALL,
    ),
    "ITM_SHOW": re.compile(
        r"\{\s*fnC47Show\b[^}]*?\"SHOW\"\s*,\s*\"(?P<label>SHOW)\"\s*,",
        re.DOTALL,
    ),
}
_STATIC_LABEL_PATTERN: Final = re.compile(
    r"if \([^)]*keyCode == (?P<key>\d+) && type == "
    r"(?P<label_type>KEYPAD_LABEL_[A-Z_]+)[^)]*\) \{\s*"
    r"return makeMainLabel\(\"(?P<label>[^\"]*)\", 0, false\);\s*\}",
    re.DOTALL,
)
_STATIC_LABEL_ASSIGN_PATTERN: Final = re.compile(
    r"if \([^)]*keyCode == (?P<key>\d+) && type == "
    r"(?P<label_type>KEYPAD_LABEL_[A-Z_]+)[^)]*\) \{\s*"
    r"\*label = makeMainLabel\(\"(?P<label>[^\"]*)\", 0, false\);\s*"
    r"return true;\s*\}",
    re.DOTALL,
)
_STATIC_EMPTY_PATTERN: Final = re.compile(
    r"if \([^)]*keyCode == (?P<key>\d+) && type == "
    r"(?P<label_type>KEYPAD_LABEL_[A-Z_]+)[^)]*\) \{\s*"
    r"return EMPTY_MAIN_LABEL;\s*\}",
    re.DOTALL,
)
_ANDROID_FORMATTER_ASSIST_PATTERNS: Final[dict[str, re.Pattern[str]]] = {
    "space_placeholder": re.compile(
        r"item == ITM_SPACE && strcmp\(utf8, \" \"\) == 0",
    ),
    "mode_hash_to_hash": re.compile(
        r"strcmp\(utf8, \"MODE#\"\) == 0",
    ),
    "linpol_to_lin": re.compile(
        r"strcmp\(utf8, \"LINPOL\"\) == 0",
    ),
    "slash_to_divide_glyph_in_tam": re.compile(
        r"presentation->showTamLabels.*?strcmp\(utf8, \"/\"\) == 0",
        re.DOTALL,
    ),
}


class KeyboardLayoutContractError(ValueError):
    """Raised when the keyboard-layout sources are missing required data."""


def _normalize_label(value: object) -> str:
    """Normalize serialized labels into stripped string values."""
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value).strip()


def load_assign_variant_rows(
    assign_path: Path = UPSTREAM_R47_ASSIGN_PATH,
) -> dict[str, dict[int, tuple[str, ...]]]:
    """Load the generated root assign tables for the R47 variant family."""
    assign_source = assign_path.read_text(encoding="utf-8")
    tables: dict[str, dict[int, tuple[str, ...]]] = {}
    for table_match in _ASSIGN_TABLE_PATTERN.finditer(assign_source):
        table_name = table_match.group("name")
        table_rows: dict[int, tuple[str, ...]] = {}
        for row_match in _ASSIGN_ROW_PATTERN.finditer(table_match.group("body")):
            fields = [field.strip() for field in row_match.group("fields").split(",")]
            key_code = int(fields[0])
            table_rows[key_code] = tuple(fields[1:])
        tables[table_name] = table_rows
    missing_tables = [
        table_name
        for table_name in ASSIGN_VARIANT_TABLE_NAMES.values()
        if table_name not in tables
    ]
    if missing_tables:
        message = f"Missing R47 assign tables: {', '.join(missing_tables)}"
        raise KeyboardLayoutContractError(message)
    return tables


def load_items_display_names(
    items_path: Path = UPSTREAM_R47_ITEMS_PATH,
) -> dict[str, str]:
    """Load the core-visible labels we rely on from items.c."""
    source = items_path.read_text(encoding="utf-8")
    display_names: dict[str, str] = {}
    for symbol_name, pattern in _ITEMS_SOFT_LABEL_PATTERNS.items():
        match = pattern.search(source)
        if match is None:
            message = f"Missing items.c display label for {symbol_name}"
            raise KeyboardLayoutContractError(message)
        display_names[symbol_name] = _normalize_label(match.group("label"))
    return display_names


def load_android_static_label_overrides(
    jni_display_path: Path = JNI_DISPLAY_PATH,
) -> dict[str, dict[str, str]]:
    """Load the Android-local static main-label overrides from jni_display.c."""
    source = jni_display_path.read_text(encoding="utf-8")
    overrides: dict[str, dict[str, str]] = {}
    for pattern in (
        _STATIC_LABEL_PATTERN,
        _STATIC_LABEL_ASSIGN_PATTERN,
        _STATIC_EMPTY_PATTERN,
    ):
        for match in pattern.finditer(source):
            key_code = match.group("key")
            label_type = match.group("label_type")
            label = match.groupdict().get("label", "")
            overrides.setdefault(key_code, {})[label_type] = label
    return overrides


def load_android_formatter_assists(
    jni_display_path: Path = JNI_DISPLAY_PATH,
) -> dict[str, bool]:
    """Load the explicit Android main-label formatter assists from jni_display.c."""
    source = jni_display_path.read_text(encoding="utf-8")
    return {
        assist_name: bool(pattern.search(source))
        for assist_name, pattern in _ANDROID_FORMATTER_ASSIST_PATTERNS.items()
    }


def load_android_fixture_expectations(
    fixtures_root: Path = KEYPAD_FIXTURES_ROOT,
) -> dict[str, dict[str, dict[str, str]]]:
    """Load representative Android-exported keypad fixture labels for app keys."""
    expectations: dict[str, dict[str, dict[str, str]]] = {}
    for fixture_name in REPRESENTATIVE_ANDROID_FIXTURES:
        fixture_path = fixtures_root / f"{fixture_name}.json"
        with fixture_path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
        labels = payload.get("labels")
        if not isinstance(labels, list):
            message = f"Fixture {fixture_name!r} is missing its labels array"
            raise KeyboardLayoutContractError(message)

        fixture_expectations: dict[str, dict[str, str]] = {}
        for key_code in REPRESENTATIVE_ANDROID_KEY_CODES:
            offset = (key_code - 1) * FIXTURE_LABEL_SLOT_COUNT
            slots = labels[offset : offset + FIXTURE_LABEL_SLOT_COUNT]
            if len(slots) != FIXTURE_LABEL_SLOT_COUNT:
                message = (
                    f"Fixture {fixture_name!r} does not contain all label slots for key"
                    f" {key_code}"
                )
                raise KeyboardLayoutContractError(message)
            fixture_expectations[str(key_code)] = {
                "primary_label": _normalize_label(slots[0]),
                "f_label": _normalize_label(slots[1]),
                "g_label": _normalize_label(slots[2]),
                "letter_label": _normalize_label(slots[3]),
                "aux_label": _normalize_label(slots[4]),
            }
        expectations[fixture_name] = fixture_expectations
    return expectations


def _resolve_items_soft_label(
    item_name: str,
    items_display_names: dict[str, str],
) -> str:
    """Resolve an assign-table item token into the core-visible items.c label."""
    if item_name == "ITM_NULL":
        return ""
    normalized_item_name = item_name.removeprefix("-")
    if normalized_item_name not in items_display_names:
        message = f"Missing items.c display label for {normalized_item_name}"
        raise KeyboardLayoutContractError(message)
    return items_display_names[normalized_item_name]


def build_keyboard_layout_contract_payload() -> dict[str, object]:
    """Build a JSON-friendly summary of the live R47 keyboard-layout contract."""
    assign_rows = load_assign_variant_rows()
    items_display_names = load_items_display_names()
    android_static_overrides = load_android_static_label_overrides()
    android_formatter_assists = load_android_formatter_assists()
    android_fixture_expectations = load_android_fixture_expectations()

    variant_key_counts = {
        variant_name: len(assign_rows[table_name])
        for variant_name, table_name in ASSIGN_VARIANT_TABLE_NAMES.items()
    }
    shift_legends = {
        variant_name: {
            "35": _resolve_items_soft_label(
                assign_rows[table_name][35][0],
                items_display_names,
            ),
            "36": _resolve_items_soft_label(
                assign_rows[table_name][36][0],
                items_display_names,
            ),
        }
        for variant_name, table_name in ASSIGN_VARIANT_TABLE_NAMES.items()
    }
    assign_shift_rows = {
        variant_name: {
            "35": assign_rows[table_name][35][0],
            "36": assign_rows[table_name][36][0],
        }
        for variant_name, table_name in ASSIGN_VARIANT_TABLE_NAMES.items()
    }
    representative_assign_rows = {
        str(key_code): {
            "primary": assign_rows[ASSIGN_VARIANT_TABLE_NAMES["R47"]][key_code][0],
            "f_shifted": assign_rows[ASSIGN_VARIANT_TABLE_NAMES["R47"]][key_code][1],
            "g_shifted": assign_rows[ASSIGN_VARIANT_TABLE_NAMES["R47"]][key_code][2],
        }
        for key_code in REPRESENTATIVE_ASSIGN_KEYS
    }

    return {
        "canonical_source": {
            "assign_path": str(UPSTREAM_R47_ASSIGN_PATH.relative_to(REPO_ROOT)),
            "items_path": str(UPSTREAM_R47_ITEMS_PATH.relative_to(REPO_ROOT)),
            "jni_display_path": str(JNI_DISPLAY_PATH.relative_to(REPO_ROOT)),
        },
        "variant_key_counts": variant_key_counts,
        "shift_legends": shift_legends,
        "assign_shift_rows": assign_shift_rows,
        "representative_assign_rows": representative_assign_rows,
        "core_display_names": items_display_names,
        "android_static_overrides": android_static_overrides,
        "android_formatter_assists": android_formatter_assists,
        "android_fixture_expectations": android_fixture_expectations,
    }


def main() -> int:
    """Print the current keyboard-layout contract payload as JSON."""
    json.dump(
        build_keyboard_layout_contract_payload(),
        sys.stdout,
        indent=2,
        sort_keys=True,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
