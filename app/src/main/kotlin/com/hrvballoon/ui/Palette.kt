package com.hrvballoon.ui

internal object Palette {
    /** Sunset keyframes, eight stops deep blue → warm: golden hour, amber, rose-violet, indigo dusk, night. */
    val SKY_PHASES = arrayOf(
        intArrayOf(0xFF2C3F8A.toInt(), 0xFF4A5099.toInt(), 0xFF7466A6.toInt(), 0xFFA07EA8.toInt(), 0xFFC995A0.toInt(), 0xFFE8AE8E.toInt(), 0xFFF8C98A.toInt(), 0xFFFFE3A0.toInt()),
        intArrayOf(0xFF26327A.toInt(), 0xFF433C8C.toInt(), 0xFF6A4890.toInt(), 0xFF92508E.toInt(), 0xFFBE6482.toInt(), 0xFFE2807A.toInt(), 0xFFF5A77E.toInt(), 0xFFFBCF8C.toInt()),
        intArrayOf(0xFF161A42.toInt(), 0xFF2B2160.toInt(), 0xFF4A2D78.toInt(), 0xFF6F3A84.toInt(), 0xFF9A4A86.toInt(), 0xFFC85C72.toInt(), 0xFFE6705C.toInt(), 0xFFF79C6E.toInt()),
        intArrayOf(0xFF0E1236.toInt(), 0xFF1A1748.toInt(), 0xFF2A2062.toInt(), 0xFF3E2A72.toInt(), 0xFF57347C.toInt(), 0xFF7A3F6C.toInt(), 0xFFA0505A.toInt(), 0xFFC86A54.toInt()),
        intArrayOf(0xFF080A1E.toInt(), 0xFF0E1130.toInt(), 0xFF151A42.toInt(), 0xFF1D2250.toInt(), 0xFF27295A.toInt(), 0xFF34305F.toInt(), 0xFF423A62.toInt(), 0xFF524766.toInt()),
    )
    /** Dusk phases: the five keyframes with three interpolated steps between each pair, so each dither step is a quarter of a keyframe. */
    const val PHASES = 17
    private fun mix(a: Int, b: Int, num: Int, den: Int): Int {
        fun ch(sh: Int) = (a shr sh and 0xFF).let { it + ((b shr sh and 0xFF) - it) * num / den }
        return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    val SKY_RAMP: Array<IntArray> = Array(PHASES) { p ->
        if (p % 4 == 0) SKY_PHASES[p / 4] else IntArray(8) { i -> mix(SKY_PHASES[p / 4][i], SKY_PHASES[p / 4 + 1][i], p % 4, 4) }
    }
    /** The golden-hour ramp (also the letterbox colour outside the scene). */
    val SKY: IntArray get() = SKY_PHASES[0]
    const val SUN = 0xFFFFE9B0.toInt()
    const val SUN_RIM = 0xFFFFF6DC.toInt()
    const val SUN_LOW = 0xFFFF9A5A.toInt()
    const val SUN_LOW_RIM = 0xFFFFC68A.toInt()
    const val SUN_GLOW = 0xFFF7C86A.toInt()
    const val STAR_SKY = 0xFFFFF8E0.toInt()
    const val STAR_SKY_DIM = 0xFFB8B4D8.toInt()
    const val CLOUD_NEAR = 0xFFFFF6EA.toInt()
    const val CLOUD_WHITE = 0xFFF9E6E0.toInt()
    const val CLOUD_SHADE = 0xFFD8B2CC.toInt()
    const val CLOUD_SUNLIT = 0xFFF2A07A.toInt()
    const val CLOUD_OUTLINE = 0xFFB08AB8.toInt()
    const val CLOUD_FAR = 0xFFD9C4E6.toInt()
    const val CLOUD_FAR_SHADE = 0xFFB89ED0.toInt()
    const val FLOWER_HEART = 0xFFFFD36B.toInt()
    val FLOWERS = intArrayOf(0xFFF2A7C3.toInt(), 0xFFFFE9C2.toInt())
    const val HILL_FAR = 0xFF6A4F99.toInt()
    const val HILL_FAR_CREST = 0xFF8A6AB4.toInt()
    const val HILL_MID = 0xFF3D2A66.toInt()
    const val HILL_MID_CREST = 0xFF5E4190.toInt()
    const val HILL = 0xFF2A5A66.toInt()
    const val HILL_LIGHT = 0xFF3B7A80.toInt()
    const val TREE = 0xFF1C3F4C.toInt()
    const val TREE_LIGHT = 0xFF25525C.toInt()
    const val GROUND = 0xFF3F7D4A.toInt()
    const val GROUND_DOT = 0xFF5A9C58.toInt()
    const val GROUND_DARK = 0xFF2E5C3A.toInt()
    const val GRASS_LIGHT = 0xFF7FB56C.toInt()
    const val SHADOW = 0xFF24492F.toInt()
    const val CORAL = 0xFFE2574B.toInt()
    const val CORAL_LIGHT = 0xFFF28A72.toInt()
    const val CORAL_DARK = 0xFFA83A3A.toInt()
    const val CREAM = 0xFFFFF1D6.toInt()
    const val CREAM_LIGHT = 0xFFFFFBEE.toInt()
    const val CREAM_DARK = 0xFFD9C09A.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val DARK = 0xFF3A2A22.toInt()
    const val WICKER_A = 0xFF9C6B3C.toInt()
    const val WICKER_B = 0xFF7A4F28.toInt()
    const val FLAME_A = 0xFFFF9B2E.toInt()
    const val FLAME_B = 0xFFFFE34D.toInt()
    const val RABBIT = 0xFFF4E7D2.toInt()
    const val RABBIT_DARK = 0xFFB89C7A.toInt()
    const val STAR = 0xFFFFD83B.toInt()
    const val STAR_DARK = 0xFFE3A41C.toInt()
    const val PANEL = 0xFF1A1033.toInt()
    const val PANEL_EDGE = 0xFF3A2A66.toInt()
    const val PANEL_INHALE = 0xFF36235F.toInt()
    const val TRACE = 0xFFFFEBC4.toInt()
    const val TRACE_DIM = 0xFF9C86C9.toInt()
    const val PACER_FRAME = 0xFF1E1240.toInt()
    const val PACER_BG = 0xFF3A2466.toInt()
    const val PACER_IN = 0xFFFFB84D.toInt()
    const val PACER_OUT = 0xFF7FDDF2.toInt()
    const val PLATE = 0xFF1A1033.toInt()
    const val HUD_TEXT = 0xFFFFF1DC.toInt()
    const val LOST_TEXT = 0xFFFFB84D.toInt()
    const val DOT_GREEN = 0xFF5FD38A.toInt()
    const val DOT_AMBER = 0xFFFFA62B.toInt()
    const val DOT_RED = 0xFFD63A3A.toInt()
    private const val NIGHT_TINT = 0xFF141A3A.toInt()

    /** A colour under dusk phase [k] (0 = as painted … PHASES − 1 = night): pulled toward a deep blue in fixed steps, so every tint is in the palette. */
    fun dusk(c: Int, k: Int): Int {
        if (k <= 0) return c
        fun ch(shift: Int): Int {
            val v = c shr shift and 0xFF
            val n = NIGHT_TINT shr shift and 0xFF
            return v + (n - v) * k * 68 / (100 * (PHASES - 1))
        }
        return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val BASE = SKY_RAMP.flatMap { it.asList() }.toIntArray() + FLOWERS + intArrayOf(
        SUN, SUN_RIM, SUN_LOW, SUN_LOW_RIM, SUN_GLOW, STAR_SKY, STAR_SKY_DIM,
        CLOUD_NEAR, CLOUD_WHITE, CLOUD_SHADE, CLOUD_SUNLIT, CLOUD_OUTLINE, CLOUD_FAR, CLOUD_FAR_SHADE, FLOWER_HEART,
        HILL_FAR, HILL_FAR_CREST, HILL_MID, HILL_MID_CREST, HILL, HILL_LIGHT, TREE, TREE_LIGHT,
        GROUND, GROUND_DOT, GROUND_DARK, GRASS_LIGHT, SHADOW,
        CORAL, CORAL_LIGHT, CORAL_DARK, CREAM, CREAM_LIGHT, CREAM_DARK, WHITE, DARK, WICKER_A, WICKER_B, FLAME_A, FLAME_B, RABBIT, RABBIT_DARK,
        STAR, STAR_DARK, PANEL, PANEL_EDGE, PANEL_INHALE, TRACE, TRACE_DIM, PACER_FRAME, PACER_BG, PACER_IN, PACER_OUT,
        PLATE, HUD_TEXT, LOST_TEXT, DOT_GREEN, DOT_AMBER, DOT_RED,
    )

    /** Every base colour plus its dusk tints. */
    val PALETTE = BASE + (1 until PHASES).flatMap { k -> BASE.map { dusk(it, k) } }.toIntArray()

    fun grey(c: Int): Int {
        val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        return 0xFF000000.toInt() or (lum shl 16) or (lum shl 8) or lum
    }
}
