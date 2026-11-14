package com.hrvballoon

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.view.Choreographer
import android.view.MotionEvent
import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.rankSessions
import com.hrvballoon.ui.RankView
import java.util.Locale
import kotlin.math.abs
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
        val all = SessionStore(this).load()
        ranked = rankSessions(all)
        val start = intent.getLongExtra(EXTRA_START, -1)
        val mine = all.lastOrNull { it.startMs == start }
        pos = ranked.indexOfFirst { it.startMs == start }
        val settings = Settings(AndroidPrefs(this))
        currentRate = settings.rateBpm
        view = RankView(this).apply {
            records = ranked
            highlight = pos
            hasUnranked = all.any { !it.ranked }
            lifetimeStars = settings.lifetimeStars
            blink = SysSettings.Global.getFloat(contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            var touchY: Float? = null
            setOnTouchListener { v, ev -> if (ev.actionMasked == MotionEvent.ACTION_UP) { touchY = ev.y; v.performClick() }; true }
            // A touch clicks at its y; an accessibility click (TalkBack double-tap) has none: act on the card, or open your own session.
            setOnClickListener {
                val y = touchY.also { touchY = null }
                if (y != null) tap(y) else if (view.result != null) tap(0f) else if (pos >= 0) show(ranked[pos], pos) else finish()
            }
        }
        // After the assignment: show() reads `view`, which is still unset inside the apply block above.
        if (intent.getBooleanExtra(EXTRA_RESULT, false) && mine != null) show(mine, pos) else describe()
        val root = android.widget.FrameLayout(this).apply { addView(view) }   // padded for the system bars; the view keeps its own coordinates
        setContentView(root)
        edgeToEdge(root)
    }

    private fun show(r: SessionRecord, pos: Int) {
        view.result = r
        view.rankPos = pos + 1
        view.total = ranked.size
        view.best = ranked.firstOrNull()
        // A search without a peak offers the 5.5 default only to someone breathing at another rate.
        view.offer = r.kind == SessionRecord.KIND_RF_SEARCH || (r.kind == SessionRecord.KIND_RF_NO_PEAK && abs(currentRate - r.rateBpm) > 0.05)
        view.invalidate()
        describe()
    }

    private fun tap(y: Float) {
        view.result?.let { r ->
            if (view.offer) { setResult(RESULT_OK, Intent().putExtra(EXTRA_USE_RATE, r.rateBpm)); finish(); return }
            view.result = null; view.invalidate(); describe(); return
        }
        val row = view.rowAtViewY(y)
        if (row >= 0) show(ranked[row], row) else finish()
    }

    /** The bitmap is opaque to TalkBack, so the description carries what it says. */
    private fun describe() {
        val r = view.result
        view.contentDescription = when {
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
