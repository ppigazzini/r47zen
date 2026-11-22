package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

// The expected blocks come from scripts/r47_contracts/_mmpx.py, the same filter
// the smooth key-label font is derived with, so these hold the two ports to one
// output. Ink is '#', paper '.'.
class LcdMmpxTest {
    @Test
    fun uniformBitmap_magnifiesToPlainBlocks() {
        val source = listOf("....", "....", "####", "####")

        assertEquals(nearest(source), magnify(source))
    }

    @Test
    fun singlePixel_staysAPlainBlock() {
        val source = listOf(".....", ".....", "..#..", ".....", ".....")

        assertEquals(nearest(source), magnify(source))
    }

    @Test
    fun oneToOneDiagonal_isRebuiltAsAContinuousStroke() {
        val source = listOf("#.....", ".#....", "..#...", "...#..", "....#.", ".....#")

        assertEquals(
            listOf(
                "##..........", "#.#.........", ".###........", "..###.......",
                "...###......", "....###.....", ".....###....", "......###...",
                ".......###..", "........###.", ".........#.#", "..........##",
            ),
            magnify(source),
        )
    }

    @Test
    fun twoToOneSlope_isRebuiltAsAContinuousStroke() {
        val source = listOf("##......", "..##....", "....##..", "......##")

        assertEquals(
            listOf(
                "####............", "###.#...........", "..######........", "....######......",
                "......######....", "........######..", "...........#.###", "............####",
            ),
            magnify(source),
        )
    }

    @Test
    fun calculatorSeven_keepsItsCornerAndSmoothsItsStem() {
        // The 7 of C47__StandardFont.ttf, on its design grid.
        val source = listOf(
            "........", ".######.", ".######.", "....###.", "....##..", "....##..", "...##...",
            "...##...", "...##...", "..##....", "..##....", "..##....", "..##....", "........",
        )

        assertEquals(
            listOf(
                "................", "................", "..############..", "..############..",
                "..############..", "..############..", "........######..", "........######..",
                "........#####...", "........#####...", ".......#####....", ".......#####....",
                "......#####.....", "......#####.....", "......####......", "......####......",
                ".....#####......", ".....#####......", "....#####.......", "....#####.......",
                "....####........", "....####........", "....####........", "....####........",
                "....####........", "....####........", "................", "................",
            ),
            magnify(source),
        )
    }

    @Test
    fun magnify_writesOnlyTheRowsItIsGiven() {
        val source = listOf("#...", ".#..", "..#.", "...#")
        val bitmap = toBytes(source)
        val target = ByteArray(bitmap.size * 4) { UNTOUCHED }

        LcdMmpx().magnify(bitmap, widthOf(source), source.size, target, 1..2)

        val rows = toRows(target, widthOf(source) * 2)
        assertEquals(listOf("????????", "????????", "????????", "????????"), listOf(rows[0], rows[1], rows[6], rows[7]))
        assertEquals(magnify(source).subList(2, 6), rows.subList(2, 6))
    }

    private fun magnify(source: List<String>): List<String> {
        val target = ByteArray(source.size * source[0].length * 4)
        LcdMmpx().magnify(toBytes(source), widthOf(source), source.size, target, source.indices)
        return toRows(target, widthOf(source) * 2)
    }

    private fun nearest(source: List<String>): List<String> =
        source.flatMap { row -> List(2) { row.flatMap { pixel -> listOf(pixel, pixel) }.joinToString("") } }

    private fun toBytes(rows: List<String>): ByteArray =
        rows.joinToString("").map { if (it == '#') LcdMmpx.INK else LcdMmpx.PAPER }.toByteArray()

    private fun toRows(bytes: ByteArray, width: Int): List<String> =
        bytes.toList().chunked(width).map { row ->
            row.joinToString("") {
                when (it) {
                    LcdMmpx.INK -> "#"
                    LcdMmpx.PAPER -> "."
                    else -> "?"
                }
            }
        }

    private companion object {
        const val UNTOUCHED: Byte = 9

        fun widthOf(rows: List<String>): Int = rows[0].length
    }
}
