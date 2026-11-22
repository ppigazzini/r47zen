package io.github.ppigazzini.r47zen

// Magnify a two-color bitmap 2x with MMPX, the style-preserving pixel-art filter
// of McGuire and Gagiu (JCGT 10(2), 2021). Each source pixel becomes a 2x2 block
// whose rules rebuild 1:1 and 2:1 diagonals and curves, keep sharp corners and
// single pixels, and never add a value the source lacks.
//
// A pixel is INK (1) or PAPER (0). MMPX breaks ties toward the darker pixel as
// foreground, so ink must rank darker: luma() puts it below paper whatever the
// palette later paints it. The rules run in the paper's Listing 4 order, and a
// later rule overwrites an earlier one: reordering them changes the output.
// scripts/r47_contracts/_mmpx.py is the same filter for the smooth key-label
// font; a change to the rules belongs in both.
//
// One instance holds the window of the pixel being magnified, so the hot loop
// allocates nothing; an instance is not safe to share between threads.
//
// The rule functions keep each of the paper's conditions clause for clause, so
// a reader can check them against Listing 4 line by line; splitting a condition
// to meet detekt's complexity limits would hide that correspondence.
@Suppress("ComplexCondition", "CyclomaticComplexMethod", "TooManyFunctions")
internal class LcdMmpx {
    private var source = ByteArray(0)
    private var width = 0
    private var height = 0
    private var x = 0
    private var y = 0

    // The 3x3 window a b c / d e f / g h i around e, then p and s two rows above
    // and below e, q and r two columns left and right of it.
    private var a = 0
    private var b = 0
    private var c = 0
    private var d = 0
    private var e = 0
    private var f = 0
    private var g = 0
    private var h = 0
    private var i = 0
    private var p = 0
    private var q = 0
    private var r = 0
    private var s = 0

    // The 2x2 output block: top-left, top-right, bottom-left, bottom-right.
    private var tl = 0
    private var tr = 0
    private var bl = 0
    private var br = 0

    // Write the 2x blocks of source rows firstRow..lastRow into target, which is
    // (2 * width) x (2 * height). A read past the bitmap's edge clamps to it.
    fun magnify(
        source: ByteArray,
        width: Int,
        height: Int,
        target: ByteArray,
        rows: IntRange,
    ) {
        this.source = source
        this.width = width
        this.height = height
        val targetWidth = width * 2
        for (row in rows.first.coerceAtLeast(0)..rows.last.coerceAtMost(height - 1)) {
            y = row
            for (column in 0 until width) {
                x = column
                magnifyPixel()
                val top = (2 * row) * targetWidth + 2 * column
                target[top] = tl.toByte()
                target[top + 1] = tr.toByte()
                target[top + targetWidth] = bl.toByte()
                target[top + targetWidth + 1] = br.toByte()
            }
        }
    }

    private fun at(dx: Int, dy: Int): Int {
        val column = (x + dx).coerceIn(0, width - 1)
        val row = (y + dy).coerceIn(0, height - 1)
        return source[row * width + column].toInt()
    }

    private fun magnifyPixel() {
        e = at(0, 0)
        tl = e
        tr = e
        bl = e
        br = e
        a = at(-1, -1)
        b = at(0, -1)
        c = at(1, -1)
        d = at(-1, 0)
        f = at(1, 0)
        g = at(-1, 1)
        h = at(0, 1)
        i = at(1, 1)
        // A pixel whose 3x3 window is one value has no feature to rebuild: it
        // stays a plain 2x2 block, as nearest-neighbor makes it.
        if (allEq(e, a, b, c, d) && allEq(e, f, g, h, i)) {
            return
        }
        p = at(0, -NEAR)
        q = at(-NEAR, 0)
        r = at(NEAR, 0)
        s = at(0, NEAR)
        slopeRules()
        intersectionRules()
        triangleTipRules()
        horizontalEdgeRules()
        verticalEdgeRules()
    }

    // Fixed-arity helpers: a vararg would allocate an array per call in the
    // per-pixel loop.
    private fun luma(value: Int): Int = 1 - value

    private fun allEq(v: Int, o1: Int, o2: Int): Boolean = v == o1 && v == o2

    private fun allEq(v: Int, o1: Int, o2: Int, o3: Int): Boolean = v == o1 && v == o2 && v == o3

    private fun allEq(v: Int, o1: Int, o2: Int, o3: Int, o4: Int): Boolean =
        v == o1 && v == o2 && v == o3 && v == o4

    private fun noneEq(v: Int, o1: Int, o2: Int): Boolean = v != o1 && v != o2

    private fun noneEq(v: Int, o1: Int, o2: Int, o3: Int): Boolean = v != o1 && v != o2 && v != o3

    private fun noneEq(v: Int, o1: Int, o2: Int, o3: Int, o4: Int): Boolean =
        v != o1 && v != o2 && v != o3 && v != o4

    private fun anyEq(v: Int, o1: Int, o2: Int, o3: Int): Boolean = v == o1 || v == o2 || v == o3

    private fun slopeRules() {
        val el = luma(e)
        if (slopeApplies(d, b, h, f, el >= luma(d) || e == a, anyEq(e, a, c, g)) &&
            (el < luma(d) || a != d || e != p || e != q)
        ) {
            tl = d
        }
        if (slopeApplies(b, f, d, h, el >= luma(b) || e == c, anyEq(e, a, c, i)) &&
            (el < luma(b) || c != b || e != p || e != r)
        ) {
            tr = b
        }
        if (slopeApplies(h, d, f, b, el >= luma(h) || e == g, anyEq(e, a, g, i)) &&
            (el < luma(h) || g != h || e != s || e != q)
        ) {
            bl = h
        }
        if (slopeApplies(f, h, b, d, el >= luma(f) || e == i, anyEq(e, c, g, i)) &&
            (el < luma(f) || i != h || e != r || e != s)
        ) {
            br = f
        }
    }

    // The first three clauses of a 1:1 slope rule: the two neighbors along the
    // slope agree and differ from the two across it.
    @Suppress("LongParameterList")
    private fun slopeApplies(
        along: Int,
        alongToo: Int,
        across: Int,
        acrossToo: Int,
        darkEnough: Boolean,
        cornerShared: Boolean,
    ): Boolean = along == alongToo && along != across && along != acrossToo && darkEnough && cornerShared

    private fun intersectionRules() {
        if (e != f && allEq(e, c, i, d, q) && allEq(f, b, h) && f != at(FAR, 0)) {
            tr = f
            br = f
        }
        if (e != d && allEq(e, a, g, f, r) && allEq(d, b, h) && d != at(-FAR, 0)) {
            tl = d
            bl = d
        }
        if (e != h && allEq(e, g, i, b, p) && allEq(h, d, f) && h != at(0, FAR)) {
            bl = h
            br = h
        }
        if (e != b && allEq(e, a, c, h, s) && allEq(b, d, f) && b != at(0, -FAR)) {
            tl = b
            tr = b
        }
    }

    private fun triangleTipRules() {
        val el = luma(e)
        if (luma(b) < el && allEq(e, g, h, i, s) && noneEq(e, a, d, c, f)) {
            tl = b
            tr = b
        }
        if (luma(h) < el && allEq(e, a, b, c, p) && noneEq(e, d, g, i, f)) {
            bl = h
            br = h
        }
        if (luma(f) < el && allEq(e, a, d, g, q) && noneEq(e, b, c, i, h)) {
            tr = f
            br = f
        }
        if (luma(d) < el && allEq(e, c, f, i, r) && noneEq(e, b, a, g, h)) {
            tl = d
            bl = d
        }
    }

    private fun horizontalEdgeRules() {
        if (h == b) {
            return
        }
        if (noneEq(h, a, e, c)) {
            if (allEq(h, g, f, r) && noneEq(h, d, at(NEAR, -1))) {
                bl = br
            }
            if (allEq(h, i, d, q) && noneEq(h, f, at(-NEAR, -1))) {
                br = bl
            }
        }
        if (noneEq(b, i, g, e)) {
            if (allEq(b, a, f, r) && noneEq(b, d, at(NEAR, 1))) {
                tl = tr
            }
            if (allEq(b, c, d, q) && noneEq(b, f, at(-NEAR, 1))) {
                tr = tl
            }
        }
    }

    private fun verticalEdgeRules() {
        if (f == d) {
            return
        }
        if (noneEq(d, i, e, c)) {
            if (allEq(d, a, h, s) && noneEq(d, b, at(1, NEAR))) {
                tl = bl
            }
            if (allEq(d, g, b, p) && noneEq(d, h, at(1, -NEAR))) {
                bl = tl
            }
        }
        if (noneEq(f, e, a, g)) {
            if (allEq(f, c, h, s) && noneEq(f, b, at(-1, NEAR))) {
                tr = br
            }
            if (allEq(f, i, b, p) && noneEq(f, h, at(-1, -NEAR))) {
                br = tr
            }
        }
    }

    companion object {
        const val INK: Byte = 1
        const val PAPER: Byte = 0

        // The farthest row or column the rules read from the pixel they
        // magnify. A change to one source row can change the blocks of the
        // rows this far above and below it, and no farther.
        const val REACH = 3
        private const val FAR = REACH

        // The distance of p, q, r, and s, and of the knight's-move reads of the
        // 2:1 edge rules.
        private const val NEAR = 2
    }
}
