package com.hrvballoon.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.Trend
import com.hrvballoon.core.TrendPeriod

class RankView(context: Context) : View(context) {
    var records: List<SessionRecord> = emptyList()
    var highlight = -1
    var timeMs = 0L

    /** When set, the results card for this session is drawn instead of the table. */
    var result: SessionRecord? = null
    var rankPos = 0
    var total = 0
    var best: SessionRecord? = null
    /** The 7 days up to the shown session's day, for the results card's coherence strip. */
    var week: Trend? = null

    /** 0 = the BEST list, 1 = the TREND page (drawn from [trend] and [period]). */
    var tab = 0
    var trend: Trend? = null
    var period = TrendPeriod.D30
    var lifetimeStars = 0
    /** The shown card ends with "tap to use" its rate. */
    var offer = false
    var hasUnranked = false
    var blink = true
    private var scale = 1
    private var offsetX = 0
    private var offsetY = 0

    private fun lx(x: Float) = ((x - offsetX) / scale).toInt()
    private fun ly(y: Float) = ((y - offsetY) / scale).toInt()

    /** The tab (0 BEST, 1 TREND) under a view-space point, or -1. */
    fun tabAtView(x: Float, y: Float) = RankScene.tabAt(lx(x), ly(y), scene?.w ?: 0)

    /** The period chip under a view-space point, or null. */
    fun chipAtView(x: Float, y: Float) = RankScene.chipAt(lx(x), ly(y), scene?.w ?: 0)

    /** Logical row under a view-space y, or -1. */
    fun rowAtViewY(y: Float) = RankScene.rowAt(((y - offsetY) / scale).toInt(), records.size)

    private var scene: RankScene? = null
    private var bitmap: Bitmap? = null
    private val dst = Rect()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }

    override fun performClick() = super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        scale = maxOf(1, w / 160)
        val lw = w / scale
        val lh = h / scale
        scene = RankScene(lw, lh, SceneText { resources.getString(it) })
        bitmap = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        offsetX = (w - lw * scale) / 2
        val ox = offsetX
        offsetY = (h - lh * scale) / 2
        dst.set(ox, offsetY, ox + lw * scale, offsetY + lh * scale)
    }

    override fun onDraw(c: Canvas) {
        val s = scene ?: return
        val b = bitmap ?: return
        val px = result?.let { s.renderResult(it, rankPos, total, best, timeMs, lifetimeStars, offer, week) }
            ?: trend?.takeIf { tab == 1 }?.let { s.renderTrend(it, period, timeMs) } ?: s.render(records, highlight, timeMs, hasUnranked, blink)
        b.setPixels(px, 0, s.w, 0, 0, s.w, s.h)
        c.drawColor(RankScene.NIGHT[0])
        c.drawBitmap(b, null, dst, paint)
    }
}
