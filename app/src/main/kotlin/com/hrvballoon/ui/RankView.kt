package com.hrvballoon.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.hrvballoon.core.SessionRecord

class RankView(context: Context) : View(context) {
    var records: List<SessionRecord> = emptyList()
    var highlight = -1
    var timeMs = 0L

    /** When set, the results card for this session is drawn instead of the table. */
    var result: SessionRecord? = null
    var rankPos = 0
    var total = 0
    var best: SessionRecord? = null
    var lifetimeStars = 0
    var hasUnranked = false
    var blink = true
    private var scale = 1
    private var offsetY = 0

    /** Logical row under a view-space y, or -1. */
    fun rowAtViewY(y: Float) = RankScene.rowAt(((y - offsetY) / scale).toInt(), records.size)

    private var scene: RankScene? = null
    private var bitmap: Bitmap? = null
    private val dst = Rect()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        scale = maxOf(1, w / 160)
        val lw = w / scale
        val lh = h / scale
        scene = RankScene(lw, lh)
        bitmap = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val ox = (w - lw * scale) / 2
        offsetY = (h - lh * scale) / 2
        dst.set(ox, offsetY, ox + lw * scale, offsetY + lh * scale)
    }

    override fun onDraw(c: Canvas) {
        val s = scene ?: return
        val b = bitmap ?: return
        val px = result?.let { s.renderResult(it, rankPos, total, best, timeMs, lifetimeStars) } ?: s.render(records, highlight, timeMs, hasUnranked, blink)
        b.setPixels(px, 0, s.w, 0, 0, s.w, s.h)
        c.drawColor(RankScene.NIGHT[0])
        c.drawBitmap(b, null, dst, paint)
    }
}
