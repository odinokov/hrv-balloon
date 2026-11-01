package com.hrvballoon

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings as SysSettings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.hrvballoon.ble.HrmClient
import com.hrvballoon.core.HrsPacket
import com.hrvballoon.core.LostReason
import com.hrvballoon.core.SessionConfig
import com.hrvballoon.core.SessionCsvWriter
import com.hrvballoon.core.SessionEngine
import com.hrvballoon.core.SessionMeta
import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.Signal
import com.hrvballoon.core.Smoother
import com.hrvballoon.core.Bounce
import com.hrvballoon.core.StarCounter
import com.hrvballoon.core.UiState
import com.hrvballoon.core.stepRateTenths
import com.hrvballoon.ui.BalloonView
import com.hrvballoon.ui.Hud
import com.hrvballoon.ui.MediaStoreCsv
import com.hrvballoon.ui.PixelScene
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), HrmClient.Listener {
    /** Connection phase, rendered in the scene's top band; transient messages are states, so nothing can overwrite them. */
    private enum class Link { NO_PERMISSION, BT_OFF, NO_DEVICE, SCANNING, CONNECTING, CONNECTED }

    private lateinit var client: HrmClient
    private lateinit var settings: Settings
    private lateinit var balloon: BalloonView
    private lateinit var startStop: Button
    private lateinit var ranksButton: Button
    private lateinit var settingsButton: Button
    private lateinit var controls: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, String?>()
    private var scanAdapter: ArrayAdapter<String>? = null
    private var scanHint: TextView? = null
    private val vibrator by lazy { (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator }

    private var link = Link.NO_DEVICE
    private var connectedAt = 0L
    private var lastRrAt = 0L
    private var latestHr: Int? = null
    private var lastHrAt = 0L
    private var foreground = false

    private var engine: SessionEngine? = null
    private var startElapsed = 0L
    private var startWall = 0L
    private var csvName = ""
    private var target = 0.0
    private var frozen = false
    private var smoother = Smoother()
    private var starCounter = StarCounter()
    private val bounce = Bounce()
    private val falling = ArrayList<Long>()
    private var lastFrame = 0L
    private var lastInhale: Boolean? = null
    private var pendingResult: Long? = null
    private var controlsUntil = 0L
    private var leftAtMs = 0L
    private var lastDescription = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        buildUi()
        client = HrmClient(this, this)
        if (hasPermissions()) connectOrPick() else requestPermissions(PERMS, REQ_PERMS)
        main.post(ticker)
        CrashLog(filesDir).apply { install(); take()?.let { showCrash(it) } }
    }

    private fun showCrash(report: String) {
        AlertDialog.Builder(this).setTitle("Last run crashed").setMessage(report.lines().take(12).joinToString("\n"))
            .setPositiveButton("Copy") { _, _ -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", report)) }
            .setNegativeButton("Dismiss", null).show()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        main.removeCallbacks(graceStop)
        main.removeCallbacks(ticker)
        main.post(ticker)
        engine?.let {
            it.resume(sessionNow())
        }
        pendingResult?.let {
            Toast.makeText(this, "Session ended while you were away", Toast.LENGTH_LONG).show()
            openRanks(it, result = true)
            pendingResult = null
        }
    }

    override fun onResume() {
        super.onResume()
        balloon.reducedMotion = SysSettings.Global.getFloat(contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        lastFrame = SystemClock.elapsedRealtime()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onPause()
    }

    /** Leaving the app pauses the statistics and cues (beats are still recorded); the session ends if you're gone a minute. */
    override fun onStop() {
        foreground = false
        if (engine == null) main.removeCallbacks(ticker)   // idle in the background: nothing to tick for
        engine?.let {
            leftAtMs = sessionNow()
            it.pause(leftAtMs)
            main.postDelayed(graceStop, GRACE_MS)
        }
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(graceStop)
        main.removeCallbacks(landing)
        client.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (engine != null) confirmStop() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private val graceStop = Runnable { if (engine != null && !foreground) stopSession(cue = false, endAtMs = leftAtMs) }

    /** After Stop the balloon is seen landing before the card opens — a quiet end, then the score. */
    private val landing = Runnable {
        controls.visibility = View.VISIBLE
        if (foreground) openRanks(startWall, result = true) else pendingResult = startWall
    }

    // ---- UI ----

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        balloon = BalloonView(this).apply {
            contentDescription = "Breathing scene"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            onTap = { yf -> if (engine != null) showControls() else if (yf < 0.12f) onStripTap() }
        }
        startStop = Button(this).apply {
            text = "Start"; isEnabled = false
            setOnClickListener { if (engine == null) startSession() else confirmStop() }
        }
        styleStart()
        ranksButton = Button(this).apply { text = "Ranks"; secondary(); setOnClickListener { openRanks(-1, result = false) } }
        settingsButton = Button(this).apply { text = "Settings"; secondary(); setOnClickListener { showSettings() } }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(16), px(8), px(16), px(12))
            setBackgroundColor(PixelScene.PLATE)
            for ((i, button) in listOf(startStop, ranksButton, settingsButton).withIndex()) {
                button.isAllCaps = false
                button.setSingleLine()
                button.textSize = 14f
                button.minWidth = 0
                button.minimumWidth = 0
                button.setPadding(px(8), 0, px(8), 0)
                addView(button, LinearLayout.LayoutParams(0, px(52), if (i == 0) 1.6f else 1f).apply {
                    if (i > 0) leftMargin = px(6)
                })
            }
        }
        // Controls overlay the scene, so showing/hiding them never resizes (and rebuilds) the framebuffer.
        setContentView(FrameLayout(this).apply {
            addView(balloon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    /** Enabled = yellow, disabled = slate, running = red with white text (4.6:1): unmistakable regardless of the OEM skin. */
    private fun styleStart() {
        val running = engine != null
        startStop.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(if (running) RED else YELLOW, SLATE),
        )
        startStop.setTextColor(if (running) 0xFFFFFFFF.toInt() else if (startStop.isEnabled) NAVY else SLATE_TEXT)
        if (!running) startStop.text = if (settings.rfSearch) "Find my rate" else String.format(Locale.ROOT, "Start · %.1f/min", settings.rateBpm)
    }

    /** Secondary buttons dim visibly when disabled. */
    private fun Button.secondary() {
        val states = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf())
        backgroundTintList = ColorStateList(states, intArrayOf(0xFF1E2C66.toInt(), 0xFF141C4A.toInt()))
        setTextColor(ColorStateList(states, intArrayOf(PALE, SLATE_TEXT)))
    }

    /** A stray tap can't end a session: Stop asks first (tap outside = keep going). */
    private fun confirmStop() {
        AlertDialog.Builder(this).setMessage("End the session?")
            .setPositiveButton("End") { _, _ -> stopSession(cue = true) }
            .setNegativeButton("Keep going", null).show()
    }

    /** During a session the button row is hidden; a tap on the scene reveals Stop for a few seconds. */
    private fun showControls() {
        controlsUntil = SystemClock.elapsedRealtime() + CONTROLS_MS
        controls.visibility = View.VISIBLE
    }

    private fun up(s: String) = s.uppercase(Locale.ROOT)

    private fun freshHr(now: Long = SystemClock.elapsedRealtime()): Int? = latestHr?.takeIf {
        link == Link.CONNECTED && lastHrAt >= connectedAt && now - lastHrAt in 0..4000
    }

    /** Connection guidance until ready; then session status, heart rate and breathing pace. */
    private fun renderStatus() {
        val now = SystemClock.elapsedRealtime()
        val connected = link == Link.CONNECTED
        val rrFresh = connected && lastRrAt >= connectedAt && now - lastRrAt in 0..4000
        val neverRr = connected && lastRrAt < connectedAt && now - connectedAt >= 10_000
        val dot = when {
            rrFresh -> PixelScene.DOT_GREEN
            connected || link == Link.CONNECTING || link == Link.SCANNING -> PixelScene.DOT_AMBER
            else -> PixelScene.DOT_RED
        }
        val enabled = engine != null || rrFresh
        if (startStop.isEnabled != enabled) { startStop.isEnabled = enabled; styleStart() }
        if (engine == null) {
            val status = when (link) {
                Link.NO_PERMISSION -> "Allow Bluetooth / tap here"
                Link.BT_OFF -> "Bluetooth off / tap to enable"
                Link.NO_DEVICE -> "Pair your strap / tap here"
                Link.SCANNING -> "Looking for your strap"
                Link.CONNECTING -> "Connecting / wear your strap"
                Link.CONNECTED -> when {
                    neverRr -> "Check your strap / tap here"
                    !rrFresh -> "Waiting for your strap"
                    else -> "Ready"
                }
            }
            balloon.hud = if (rrFresh) Hud.session(dot, status, freshHr(now), settings.rateBpm, mmss(settings.durationMs)) else Hud(dot, status)
            describe(status)
        } else {
            hudDot = dot
        }
    }

    private var hudDot = PixelScene.DOT_RED

    private fun onStripTap() {
        when (link) {
            Link.NO_PERMISSION -> requestPermissions(PERMS, REQ_PERMS)
            Link.CONNECTING -> pick()
            Link.BT_OFF, Link.NO_DEVICE -> connectOrPick()
            Link.CONNECTED -> if (lastRrAt < connectedAt) pick()
            Link.SCANNING -> {}
        }
    }

    /** The scene is a polite live region: it only gets state changes (never the live numbers), so TalkBack stays quiet mid-breath. */
    private fun describe(text: String) {
        if (text == lastDescription) return
        lastDescription = text
        balloon.contentDescription = text
    }

    private fun applyState(s: UiState) {
        frozen = s.signal == Signal.LOST
        if (!frozen) target = s.balloonTarget
        balloon.frozen = frozen
        val lost = when (s.lostReason) {
            LostReason.LINK -> listOf("STRAP DISCONNECTED", "RECONNECTING")
            LostReason.CONTACT -> listOf("NO SKIN CONTACT", "WET THE ELECTRODES")
            LostReason.STALE -> listOf("NO BEATS ARRIVING", "CHECK THE STRAP")
            LostReason.GAP -> listOf("BEATS MISSING", "RECONNECTING")
            LostReason.REJECTS -> listOf("BEATS IRREGULAR", "SIT STILL")
            null -> emptyList()
        }
        val probe = s.rfProbe
        val status = when {
            s.finished -> "Done"
            frozen -> "No signal"
            probe != null && !probe.converged -> "Search"
            s.warmingUp -> "Settling"
            else -> ""
        }
        balloon.hud = Hud.session(hudDot, status, if (frozen) null else freshHr(), s.rateBpm, mmss(s.remainingMs ?: s.elapsedMs), lost)
        balloon.traceT = s.traceT
        balloon.traceHr = s.traceHr
        balloon.forecast = s.forecast?.let { f -> engine?.pacer?.let { p -> { t: Double -> f.at(p.cycles(t)) } } }
        describe((listOf(status) + lost).joinToString(". "))
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            engine?.let { e ->
                val st = e.tick(sessionNow())
                applyState(st)
                if (st.finished) stopSession(cue = true)
            }
            main.postDelayed(this, 250)
        }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastFrame).coerceIn(0, 100).toDouble()
            lastFrame = now
            if (!frozen) balloon.timeMs = now
            if (controls.visibility == View.VISIBLE && engine != null && now > controlsUntil) controls.visibility = View.GONE
            val e = engine
            if (e == null) {
                if (balloon.height01 > 0.002) balloon.height01 = smoother.step(0.0, dt) else balloon.height01 = 0.0
            } else {
                val p = e.pacer
                val t = (now - startElapsed).toDouble()
                val inhale = p.isInhale(t)
                if (lastInhale != null && inhale != lastInhale) cue(inhale)
                lastInhale = inhale
                balloon.inhale = inhale
                balloon.pacerLevel = p.level(t)
                balloon.pacerPeriodMs = p.periodMs(t)
                balloon.pacerCycleOffset = p.cycleOffset(t)
                balloon.pacerPhaseAt = { tt -> p.phase(tt) }
                balloon.traceNowMs = t.toLong()
                balloon.progress = (t / settings.durationMs).coerceIn(0.0, 1.0)   // the sunset is the session
                balloon.rising = !frozen && target - balloon.height01 > 0.01
                if (!frozen) {
                    val h = smoother.step(target, dt)
                    if (starCounter.update(h, p.cycles(t).toInt())) {
                        bounce.hit(now)
                        balloon.ceilingHitAtMs = now
                        earnStar(now)
                    }
                    balloon.height01 = (h - bounce.offset(now)).coerceIn(0.0, 1.0)
                }
                falling.removeAll { now - it > 2500 }
            }
            balloon.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---- session ----

    private fun sessionNow() = SystemClock.elapsedRealtime() - startElapsed

    private fun mmss(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60_000, ms / 1000 % 60)

    private fun earnStar(now: Long) {
        engine?.onStar(sessionNow())
        settings.lifetimeStars++
        falling += now
        balloon.stars = starCounter.count
        balloon.announceForAccessibility("Star")
        // Triple tap: unlike the single inhale cue and the double exhale cue.
        if (settings.vibrate) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 20, 40, 20), -1))
    }

    private fun openRanks(startMs: Long, result: Boolean) {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, RankActivity::class.java).putExtra(RankActivity.EXTRA_START, startMs).putExtra(RankActivity.EXTRA_RESULT, result),
            REQ_RANKS,
        )
    }

    private fun useRate(rate: Double) {
        settings.rateTenths = (rate * 10).roundToInt()
        styleStart()
        renderStatus()
        Toast.makeText(this, String.format(Locale.ROOT, "Breathing rate set to %.1f /min", settings.rateBpm), Toast.LENGTH_SHORT).show()
    }

    private fun startSession() {
        val now = OffsetDateTime.now()
        csvName = if (settings.saveCsv) "hrv_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)) + ".csv" else ""
        val csv = if (!settings.saveCsv) SessionCsvWriter(NULL_STREAM) else try {
            SessionCsvWriter(MediaStoreCsv.open(this, csvName))
        } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage("Cannot create the CSV file in Download/HRV. Check storage space, or switch Save CSV off.")
                .setPositiveButton("OK", null).show()
            return
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        csv.writeHeader(
            SessionMeta(
                version, settings.deviceAddress ?: "?", settings.deviceName, now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                settings.rateBpm, settings.inhaleFraction, settings.durationLabel, settings.floorBpm.toDouble(), settings.ceilingBpm.toDouble(),
            ),
        )
        startElapsed = SystemClock.elapsedRealtime()
        startWall = System.currentTimeMillis()
        engine = SessionEngine(
            SessionConfig(
                settings.rateBpm, settings.inhaleFraction, settings.durationMs, settings.floorBpm.toDouble(), settings.ceilingBpm.toDouble(),
                settings.shapeGate, settings.rfSearch,
            ),
            csv,
        )
        smoother = Smoother(settings.responseMs)
        starCounter = StarCounter()
        falling.clear()
        balloon.stars = 0
        balloon.ceilingHitAtMs = null
        balloon.falling = falling
        balloon.traceT = DoubleArray(0)
        balloon.traceHr = DoubleArray(0)
        balloon.forecast = null
        balloon.pacerPeriodMs = 60_000 / settings.rateBpm
        balloon.inhaleFraction = settings.inhaleFraction
        target = 0.0
        frozen = false
        lastInhale = null
        balloon.progress = 0.0   // a new session starts at golden hour
        balloon.running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startStop.text = "Stop"
        styleStart()
        ranksButton.visibility = View.GONE
        settingsButton.visibility = View.GONE
        showControls()   // visible for the first few seconds so the reveal gesture is learned
    }

    /** [cue] = long vibration: Stop button and timer end, not a session cut short by leaving the app. */
    private fun stopSession(cue: Boolean, endAtMs: Long = sessionNow()) {
        val e = engine ?: return
        val summary = e.stop(endAtMs)
        val wasSearch = settings.rfSearch
        val finalRate = e.pacer.rateAt(endAtMs.toDouble())   // exclude any change still waiting for the next inhale
        engine = null
        frozen = false
        target = 0.0
        balloon.running = false
        balloon.frozen = false
        balloon.rising = false
        balloon.pacerPhaseAt = null   // the night stays until the next session starts
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ranksButton.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        controls.visibility = View.GONE
        if (cue && foreground) endCue()   // the timer can end while you are in another app: no blind buzz there
        val rfBest = summary.rfBest
        if (wasSearch) {
            settings.rfSearch = false
            if (summary.rfConverged && rfBest != null) settings.lastRfTenths = (rfBest * 10).toInt()
        }
        styleStart()
        // An unfinished search records its best probe, never the rate it started from.
        val recordRate = if (!wasSearch) finalRate else rfBest ?: summary.rfProbes.maxByOrNull { it.second }?.first ?: settings.rateBpm
        SessionStore(this).append(
            SessionRecord(
                startWall, summary.durationMs, recordRate, summary.meanP, summary.meanShape,
                summary.stars, summary.meanHr, summary.rejectedPct, summary.validMs, summary.rmssd, summary.sdnn, summary.beats,
                if (!wasSearch) SessionRecord.KIND_TRAINING else if (summary.rfConverged) SessionRecord.KIND_RF_SEARCH else SessionRecord.KIND_RF_INCOMPLETE,
                summary.rfProbes,
            ),
        )
        if (csvName.isNotEmpty()) Toast.makeText(this, "CSV saved: Download/HRV/$csvName", Toast.LENGTH_LONG).show()
        main.postDelayed(landing, if (foreground) LANDING_MS else 0)
    }

    private fun cue(inhale: Boolean) {
        if (!settings.vibrate || !foreground) return
        vibrator.vibrate(
            if (inhale) VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
            else VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30), -1),
        )
    }

    private fun endCue() {
        if (settings.vibrate) vibrator.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** One idiom throughout: section header, then rows of label-left / control-right (stepper, segmented choice, ON/OFF switch). */
    private fun showSettings() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val refreshers = ArrayList<() -> Unit>()
        fun refresh() = refreshers.forEach { it() }
        var dialog: AlertDialog? = null

        fun header(t: String) = TextView(this).apply {
            text = up(t); textSize = 12f; setTextColor(YELLOW); letterSpacing = 0.12f; setPadding(0, px(16), 0, px(2))
        }
        fun row(label: String, control: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(52)
            if (control is Switch) control.contentDescription = label   // read as "<label>, switch, on", not "ON, on"
            addView(TextView(this@MainActivity).apply { text = label; textSize = 15f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(control, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        fun small(label: String) = Button(this).apply { text = label; secondary(); minWidth = px(48); minimumWidth = px(48) }
        /** Tap steps once; holding repeats every 120 ms after 400 ms. */
        fun repeating(label: String, step: () -> Unit) = small(label).apply {
            var repeat: Runnable? = null
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        step(); refresh()
                        repeat = object : Runnable { override fun run() { step(); refresh(); main.postDelayed(this, 120) } }
                        main.postDelayed(repeat!!, 400)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { repeat?.let { main.removeCallbacks(it) }; v.performClick() }
                }
                true
            }
        }
        fun stepper(label: String, value: () -> String, onMinus: () -> Unit, onPlus: () -> Unit): View {
            val v = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; minWidth = px(76) }
            refreshers += { v.text = value() }
            return LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(repeating("−", onMinus).apply { contentDescription = "$label, decrease" })
                addView(v)
                addView(repeating("+", onPlus).apply { contentDescription = "$label, increase" })
            }
        }
        fun choice(options: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): View {
            val buttons = options.mapIndexed { i, o -> small(o).apply { minWidth = px(60); setOnClickListener { onSelect(i); refresh() } } }
            refreshers += {
                buttons.forEachIndexed { i, b ->
                    val on = i == selected()
                    b.backgroundTintList = ColorStateList.valueOf(if (on) YELLOW else 0xFF1E2C66.toInt())
                    b.setTextColor(if (on) NAVY else PALE)
                }
            }
            return LinearLayout(this).apply { buttons.forEach { addView(it) } }
        }
        fun onOff(initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
            val checked = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(checked, intArrayOf(YELLOW, 0xFF8AA0C0.toInt()))
            trackTintList = ColorStateList(checked, intArrayOf(0xFF8A7420.toInt(), 0xFF3A4660.toInt()))
            text = if (initial) "ON  " else "OFF  "
            isChecked = initial
            setOnCheckedChangeListener { _, v -> text = if (v) "ON  " else "OFF  "; onChange(v) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), 0, px(20), px(8))
            addView(header("Breathing"))
            addView(row("Rate, breaths/min", stepper("Rate", { String.format(Locale.ROOT, "%.1f", settings.rateBpm) },
                { settings.rateTenths = stepRateTenths(settings.rateTenths, -1) }, { settings.rateTenths = stepRateTenths(settings.rateTenths, 1) })))
            if (settings.lastRfTenths > 0) {
                addView(row(String.format(Locale.ROOT, "Resonance found: %.1f /min", settings.lastRfTenths / 10.0), small("Use").apply {
                    setOnClickListener { settings.rateTenths = settings.lastRfTenths; refresh() }
                }))
            }
            addView(row("Inhale : exhale", choice(listOf("40:60", "50:50"), { if (settings.inhale5050) 1 else 0 }) { settings.inhale5050 = it == 1 }))
            addView(header("Session"))
            addView(row("Vibration cues", onOff(settings.vibrate) { settings.vibrate = it }))
            addView(row("Find my resonance rate\n(next session only)", onOff(settings.rfSearch) { settings.rfSearch = it }))
            addView(header("Balloon"))
            addView(row("Lift threshold\n(heart-rate swing, beats/min)", stepper("Lift threshold", { "${settings.floorBpm}" },
                { settings.floorBpm = (settings.floorBpm - 1).coerceIn(0, settings.ceilingBpm - 5) }, { settings.floorBpm = (settings.floorBpm + 1).coerceIn(0, settings.ceilingBpm - 5) })))
            addView(row("Sky ceiling\n(heart-rate swing, beats/min)", stepper("Sky ceiling", { "${settings.ceilingBpm}" },
                { settings.ceilingBpm = (settings.ceilingBpm - 1).coerceIn(settings.floorBpm + 5, 40) }, { settings.ceilingBpm = (settings.ceilingBpm + 1).coerceIn(settings.floorBpm + 5, 40) })))
            addView(header("Data"))
            addView(row("Save CSV to Download/HRV", onOff(settings.saveCsv) { settings.saveCsv = it }))
            val forget = small("Forget").apply {
                isEnabled = settings.deviceAddress != null
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage("Forget ${settings.deviceName ?: "this strap"} and pair again?")
                        .setPositiveButton("Forget") { _, _ ->
                            dialog?.dismiss()
                            settings.deviceAddress = null
                            settings.deviceName = null
                            client.close()
                            client = HrmClient(this@MainActivity, this@MainActivity)
                            link = Link.NO_DEVICE
                            pick()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            // Rescan keeps the saved strap: picking another replaces it, Cancel leaves the link as it was.
            val rescan = small("Rescan").apply { setOnClickListener { dialog?.dismiss(); pick() } }
            addView(row(settings.deviceName ?: "No strap paired", LinearLayout(this@MainActivity).apply { addView(rescan); addView(forget) }))
            addView(row("What the numbers mean", small("Help").apply { setOnClickListener { showHelp() } }))
        }
        refresh()
        dialog = AlertDialog.Builder(this).setTitle("Settings")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null)
            .setOnDismissListener { styleStart(); renderStatus() }   // Back and a tap outside change settings too
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("What the numbers mean").setMessage(
            "P — how far your heart rate swings within one breath, peak minus trough, in beats per minute. A breath that takes you " +
                "from 62 to 71 beats/min is a swing of 9. The resonance-breathing signal itself.\n\n" +
                "Rhythm — how much of that swing is a clean sine at the pacer's rate (0–100 %). Depth without rhythm earns little lift.\n\n" +
                "Lift — P between the lift threshold and the sky ceiling, scaled by rhythm. Stars fall each time the balloon touches the ceiling.\n\n" +
                "Trace — your heart rate over the last 30 s. Light bands are inhales, dark bands exhales; the band at the right edge is the breath you are in.\n\n" +
                "Stars — Ranks compare the number of stars earned in each session. Sessions rank after 2 min of valid signal; " +
                "equal star totals are ordered newest first.\n\n" +
                "Search score — mean P × mean rhythm, used to compare breathing rates during a resonance search.\n\n" +
                "Breathe gently and comfortably; do not force deeper breaths to lift the balloon. Stop pacing if you feel dizzy or " +
                "uncomfortable. The strap does not measure breathing depth or carbon dioxide.\n\n" +
                "Find my resonance rate — a separate search mode that tries rates between 4 and 7 /min " +
                "during one 15-min session (about 5 breaths each) and " +
                "picks the one with the biggest, cleanest swing. It only reports a rate when its estimate has settled. The card shows the " +
                "model's peak (the rate it would pick) next to the single best probe; they can differ by a step.",
        ).setPositiveButton("OK", null).show()
    }

    // ---- connection ----

    private fun hasPermissions() = PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) { connectOrPick(); return }
        link = Link.NO_PERMISSION
        renderStatus()
        val permanent = !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        AlertDialog.Builder(this)
            .setMessage(if (permanent) "Bluetooth permission was denied. Allow \"Nearby devices\" in the app settings." else "Bluetooth permission is required to read the strap.")
            .setPositiveButton(if (permanent) "Open settings" else "Retry") { _, _ ->
                if (permanent) startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                else requestPermissions(PERMS, REQ_PERMS)
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BT -> if (resultCode == RESULT_OK) connectOrPick() else { link = Link.BT_OFF; renderStatus() }
            REQ_RANKS -> if (resultCode == RESULT_OK) data?.getDoubleExtra(RankActivity.EXTRA_USE_RATE, 0.0)?.takeIf { it > 0 }?.let { useRate(it) }
        }
    }

    private fun connectOrPick() {
        if (!hasPermissions()) { link = Link.NO_PERMISSION; renderStatus(); return }
        if (!client.bluetoothEnabled) {
            link = Link.BT_OFF
            renderStatus()
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
            } catch (_: SecurityException) {
                link = Link.NO_PERMISSION
                renderStatus()
            }
            return
        }
        val address = settings.deviceAddress
        if (address == null) pick() else connectTo(address)
    }

    private fun connectTo(address: String) {
        latestHr = null
        link = Link.CONNECTING
        renderStatus()
        client.connect(address)
    }

    private fun pick() {
        if (!hasPermissions() || !client.bluetoothEnabled) { connectOrPick(); return }
        val dp = resources.displayMetrics.density
        devices.clear()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        scanAdapter = adapter
        val hint = TextView(this).apply { textSize = 14f; setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt()) }
        scanHint = hint
        val list = ListView(this).apply { this.adapter = adapter }
        var dialog: AlertDialog? = null
        list.setOnItemClickListener { _, _, i, _ ->
            val address = devices.keys.elementAt(i)
            settings.deviceAddress = address
            settings.deviceName = devices[address]
            client.stopScan()   // before the dialog's dismiss arrives: no connect under a running scan
            dialog?.dismiss()
            connectTo(address)
        }
        val timeout = Runnable {
            if (devices.isEmpty()) hint.text = "No strap found. Wear it and wet the electrodes. A strap that is connected to another app or phone " +
                "(the old HRV app, a watch) does not advertise: disconnect it there first, then Rescan."
        }
        var lastScan = 0L
        fun scan() {
            hint.text = "Scanning… wear the strap and wet the electrodes."
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, SCAN_TIMEOUT_MS)
            // Android refuses more than five scan starts in 30 s; a quick second tap just keeps the running scan.
            val now = SystemClock.elapsedRealtime()
            if (now - lastScan < 6_000) return
            lastScan = now
            client.stopScan()
            client.startScan()
        }
        val before = link
        link = Link.SCANNING
        renderStatus()
        dialog = AlertDialog.Builder(this)
            .setTitle("Pick heart-rate strap")
            .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(hint); addView(list) })
            .setNeutralButton("Rescan", null)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                client.stopScan()
                main.removeCallbacks(timeout)
                // Nothing picked. A live link survived the scan (CONNECTED stays CONNECTED); a pending connect was dropped
                // by the scan, so start it again for the saved strap.
                if (link == Link.SCANNING) {
                    val saved = settings.deviceAddress
                    if (saved == null) { link = Link.NO_DEVICE; renderStatus() }
                    else if (before == Link.CONNECTED) { link = before; renderStatus() }
                    else connectTo(saved)
                }
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { scan() }
        scan()
    }

    override fun onScanResult(address: String, name: String?) {
        if (address in devices) return
        devices[address] = name
        scanAdapter?.add("${name ?: "unnamed strap"}\n$address")
        scanHint?.text = "Tap your strap:"
    }

    override fun onScanProgress(seen: Int) {
        if (devices.isEmpty()) scanHint?.text = "Scanning… $seen Bluetooth device${if (seen == 1) "" else "s"} nearby, no heart-rate strap yet. Wear it and wet the electrodes."
    }

    override fun onScanFailed(code: Int) {
        scanHint?.text = "Scan failed (code $code). Turn Bluetooth off and on again, then Rescan."
    }

    override fun onBle(note: String) {
        renderStatus()
    }

    override fun onLink(connected: Boolean) {
        latestHr = null
        link = if (connected) Link.CONNECTED else Link.CONNECTING
        connectedAt = if (connected) SystemClock.elapsedRealtime() else 0L
        engine?.onLink(connected, sessionNow())
        renderStatus()
    }

    override fun onBluetoothOn() {
        if (link == Link.BT_OFF) connectOrPick()
    }

    override fun onPacket(nowMs: Long, packet: HrsPacket) {
        latestHr = packet.hrBpm
        lastHrAt = nowMs
        if (packet.rr1024.isNotEmpty()) lastRrAt = nowMs
        engine?.onPacket(nowMs - startElapsed, packet)
    }

    private companion object {
        val PERMS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val NULL_STREAM = object : OutputStream() { override fun write(b: Int) {} }
        const val REQ_PERMS = 1
        const val REQ_BT = 2
        const val REQ_RANKS = 3
        const val LANDING_MS = 1500L
        const val GRACE_MS = 60_000L
        const val CONTROLS_MS = 4_000L
        const val SCAN_TIMEOUT_MS = 20_000L
        const val NAVY = 0xFF0B1030.toInt()
        const val PALE = 0xFFDCE6F5.toInt()
        const val YELLOW = 0xFFFFD83B.toInt()
        const val RED = 0xFFD63A3A.toInt()
        const val SLATE = 0xFF2E3A5C.toInt()
        const val SLATE_TEXT = 0xFF6B7A99.toInt()
    }
}
