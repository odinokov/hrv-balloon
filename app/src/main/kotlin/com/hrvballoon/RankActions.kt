package com.hrvballoon

import com.hrvballoon.core.TrendPeriod

/** TalkBack's way to the pages and periods the touch UI reaches with tabs and chips: the actions per page, and what each does. */
object RankActions {
    const val SHOW_TREND = 0x0A110001
    const val SHOW_BEST = 0x0A110002
    private const val PERIOD_BASE = 0x0A110010   // + the period's ordinal

    private val periodLabels = listOf(R.string.a11y_action_7d, R.string.a11y_action_14d, R.string.a11y_action_30d, R.string.a11y_action_1y)

    /** (action id, label string) offered on [tab]: the other page, and on TREND the four periods. */
    fun available(tab: Int): List<Pair<Int, Int>> =
        if (tab == 0) listOf(SHOW_TREND to R.string.a11y_action_trend)
        else listOf(SHOW_BEST to R.string.a11y_action_best) + TrendPeriod.values().map { PERIOD_BASE + it.ordinal to periodLabels[it.ordinal] }

    /** A tap on [tapped] while [shown] is on screen: the page to switch to, or null = close (the shown tab acts like the title bar did in 2.7). */
    fun tabTap(tapped: Int, shown: Int): Int? = tapped.takeIf { it != shown }

    /** The (tab, period) after performing [id] on [tab], or null when [id] is not offered there. */
    fun perform(id: Int, tab: Int, period: TrendPeriod): Pair<Int, TrendPeriod>? = when {
        available(tab).none { it.first == id } -> null
        id == SHOW_TREND -> 1 to period
        id == SHOW_BEST -> 0 to period
        else -> 1 to TrendPeriod.values()[id - PERIOD_BASE]
    }
}
