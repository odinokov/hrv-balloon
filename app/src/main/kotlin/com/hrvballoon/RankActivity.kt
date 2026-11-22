package com.hrvballoon

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.Trend
import com.hrvballoon.core.TrendPeriod
import com.hrvballoon.core.TrendPoint
import com.hrvballoon.core.coherenceTrend
import com.hrvballoon.core.offersRate
import com.hrvballoon.core.rankSessions
import com.hrvballoon.ui.RankView
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Star-ranked table of past sessions. With [EXTRA_RESULT] it first shows the results card for the
 * session whose start is [EXTRA_START]. Tapping a row opens that session's card; tapping a card returns to
 * the table; tapping elsewhere on the table closes. A converged search card is the offer itself: a tap
 * returns [EXTRA_USE_RATE], Back skips.
 */
class RankActivity : Activity() {
    private var currentRate = 0.0
    private lateinit var view: RankView
    private lateinit var ranked: List<SessionRecord>
    private lateinit var points: List<TrendPoint>
    private lateinit var settings: Settings
    private var pos = -1

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            view.timeMs = SystemClock.elapsedRealtime()
            view.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val all = SessionStore(filesDir).load()
        ranked = rankSessions(all)
        points = all.map { TrendPoint(it.startMs, it.validMs, it.coherence) }
        val start = intent.getLongExtra(EXTRA_START, -1)
        val mine = all.lastOrNull { it.startMs == start }
        pos = ranked.indexOfFirst { it.startMs == start }
        settings = Settings(AndroidPrefs(this))
        currentRate = settings.rateBpm
        view = RankView(this).apply {
            records = ranked
            highlight = pos
            hasUnranked = all.any { !it.ranked }
            lifetimeStars = settings.lifetimeStars
            blink = SysSettings.Global.getFloat(contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            var touch: Pair<Float, Float>? = null
            setOnTouchListener { v, ev -> if (ev.actionMasked == MotionEvent.ACTION_UP) { touch = ev.x to ev.y; v.performClick() }; true }
            // A touch clicks at its y; an accessibility click (TalkBack double-tap) has none: act on the card, or open your own session.
            setOnClickListener {
                val at = touch.also { touch = null }
                if (at != null) tap(at.first, at.second) else if (view.result != null) tap(0f, 0f) else if (pos >= 0) show(ranked[pos], pos) else finish()
            }
        }
        // After the assignment: show() reads `view`, which is still unset inside the apply block above.
        if (intent.getBooleanExtra(EXTRA_RESULT, false) && mine != null) show(mine, pos) else describe()
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (view.result == null) for ((id, label) in RankActions.available(view.tab)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(id, getString(label)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val next = if (view.result == null) RankActions.perform(action, view.tab, settings.trendPeriod) else null
                if (next == null) return super.performAccessibilityAction(host, action, args)
                settings.trendPeriod = next.second
                showTab(next.first)
                view.announceForAccessibility(view.contentDescription)
                return true
            }
        }
        val root = android.widget.FrameLayout(this).apply { addView(view) }   // padded for the system bars; the view keeps its own coordinates
        setContentView(root)
        edgeToEdge(root)
    }

    private fun show(r: SessionRecord, pos: Int) {
        view.result = r
        view.rankPos = pos + 1
        view.total = ranked.size
        view.best = ranked.firstOrNull()
        view.offer = offersRate(r.kind, r.rateBpm, currentRate)
        view.week = r.coherence?.let { coherenceTrend(points, r.startMs, ZoneId.systemDefault(), TrendPeriod.D7) }
        view.invalidate()
        describe()
    }

    private fun tap(x: Float, y: Float) {
        view.result?.let { r ->
            if (view.offer) { setResult(RESULT_OK, Intent().putExtra(EXTRA_USE_RATE, r.rateBpm)); finish(); return }
            view.result = null; view.invalidate(); describe(); return
        }
        val tab = view.tabAtView(x, y)
        if (tab >= 0) { if (tab != view.tab) showTab(tab); return }
        if (view.tab == 1) {
            view.chipAtView(x, y)?.let { settings.trendPeriod = it; showTab(1) } ?: finish()
            return
        }
        val row = view.rowAtViewY(y)
        if (row >= 0) show(ranked[row], row) else finish()
    }

    /** Switches the page; the TREND page is read afresh, for the saved period. */
    private fun showTab(tab: Int) {
        view.tab = tab
        view.period = settings.trendPeriod
        view.trend = coherenceTrend(points, System.currentTimeMillis(), ZoneId.systemDefault(), view.period)
        view.invalidate()
        describe()
    }

    /** The bitmap is opaque to TalkBack, so the description carries what it says. */
    private fun describe() {
        val r = view.result
        view.contentDescription = when {
            r == null && view.tab == 1 -> trendDescription({ getString(it) }, view.trend!!, view.period, Locale.getDefault())
            r == null && ranked.isEmpty() -> getString(R.string.a11y_no_ranked)
            r == null -> getString(R.string.a11y_best, ranked.take(7).mapIndexed { i, s ->
                resources.getQuantityString(R.plurals.a11y_rank_item, s.stars, i + 1, s.stars)
            }.joinToString(". "))
            r.kind == SessionRecord.KIND_RF_SEARCH -> getString(R.string.a11y_rf_done, r.rateBpm)
            r.kind == SessionRecord.KIND_RF_NO_PEAK ->
                getString(R.string.a11y_rf_no_peak, r.rateBpm, getString(if (view.offer) R.string.a11y_use_or_skip else R.string.a11y_for_table))
            r.isSearch -> resources.getQuantityString(R.plurals.a11y_rf_incomplete, r.probes.size, r.probes.size)
            else -> getString(
                R.string.a11y_session_done,
                resources.getQuantityString(R.plurals.a11y_stars, r.stars, r.stars),
                if (view.rankPos > 0) getString(R.string.a11y_rank, view.rankPos, ranked.size) else getString(R.string.a11y_not_ranked),
                resources.getQuantityString(R.plurals.a11y_lifetime, view.lifetimeStars, view.lifetimeStars),
                r.meanShape?.let { (it * 100).roundToInt().let { pct -> resources.getQuantityString(R.plurals.a11y_rhythm, pct, pct) } } ?: "",
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onPause()
    }

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_RESULT = "result"
        const val EXTRA_USE_RATE = "useRate"
    }
}

/** TalkBack's one sentence for the TREND page; numbers follow [locale], the device's. */
internal fun trendDescription(text: (Int) -> String, trend: Trend, period: TrendPeriod, locale: Locale): String {
    fun n(v: Double) = String.format(locale, "%.1f", v)
    if (trend.daysPractised == 0) return String.format(locale, text(R.string.a11y_trend_none), period.days)
    val prev = trend.previousMedian?.let { String.format(locale, text(R.string.a11y_trend_prev), period.days, n(it)) } ?: ""
    return String.format(locale, text(R.string.a11y_trend), period.days, n(trend.median!!), trend.daysPractised, prev)
}
