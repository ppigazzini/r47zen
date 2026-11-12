"""Hold the PC keyboard's tables to upstream's GTK keyboard, the code they port.

`pc_keyboard.c` ports the R47 profile of the PC keyboard handler in
`src/c47-gtk/gtkGui.c`. Its logic is reviewed against that file; its data is checked
here, each check first failing on a seeded drift:

- each of its four row tables equals, field by field and in order, the R47 rows
  (`shortCutCommand` and `shortCutFNCommand` calls whose condition names
  `USER_R47`) of the gtkGui.c chain it copies, and no call in those chains is left
  unparsed;
- its keysym table names the items and keysyms of gtkGui.c's `gdkKeyMap`, in order,
  and its `deadKeysMap` equals gtkGui.c's;
- its Ctrl commands bind the letters gtkGui.c's Ctrl switch binds, to the same
  calls, but the image copies GTK's own `hal/lcd.c` defines, which the app has no
  counterpart for and the port's header names;
- each keysym `PcKeys.kt` sends is one it defines, with the same value;
- each `GDK_KEY_` value it defines, and each legacy keysym and code point of its
  keysym table, is X11's: a one-character name is that character's code, and the
  rest are checked against `keysymdef.h` on a host that has it (`x11proto-dev`).
  CI's runner has not, so there the table's values are reported skipped.
"""

from __future__ import annotations

import itertools
import re
import unittest
from pathlib import Path
from typing import Final

from r47_contracts._repo_paths import ANDROID_CPP_ROOT, KOTLIN_R47ZEN_ROOT, REPO_ROOT

_GTK: Final = REPO_ROOT / "src" / "c47-gtk" / "gtkGui.c"
_PORT: Final = ANDROID_CPP_ROOT / "pc_keyboard.c"
_KOTLIN: Final = KOTLIN_R47ZEN_ROOT / "PhysicalKeyboardInput.kt"
_KEYSYMDEF: Final = Path("/usr/include/X11/keysymdef.h")

# The chains, in gtkGui.c's order: where each begins, and the port's table for it.
_CHAINS: Final = (
    ("event_key_command = event_key_strip_capslock;", "r47PcCommandRows"),
    ("if(tam.mode == TM_STORCL) {", "r47PcStoRclRows"),
    ("else if((tamArrows) && !getSystemFlag(FLAG_ALPHA)) {", "r47PcTamArrowRows"),
    ("IS_SIM_ARROW_ALLOWED_IN_MENU(currentMenu(), event_keyval)", "r47PcMenuArrowRows"),
)
_CHAINS_END: Final = "//New ALPHA SECTION"
# What the app's build leaves undefined, so gtkGui.c's blocks under it are not the
# app's: RASPBERRY is set only by upstream's tools/onARaspberry.
_UNDEFINED: Final = frozenset({"RASPBERRY", "VERBOSEKEYS"})
_CONDITIONAL: Final = re.compile(r"^\s*#\s*(if|ifdef|ifndef|elif|else|endif)\b(.*)$")
_DEFINED: Final = re.compile(r"^\s*defined\((\w+)\)\s*(?://.*)?$")
_GTK_CALL: Final = re.compile(r"shortCut(FN)?Command\(w,")
_GTK_ROW: Final = re.compile(
    r"shortCut(FN)?Command\(w,\s*[\w>-]+,\s*(.+?)\s*(?:/\*.*?\*/)?\s*,(.*?)\)\)\s*\{",
)
_PORT_ROW: Final = re.compile(r"\b(FN_ROW|ROW)\(([^()]*)\),")
_PORT_CALL: Final = re.compile(r"\b(?:FN_ROW|ROW)\(")
_DISABLE: Final = {
    "tam.mode": "R47_PC_BY_TAM",
    "FALSE": "R47_PC_NEVER",
    "!DISABLED": "R47_PC_NEVER",
}
_SHIFT: Final = {'""': "0", '"f"': "'f'", '"g"': "'g'"}
_GTK_KEYMAP_ROW: Final = re.compile(
    r"^\s*\{\s*\.item\s*=\s*(\w+)\s*,\s*\.gdkKey\s*=\s*GDK_KEY_(\w+)\s*\}",
    re.MULTILINE,
)
_PORT_KEYSYM_ROW: Final = re.compile(
    r"\{0x([0-9a-f]+), 0x([0-9a-f]+), (\w+)\},\s*// (\w+)",
)
_DEFINE: Final = re.compile(r"^#define GDK_KEY_(\w+) 0x([0-9a-f]+)$", re.MULTILINE)
_KOTLIN_KEYSYM: Final = re.compile(
    r"^    const val (\w+) = 0x([0-9a-f]+)$",
    re.MULTILINE,
)
_X11_KEYSYM: Final = re.compile(
    r"^#define XK_(\w+)\s+0x([0-9a-fA-F]+)\s*(?:/\*\s*\(?U\+([0-9A-Fa-f]{4,6}))?",
    re.MULTILINE,
)
_LATIN1_END: Final = 0x100
_GTK_CTRL_START: Final = "Continue with old key detection using event_keyval"
_GTK_CTRL_CASE: Final = re.compile(r"case\s+(\w+)\s*\+\s*65536\s*:")
_PORT_CTRL_CASE: Final = re.compile(r"case\s+'(.)'\s*\+\s*65536\s*:")
_CTRL_CALL: Final = re.compile(r"^\s*(\w+)\((?:NOPARAM)?\);", re.MULTILINE)
_GTK_LCD: Final = REPO_ROOT / "src" / "c47-gtk" / "hal" / "lcd.c"
# The Ctrl copies the port leaves out: GTK draws each image in its own hal/lcd.c.
_CTRL_NOT_PORTED: Final = frozenset(
    {"copyScreenToClipboard", "copyMenuToClipboard", "copyRegisterXImageToClipboard"},
)
_KOTLIN_NOT_KEYSYMS: Final = frozenset({"UNICODE"})

Row = tuple[str, ...]


def _table(text: str, name: str) -> str:
    start = text.index(f"{name}[] = {{")
    return text[start : text.index("\n};", start)]


def compiled(body: str) -> str:
    """Return the lines the app compiles: a block under an undefined symbol goes.

    Any other conditional fails, so a new one gets a decision, not a guess.
    """
    kept: list[str] = []
    skipping: list[bool] = []
    for line in body.splitlines():
        match = _CONDITIONAL.match(line)
        if match is None:
            if not any(skipping):
                kept.append(line)
            continue
        directive, rest = match.groups()
        symbol = _DEFINED.match(rest)
        if directive == "if" and symbol and symbol.group(1) in _UNDEFINED:
            skipping.append(True)
        elif directive == "endif" and skipping:
            skipping.pop()
        elif directive == "else" and skipping:
            skipping[-1] = not skipping[-1]
        else:
            message = f"a gtkGui.c conditional the contract does not know: {line!r}"
            raise AssertionError(message)
    return "\n".join(kept)


def gtk_chains(text: str) -> list[list[Row]]:
    """Return the R47 rows the app's build compiles in each chain, as the port's."""
    starts = []
    at = 0
    for anchor, _ in _CHAINS:
        at = text.index(anchor, at)
        starts.append(at)
    starts.append(text.index(_CHAINS_END, at))
    chains = []
    for begin, end in itertools.pairwise(starts):
        body = compiled(text[begin:end])
        calls = _GTK_CALL.findall(body)
        rows = _GTK_ROW.findall(body)
        if len(rows) != len(calls):
            message = f"{len(calls) - len(rows)} gtkGui.c rows did not parse"
            raise AssertionError(message)
        chains.append([_normalize(*row) for row in rows if "USER_R47" in row[2]])
    return chains


def _normalize(fn: str, key: str, rest: str) -> Row:
    fields = [field.strip() for field in rest.split(",")]
    if fn:
        _, disable, shift, button, modes, mode2, item = fields
        exit_if_nim = "false"
    else:
        _, exit_if_nim, disable, shift, button, modes, mode2, item = fields
        exit_if_nim = "true" if exit_if_nim == "EXITIFNIM" else "false"
    return (
        "FN_ROW" if fn else "ROW",
        key,
        exit_if_nim,
        _DISABLE[disable],
        _SHIFT[shift],
        button,
        " ".join(modes.split()),
        mode2,
        item,
    )


def port_chains(text: str) -> list[list[Row]]:
    """Return the port's four row tables, failing on a row that did not parse."""
    chains = []
    for _, table in _CHAINS:
        body = _table(text, table)
        rows: list[Row] = [
            (str(kind), *(" ".join(field.split()) for field in str(args).split(",")))
            for kind, args in _PORT_ROW.findall(body)
        ]
        if len(rows) != len(_PORT_CALL.findall(body)):
            message = f"a row of {table} did not parse"
            raise AssertionError(message)
        chains.append(rows)
    return chains


def gtk_keymap(text: str) -> list[tuple[str, str]]:
    """Return gdkKeyMap's (item, keysym name) rows, in order."""
    body = _table(text, "const gdkKeyMap_t gdkKeyMap")
    return [(item, name) for item, name in _GTK_KEYMAP_ROW.findall(body) if item != "0"]


def port_keysyms(text: str) -> list[tuple[str, str, int, int]]:
    """Return the port's (item, keysym name, keysym, code point) rows, in order."""
    return [
        (item, name, int(keysym, 16), int(unicode, 16))
        for keysym, unicode, item, name in _PORT_KEYSYM_ROW.findall(
            _table(text, "r47PcKeysyms"),
        )
    ]


def dead_keys(text: str, table: str) -> list[list[str]]:
    """Return a deadKeysMap's rows as token lists."""
    return [
        [token.strip() for token in row.split(",")]
        for row in re.findall(r"\{([^{}]*)\}", _table(text, table))
    ]


def x11_keysyms() -> dict[str, tuple[int, int | None]] | None:
    """Return keysymdef.h's (value, code point) per name, or None without the file."""
    if not _KEYSYMDEF.exists():
        return None
    found: dict[str, tuple[int, int | None]] = {}
    for name, value, unicode in _X11_KEYSYM.findall(_KEYSYMDEF.read_text("utf-8")):
        found.setdefault(name, (int(value, 16), int(unicode, 16) if unicode else None))
    return found


def wrong_keysyms(
    keysyms: dict[str, int],
    x11: dict[str, tuple[int, int | None]] | None,
) -> list[str]:
    """Return each keysym that is not X11's, by its one character or keysymdef.h."""
    wrong = []
    for name, value in keysyms.items():
        expected = ord(name) if len(name) == 1 else None
        if x11 is not None and name in x11:
            expected = x11[name][0]
        if expected is not None and expected != value:
            wrong.append(f"{name}=0x{value:x}, X11 0x{expected:x}")
    return wrong


def kotlin_keysyms(text: str) -> dict[str, int]:
    """Return PcKeys' keysyms under the X11 names they spell, case and _ aside."""
    return {
        name.replace("_", "").lower(): int(value, 16)
        for name, value in _KOTLIN_KEYSYM.findall(text)
        if not name.endswith("_MASK") and name not in _KOTLIN_NOT_KEYSYMS
    }


def kotlin_mismatches(kotlin: dict[str, int], port: dict[str, int]) -> list[str]:
    """Return each Kotlin keysym the port does not define with the same value."""
    known = {name.replace("_", "").lower(): value for name, value in port.items()}
    return [
        f"{name}=0x{value:x}, the port 0x{known[name]:x}"
        if name in known
        else f"{name} is no keysym pc_keyboard.c defines"
        for name, value in kotlin.items()
        if known.get(name) != value
    ]


def _ctrl_blocks(body: str, case: re.Pattern[str]) -> dict[str, tuple[str, ...]]:
    """Return each Ctrl letter's calls, one block of cases per `break;`."""
    bound: dict[str, tuple[str, ...]] = {}
    for block in body.split("break;"):
        keys = case.findall(block)
        calls = tuple(c for c in _CTRL_CALL.findall(block) if c != "printf")
        for key in keys:
            bound[key] = calls
    return bound


def _letter(token: str) -> str:
    """Return the letter a gtkGui.c case names: `GDK_KEY_H`, or a code such as `83`."""
    if token.startswith("GDK_KEY_"):
        return token.removeprefix("GDK_KEY_")
    return chr(int(token))


def gtk_ctrl_commands(text: str) -> dict[str, tuple[str, ...]]:
    """Return the calls gtkGui.c's Ctrl switch makes, per letter."""
    start = text.index("switch(event_keyval) {", text.index(_GTK_CTRL_START))
    body = compiled(text[start : text.index("default:;", start)])
    return {_letter(k): v for k, v in _ctrl_blocks(body, _GTK_CTRL_CASE).items()}


def port_ctrl_commands(text: str) -> dict[str, tuple[str, ...]]:
    """Return the calls the port's runCtrlCommand makes, per letter."""
    start = text.index("static void runCtrlCommand(void) {")
    body = text[start : text.index("\n}\n", start)]
    return _ctrl_blocks(body, _PORT_CTRL_CASE)


def ctrl_drift(
    gtk: dict[str, tuple[str, ...]],
    port: dict[str, tuple[str, ...]],
) -> list[str]:
    """Return each letter whose calls differ, the image copies left out."""
    expected = {
        key: calls
        for key, calls in gtk.items()
        if not _CTRL_NOT_PORTED.intersection(calls)
    }
    return [
        f"Ctrl+{key}: port {port.get(key)}, gtkGui.c {expected.get(key)}"
        for key in sorted(expected.keys() | port.keys())
        if port.get(key) != expected.get(key)
    ]


def _sources() -> tuple[str, str, str]:
    return (
        _GTK.read_text(encoding="utf-8"),
        _PORT.read_text(encoding="utf-8"),
        _KOTLIN.read_text(encoding="utf-8"),
    )


class PcKeyboardRowsTest(unittest.TestCase):
    """The port's row tables are gtkGui.c's R47 rows."""

    def test_each_table_equals_its_gtk_chain(self) -> None:
        """Every row, field by field, in order, in each of the four chains."""
        gtk, port, _ = _sources()
        expected = gtk_chains(gtk)
        found = port_chains(port)
        if not all(expected) or not all(found):
            message = "a chain or a table parsed empty"
            raise AssertionError(message)
        if found != expected:
            lines = [
                f"{table}: port {set(ours) ^ set(theirs)} differ"
                for (_, table), ours, theirs in zip(
                    _CHAINS,
                    found,
                    expected,
                    strict=True,
                )
                if ours != theirs
            ]
            raise AssertionError("\n".join(lines))

    def test_a_seeded_row_drift_is_caught(self) -> None:
        """Seeded: the s row's key turned to c differs from gtkGui.c."""
        gtk, port, _ = _sources()
        seeded = port.replace("ROW(GDK_KEY_s,", "ROW(GDK_KEY_c,", 1)
        if port_chains(seeded) == gtk_chains(gtk):
            message = "a seeded row drift was not caught"
            raise AssertionError(message)

    def test_rows_the_app_does_not_compile_are_left_out(self) -> None:
        """Seeded: the RASPBERRY rows, read as if compiled, differ from the port."""
        gtk, port, _ = _sources()
        if "#if defined(RASPBERRY)" not in gtk:
            self.skipTest("gtkGui.c has no RASPBERRY block to seed with")
        unguarded = gtk.replace("#if defined(RASPBERRY)", "", 1)
        unguarded = unguarded.replace("#endif // RASPBERRY", "", 1)
        if port_chains(port) == gtk_chains(unguarded):
            message = "rows under an undefined symbol were taken as compiled"
            raise AssertionError(message)


class PcKeyboardTextTablesTest(unittest.TestCase):
    """The port's keysym and dead-key tables are gtkGui.c's."""

    def test_the_keysym_table_names_gdk_keymap(self) -> None:
        """Items and keysym names, in gdkKeyMap's order."""
        gtk, port, _ = _sources()
        expected = gtk_keymap(gtk)
        found = [(item, name) for item, name, _, _ in port_keysyms(port)]
        if not expected or found != expected:
            message = (
                f"keysym table drift: {len(found)} rows, gdkKeyMap has {len(expected)}"
            )
            raise AssertionError(message)
        seeded = found[1:]
        if seeded == expected:
            message = "a seeded missing row was not caught"
            raise AssertionError(message)

    def test_the_dead_key_table_is_gtks(self) -> None:
        """Every row and token of deadKeysMap."""
        gtk, port, _ = _sources()
        expected = dead_keys(gtk, "const deadKeysMap_t deadKeysMap")
        found = dead_keys(port, "deadKeysMap")
        if not expected or found != expected:
            message = "deadKeysMap drift between pc_keyboard.c and gtkGui.c"
            raise AssertionError(message)
        seeded = [row[:] for row in found]
        seeded[0][1] = seeded[0][2]
        if seeded == expected:
            message = "a seeded dead key drift was not caught"
            raise AssertionError(message)


class PcKeyboardCtrlCommandsTest(unittest.TestCase):
    """The port's Ctrl commands are gtkGui.c's, but the image copies."""

    def test_each_ctrl_letter_makes_gtks_calls(self) -> None:
        """Every letter of both switches, and the calls each one makes."""
        gtk, port, _ = _sources()
        expected = gtk_ctrl_commands(gtk)
        found = port_ctrl_commands(port)
        if not expected or not found:
            message = "a Ctrl switch parsed empty"
            raise AssertionError(message)
        drift = ctrl_drift(expected, found)
        if drift:
            raise AssertionError("\n".join(drift))
        seeded = port_ctrl_commands(
            port.replace("case 'J' + 65536:", "case 'B' + 65536:", 1),
        )
        if not ctrl_drift(expected, seeded):
            message = "a seeded Ctrl letter drift was not caught"
            raise AssertionError(message)

    def test_each_copy_left_out_is_gtks_own_and_named(self) -> None:
        """Each image copy left out is defined in GTK's hal/lcd.c and named."""
        gtk, port, _ = _sources()
        lcd = _GTK_LCD.read_text(encoding="utf-8")
        made = {call for calls in gtk_ctrl_commands(gtk).values() for call in calls}
        header = port[: port.index("#include")]
        wrong = [
            call
            for call in sorted(_CTRL_NOT_PORTED)
            if call not in made
            or not re.search(rf"^void {call}\(void\) \{{", lcd, re.MULTILINE)
        ]
        if wrong:
            message = f"left out but not GTK's own Ctrl image copy: {wrong}"
            raise AssertionError(message)
        letters = sorted(
            key
            for key, calls in gtk_ctrl_commands(gtk).items()
            if key.isupper() and _CTRL_NOT_PORTED.intersection(calls)
        )
        unnamed = [
            key for key in letters if f"Ctrl+{key}" not in " ".join(header.split())
        ]
        if unnamed:
            named = ", ".join(f"Ctrl+{key}" for key in unnamed)
            message = f"pc_keyboard.c's header does not name {named}"
            raise AssertionError(message)


class PcKeyboardKeysymsTest(unittest.TestCase):
    """Every keysym the port defines and Kotlin sends is X11's."""

    def test_the_port_defines_x11_keysyms(self) -> None:
        """Each GDK_KEY_ define, by its character or keysymdef.h."""
        _, port, _ = _sources()
        defines = {name: int(value, 16) for name, value in _DEFINE.findall(port)}
        x11 = x11_keysyms()
        if not defines:
            message = "no GDK_KEY_ define parsed"
            raise AssertionError(message)
        wrong = wrong_keysyms(defines, x11)
        if wrong:
            raise AssertionError("\n".join(wrong))
        if not wrong_keysyms({**defines, "a": ord("b")}, x11):
            message = "a seeded wrong keysym was not caught"
            raise AssertionError(message)

    def test_the_keysym_table_holds_x11_values(self) -> None:
        """Each keysym and code point, against keysymdef.h."""
        x11 = x11_keysyms()
        if x11 is None:
            self.skipTest(f"{_KEYSYMDEF} is not on this host; the names were checked")
        _, port, _ = _sources()
        wrong = [
            f"{name}: 0x{keysym:x} U+{unicode:04x}, X11 {x11.get(name)}"
            for _, name, keysym, unicode in port_keysyms(port)
            if x11.get(name, (None, None))[0] != keysym
            or (x11[name][1] or (keysym if keysym < _LATIN1_END else None)) != unicode
        ]
        if wrong:
            raise AssertionError("\n".join(wrong))

    def test_kotlin_sends_the_ports_keysyms(self) -> None:
        """Each PcKeys keysym is one the port defines, with the same value."""
        _, port, kotlin = _sources()
        defines = {name: int(value, 16) for name, value in _DEFINE.findall(port)}
        sent = kotlin_keysyms(kotlin)
        if not sent:
            message = "no PcKeys keysym parsed"
            raise AssertionError(message)
        mismatched = kotlin_mismatches(sent, defines)
        if mismatched:
            raise AssertionError("\n".join(mismatched))
        if not kotlin_mismatches({**sent, "up": 0xFF51}, defines):
            message = "a seeded wrong Kotlin keysym was not caught"
            raise AssertionError(message)


if __name__ == "__main__":
    unittest.main()
