"""Magnify a two-color bitmap 2x with MMPX.

MMPX is the style-preserving pixel-art filter of McGuire and Gagiu, "MMPX
Style-Preserving Pixel Art Magnification", JCGT 10(2), 2021. Each source pixel
becomes a 2x2 block. Its rules rebuild 1:1 and 2:1 diagonals and curves, keep
sharp corners and single-pixel features, and never introduce a color the
source lacks.

This port takes ink as 1 and paper as 0. MMPX breaks ties toward the darker
pixel as foreground, so ink must be the darker value: `_luma` ranks it below
paper. A bitmap whose ink is the lighter color, an inverted label, must be
inverted before the call and after it.

The rules follow the paper's Listing 4 in order, and later rules overwrite
earlier ones: reordering them changes the output.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Callable, Sequence

type Bitmap = list[list[int]]
type _Block = tuple[int, int, int, int]


@dataclass(frozen=True, slots=True)
class _Window:
    """The neighborhood of source pixel e, lettered as the paper letters it.

    a b c on the row above, d e f on e's row, g h i on the row below; p and s
    two rows above and below e, q and r two columns left and right of it.
    """

    a: int
    b: int
    c: int
    d: int
    e: int
    f: int
    g: int
    h: int
    i: int
    p: int
    q: int
    r: int
    s: int


def _luma(value: int) -> int:
    # Ink is the dark, foreground value: it must rank below paper.
    return 1 - value


def _all_eq(first: int, *others: int) -> bool:
    return all(other == first for other in others)


def _none_eq(first: int, *others: int) -> bool:
    return all(other != first for other in others)


def _slope_rules(w: _Window, block: _Block) -> _Block:
    tl, tr, bl, br = block
    el = _luma(w.e)
    if (
        (w.d == w.b and w.d not in (w.h, w.f))
        and (el >= _luma(w.d) or w.e == w.a)
        and w.e in (w.a, w.c, w.g)
        and (el < _luma(w.d) or w.a != w.d or w.e != w.p or w.e != w.q)
    ):
        tl = w.d
    if (
        (w.b == w.f and w.b not in (w.d, w.h))
        and (el >= _luma(w.b) or w.e == w.c)
        and w.e in (w.a, w.c, w.i)
        and (el < _luma(w.b) or w.c != w.b or w.e != w.p or w.e != w.r)
    ):
        tr = w.b
    if (
        (w.h == w.d and w.h not in (w.f, w.b))
        and (el >= _luma(w.h) or w.e == w.g)
        and w.e in (w.a, w.g, w.i)
        and (el < _luma(w.h) or w.g != w.h or w.e != w.s or w.e != w.q)
    ):
        bl = w.h
    if (
        (w.f == w.h and w.f not in (w.b, w.d))
        and (el >= _luma(w.f) or w.e == w.i)
        and w.e in (w.c, w.g, w.i)
        and (el < _luma(w.f) or w.i != w.h or w.e != w.r or w.e != w.s)
    ):
        br = w.f
    return tl, tr, bl, br


def _intersection_rules(
    w: _Window,
    block: _Block,
    far: Callable[[int, int], int],
) -> _Block:
    tl, tr, bl, br = block
    if (
        w.e != w.f
        and _all_eq(w.e, w.c, w.i, w.d, w.q)
        and _all_eq(w.f, w.b, w.h)
        and w.f != far(3, 0)
    ):
        tr = br = w.f
    if (
        w.e != w.d
        and _all_eq(w.e, w.a, w.g, w.f, w.r)
        and _all_eq(w.d, w.b, w.h)
        and w.d != far(-3, 0)
    ):
        tl = bl = w.d
    if (
        w.e != w.h
        and _all_eq(w.e, w.g, w.i, w.b, w.p)
        and _all_eq(w.h, w.d, w.f)
        and w.h != far(0, 3)
    ):
        bl = br = w.h
    if (
        w.e != w.b
        and _all_eq(w.e, w.a, w.c, w.h, w.s)
        and _all_eq(w.b, w.d, w.f)
        and w.b != far(0, -3)
    ):
        tl = tr = w.b
    return tl, tr, bl, br


def _triangle_tip_rules(w: _Window, block: _Block) -> _Block:
    tl, tr, bl, br = block
    el = _luma(w.e)
    if (
        _luma(w.b) < el
        and _all_eq(w.e, w.g, w.h, w.i, w.s)
        and _none_eq(w.e, w.a, w.d, w.c, w.f)
    ):
        tl = tr = w.b
    if (
        _luma(w.h) < el
        and _all_eq(w.e, w.a, w.b, w.c, w.p)
        and _none_eq(w.e, w.d, w.g, w.i, w.f)
    ):
        bl = br = w.h
    if (
        _luma(w.f) < el
        and _all_eq(w.e, w.a, w.d, w.g, w.q)
        and _none_eq(w.e, w.b, w.c, w.i, w.h)
    ):
        tr = br = w.f
    if (
        _luma(w.d) < el
        and _all_eq(w.e, w.c, w.f, w.i, w.r)
        and _none_eq(w.e, w.b, w.a, w.g, w.h)
    ):
        tl = bl = w.d
    return tl, tr, bl, br


def _horizontal_edge_rules(
    w: _Window,
    block: _Block,
    far: Callable[[int, int], int],
) -> _Block:
    tl, tr, bl, br = block
    if w.h == w.b:
        return tl, tr, bl, br
    if w.h not in (w.a, w.e, w.c):
        if _all_eq(w.h, w.g, w.f, w.r) and _none_eq(w.h, w.d, far(2, -1)):
            bl = br
        if _all_eq(w.h, w.i, w.d, w.q) and _none_eq(w.h, w.f, far(-2, -1)):
            br = bl
    if w.b not in (w.i, w.g, w.e):
        if _all_eq(w.b, w.a, w.f, w.r) and _none_eq(w.b, w.d, far(2, 1)):
            tl = tr
        if _all_eq(w.b, w.c, w.d, w.q) and _none_eq(w.b, w.f, far(-2, 1)):
            tr = tl
    return tl, tr, bl, br


def _vertical_edge_rules(
    w: _Window,
    block: _Block,
    far: Callable[[int, int], int],
) -> _Block:
    tl, tr, bl, br = block
    if w.f == w.d:
        return tl, tr, bl, br
    if w.d not in (w.i, w.e, w.c):
        if _all_eq(w.d, w.a, w.h, w.s) and _none_eq(w.d, w.b, far(1, 2)):
            tl = bl
        if _all_eq(w.d, w.g, w.b, w.p) and _none_eq(w.d, w.h, far(1, -2)):
            bl = tl
    if w.f not in (w.e, w.a, w.g):
        if _all_eq(w.f, w.c, w.h, w.s) and _none_eq(w.f, w.b, far(-1, 2)):
            tr = br
        if _all_eq(w.f, w.i, w.b, w.p) and _none_eq(w.f, w.h, far(-1, -2)):
            br = tr
    return tl, tr, bl, br


def magnify_2x(source: Sequence[Sequence[int]]) -> Bitmap:
    """Return `source` magnified 2x by MMPX; reads past an edge clamp to it."""
    height = len(source)
    width = len(source[0]) if height else 0
    out: Bitmap = [[0] * (2 * width) for _ in range(2 * height)]

    def at(x: int, y: int) -> int:
        return source[min(max(y, 0), height - 1)][min(max(x, 0), width - 1)]

    for y in range(height):
        for x in range(width):
            e = at(x, y)
            neighbors = (
                at(x - 1, y - 1),
                at(x, y - 1),
                at(x + 1, y - 1),
                at(x - 1, y),
                at(x + 1, y),
                at(x - 1, y + 1),
                at(x, y + 1),
                at(x + 1, y + 1),
            )
            block: _Block = (e, e, e, e)
            # A pixel whose 3x3 neighborhood is one color has no feature to
            # rebuild: it stays a plain 2x2 block, as nearest-neighbor makes it.
            if any(value != e for value in neighbors):
                a, b, c, d, f, g, h, i = neighbors
                w = _Window(
                    a=a,
                    b=b,
                    c=c,
                    d=d,
                    e=e,
                    f=f,
                    g=g,
                    h=h,
                    i=i,
                    p=at(x, y - 2),
                    q=at(x - 2, y),
                    r=at(x + 2, y),
                    s=at(x, y + 2),
                )

                def far(dx: int, dy: int, x: int = x, y: int = y) -> int:
                    return at(x + dx, y + dy)

                block = _slope_rules(w, block)
                block = _intersection_rules(w, block, far)
                block = _triangle_tip_rules(w, block)
                block = _horizontal_edge_rules(w, block, far)
                block = _vertical_edge_rules(w, block, far)
            tl, tr, bl, br = block
            out[2 * y][2 * x] = tl
            out[2 * y][2 * x + 1] = tr
            out[2 * y + 1][2 * x] = bl
            out[2 * y + 1][2 * x + 1] = br
    return out
