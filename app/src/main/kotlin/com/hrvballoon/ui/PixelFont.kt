package com.hrvballoon.ui

/** 4×6 pixel font: digits, upper-case letters and a few symbols; unknown characters are blank. Advance 5 px. */
object PixelFont {
    const val H = 6
    const val ADVANCE = 5

    private val blank = Array(H) { "0000" }
    private val glyphs: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf("0110", "1001", "1001", "1001", "1001", "0110"),
        '1' to arrayOf("0010", "0110", "0010", "0010", "0010", "0111"),
        '2' to arrayOf("0110", "1001", "0001", "0010", "0100", "1111"),
        '3' to arrayOf("1110", "0001", "0110", "0001", "0001", "1110"),
        '4' to arrayOf("1001", "1001", "1111", "0001", "0001", "0001"),
        '5' to arrayOf("1111", "1000", "1110", "0001", "0001", "1110"),
        '6' to arrayOf("0110", "1000", "1110", "1001", "1001", "0110"),
        '7' to arrayOf("1111", "0001", "0010", "0100", "0100", "0100"),
        '8' to arrayOf("0110", "1001", "0110", "1001", "1001", "0110"),
        '9' to arrayOf("0110", "1001", "0111", "0001", "0001", "0110"),
        'A' to arrayOf("0110", "1001", "1001", "1111", "1001", "1001"),
        'B' to arrayOf("1110", "1001", "1110", "1001", "1001", "1110"),
        'C' to arrayOf("0110", "1001", "1000", "1000", "1001", "0110"),
        'D' to arrayOf("1110", "1001", "1001", "1001", "1001", "1110"),
        'E' to arrayOf("1111", "1000", "1110", "1000", "1000", "1111"),
        'F' to arrayOf("1111", "1000", "1110", "1000", "1000", "1000"),
        'G' to arrayOf("0110", "1001", "1000", "1011", "1001", "0111"),
        'H' to arrayOf("1001", "1001", "1111", "1001", "1001", "1001"),
        'I' to arrayOf("0111", "0010", "0010", "0010", "0010", "0111"),
        'J' to arrayOf("0001", "0001", "0001", "0001", "1001", "0110"),
        'K' to arrayOf("1001", "1010", "1100", "1100", "1010", "1001"),
        'L' to arrayOf("1000", "1000", "1000", "1000", "1000", "1111"),
        'M' to arrayOf("1001", "1111", "1111", "1001", "1001", "1001"),
        'N' to arrayOf("1001", "1101", "1101", "1011", "1011", "1001"),
        'O' to arrayOf("0110", "1001", "1001", "1001", "1001", "0110"),
        'P' to arrayOf("1110", "1001", "1001", "1110", "1000", "1000"),
        'Q' to arrayOf("0110", "1001", "1001", "1001", "1010", "0101"),
        'R' to arrayOf("1110", "1001", "1001", "1110", "1010", "1001"),
        'S' to arrayOf("0111", "1000", "0110", "0001", "0001", "1110"),
        'T' to arrayOf("1111", "0100", "0100", "0100", "0100", "0100"),
        'U' to arrayOf("1001", "1001", "1001", "1001", "1001", "0110"),
        'V' to arrayOf("1001", "1001", "1001", "1001", "0110", "0110"),
        'W' to arrayOf("1001", "1001", "1001", "1111", "1111", "1001"),
        'X' to arrayOf("1001", "1001", "0110", "0110", "1001", "1001"),
        'Y' to arrayOf("1001", "1001", "0110", "0010", "0010", "0010"),
        'Z' to arrayOf("1111", "0001", "0010", "0100", "1000", "1111"),
        '#' to arrayOf("0101", "1111", "0101", "0101", "1111", "0101"),
        '.' to arrayOf("0000", "0000", "0000", "0000", "0110", "0110"),
        '-' to arrayOf("0000", "0000", "1111", "0000", "0000", "0000"),
        '%' to arrayOf("1001", "0010", "0010", "0100", "0100", "1001"),
        ':' to arrayOf("0000", "0110", "0000", "0000", "0110", "0000"),
        '>' to arrayOf("1000", "0100", "0010", "0010", "0100", "1000"),
        '/' to arrayOf("0001", "0001", "0010", "0100", "1000", "1000"),
        '+' to arrayOf("0000", "0100", "1110", "0100", "0000", "0000"),
        '*' to arrayOf("1010", "0100", "1110", "0100", "1010", "0000"),
        '^' to arrayOf("0100", "1010", "0000", "0000", "0000", "0000"),
    )

    fun rows(ch: String): List<String> = (glyphs[ch.single()] ?: blank).toList()

    fun width(text: String) = text.length * ADVANCE - 1

    /** Draws [text] with its top-left at (x, y) through [put]; [scale] enlarges each glyph pixel to a scale×scale block. */
    inline fun draw(x: Int, y: Int, text: String, color: Int, scale: Int = 1, put: (Int, Int, Int) -> Unit) {
        var cx = x
        for (ch in text) {
            val g = rows(ch.toString())
            for (r in 0 until H) for (c in 0 until 4) if (g[r][c] == '1') {
                for (dy in 0 until scale) for (dx in 0 until scale) put(cx + c * scale + dx, y + r * scale + dy, color)
            }
            cx += ADVANCE * scale
        }
    }
}
