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

/**
 * Star-ranked table of past sessions. With [EXTRA_RESULT] it first shows the results card for the
 * session whose start is [EXTRA_START]. Tapping a row opens that session's card; tapping a card returns to
 * the table; tapping elsewhere on the table closes. A converged search card is the offer itself: a tap
 * returns [EXTRA_USE_RATE], Back skips.
 */
class RankActivity : Activity() {
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
        val settings = Settings(this)
        view = RankView(this).apply {
            records = ranked
            highlight = pos
            hasUnranked = all.any { !it.ranked }
            lifetimeStars = settings.lifetimeStars
            blink = SysSettings.Global.getFloat(contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_UP) tap(ev.y); true }
            // Accessibility click (TalkBack double-tap) has no coordinates: act on the card, or open your own session.
            setOnClickListener { if (view.result != null) tap(0f) else if (pos >= 0) show(ranked[pos], pos) else finish() }
        }
        // After the assignment: show() reads `view`, which is still unset inside the apply block above.
        if (intent.getBooleanExtra(EXTRA_RESULT, false) && mine != null) show(mine, pos) else describe()
        setContentView(view)
    }

    private fun show(r: SessionRecord, pos: Int) {
        view.result = r
        view.rankPos = pos + 1
        view.total = ranked.size
        view.best = ranked.firstOrNull()
        view.invalidate()
        describe()
    }

    private fun tap(y: Float) {
        view.result?.let { r ->
            if (r.kind == SessionRecord.KIND_RF_SEARCH) { setResult(RESULT_OK, Intent().putExtra(EXTRA_USE_RATE, r.rateBpm)); finish(); return }
            view.result = null; view.invalidate(); describe(); return
        }
        val row = view.rowAtViewY(y)
        if (row >= 0) show(ranked[row], row) else finish()
    }

    /** The bitmap is opaque to TalkBack, so the description carries what it says. */
    private fun describe() {
        val r = view.result
        view.contentDescription = when {
            r == null && ranked.isEmpty() -> "No ranked sessions yet"
            r == null -> "Best sessions. " + ranked.take(7).mapIndexed { i, s ->
                String.format(Locale.ROOT, "%d: %d stars", i + 1, s.stars)
            }.joinToString(". ") + ". Double-tap to open your session."
            r.kind == SessionRecord.KIND_RF_SEARCH -> String.format(Locale.ROOT, "Resonance search done: %.1f per minute. Double-tap to use it, Back to skip.", r.rateBpm)
            r.isSearch -> "Resonance search incomplete, ${r.probes.size} probes. Double-tap for the table."
            else -> String.format(
                Locale.ROOT, "Session done. %d stars, %s. %d lifetime stars. Double-tap for the table.",
                r.stars, if (view.rankPos > 0) "number ${view.rankPos} of ${ranked.size} by stars" else "not ranked", view.lifetimeStars,
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
