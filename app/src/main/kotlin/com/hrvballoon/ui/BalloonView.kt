package com.hrvballoon.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Blits the PixelScene framebuffer with nearest-neighbour scaling (integer factor, crisp pixels). The Activity sets the fields. */
class BalloonView(context: Context) : View(context) {
    /** 0 = basket on the ground, 1 = top of the sky. */
    var height01 = 0.0
    var rising = false
    var pacerLevel = 0.0
    var inhale = true
    var running = false
    var frozen = false
    var timeMs = 0L
    var stars = 0
    var falling: List<Long> = emptyList()
    var traceT = DoubleArray(0)
    var traceHr = DoubleArray(0)
    var traceNowMs = 0L
    var pacerPeriodMs = 0.0
    var inhaleFraction = 0.4
    var pacerCycleOffset = 0.0
    var pacerPhaseAt: ((Double) -> Double)? = null
    var hud: Hud? = null
    var reducedMotion = false
    /** Session progress 0..1 (the sunset); 0 while idle. */
    var progress = 0.0
    var ceilingHitAtMs: Long? = null

    /** Called with the tapped y as a fraction of the view height (0 = top). */
    var onTap: ((Float) -> Unit)? = null

    init {
        isClickable = true
        setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) { onTap?.invoke(ev.y / height); v.performClick() }
            true
        }
    }

    private var scene: PixelScene? = null
    private var bitmap: Bitmap? = null
    private val dst = Rect()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val scale = maxOf(1, w / LOGICAL_WIDTH)
        val lw = w / scale
        val lh = h / scale
        scene = PixelScene(lw, lh)
        bitmap = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888)
        val ox = (w - lw * scale) / 2
        val oy = (h - lh * scale) / 2
        dst.set(ox, oy, ox + lw * scale, oy + lh * scale)
    }

    override fun onDraw(c: Canvas) {
        val s = scene ?: return
        val b = bitmap ?: return
        val state = SceneState(
            height01, rising, pacerLevel, inhale, running, frozen, timeMs, stars, falling,
            traceT, traceHr, traceNowMs, pacerPeriodMs, inhaleFraction, pacerCycleOffset, pacerPhaseAt, hud, reducedMotion, progress, ceilingHitAtMs,
        )
        b.setPixels(s.render(state), 0, s.w, 0, 0, s.w, s.h)
        c.drawColor(PixelScene.SKY[0])
        c.drawBitmap(b, null, dst, paint)
    }

    private companion object {
        const val LOGICAL_WIDTH = 160
    }
}
