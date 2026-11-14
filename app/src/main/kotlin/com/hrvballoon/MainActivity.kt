package com.hrvballoon

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings as SysSettings
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.hrvballoon.ble.HrmClient
import com.hrvballoon.core.Bounce
import com.hrvballoon.core.HrsPacket
import com.hrvballoon.core.LostReason
import com.hrvballoon.core.RfEstimate
import com.hrvballoon.core.RfSearch
import com.hrvballoon.core.SessionConfig
import com.hrvballoon.core.SessionCsvWriter
import com.hrvballoon.core.SessionEngine
import com.hrvballoon.core.SessionMeta
import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.SessionStatus
import com.hrvballoon.core.Sex
import com.hrvballoon.core.Signal
import com.hrvballoon.core.Smoother
import com.hrvballoon.core.StarCounter
import com.hrvballoon.core.UiState
import com.hrvballoon.core.hasuoEstimate
import com.hrvballoon.core.rhythmLimited
import com.hrvballoon.core.sessionOutcome
import com.hrvballoon.core.sessionStatus
import com.hrvballoon.core.starSlot
import com.hrvballoon.ui.BalloonView
import com.hrvballoon.ui.Chrome.NAVY
import com.hrvballoon.ui.Chrome.RED
import com.hrvballoon.ui.Chrome.SLATE
import com.hrvballoon.ui.Chrome.SLATE_TEXT
import com.hrvballoon.ui.Chrome.YELLOW
import com.hrvballoon.ui.Hud
import com.hrvballoon.ui.MediaStoreCsv
import com.hrvballoon.ui.PixelScene
import com.hrvballoon.ui.SceneText
import com.hrvballoon.ui.SettingsWidgets
import com.hrvballoon.ui.secondary
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity(), HrmClient.Listener {
    private lateinit var client: HrmClient
    private lateinit var settings: Settings
    private lateinit var balloon: BalloonView
    private lateinit var startStop: Button
    private lateinit var ranksButton: Button
    private lateinit var settingsButton: Button
    private lateinit var controls: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var picker: StrapPicker? = null
    private val vibrator by lazy { (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator }

    private var link = Link.NO_DEVICE
    private var connectedAt = 0L
    private var lastRrAt = 0L
    private var latestHr: Int? = null
    private var lastHrAt = 0L
    private var foreground = false

    private var engine: SessionEngine? = null
    private var sessionEstimate: Double? = null
    private val sceneText by lazy { SceneText { resources.getString(it) } }
    private var backGuard: Any? = null   // OnBackInvokedCallback on Android 13+, set while a session runs
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
    /** The engine has a live swing estimate; stars are paid only then, never for the height held while settling. */
    private var live = false
    /** Rhythm, not swing, is holding the balloon down (with hysteresis); shown as the Steady label. */
    private var steady = false
    private var pendingResult: Long? = null
    private var controlsUntil = 0L
    private var leftAtMs = 0L
    private var lastDescription = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(AndroidPrefs(this))
        buildUi()
        client = HrmClient(this, this)
        if (hasPermissions()) connectOrPick() else requestPermissions(PERMS, REQ_PERMS)
        main.post(ticker)
        CrashLog(filesDir).apply { install(); take()?.let { showCrash(it) } }
    }

    private fun showCrash(report: String) {
        AlertDialog.Builder(this).setTitle(R.string.crash_title).setMessage(report.lines().take(12).joinToString("\n"))
            .setPositiveButton(R.string.copy) { _, _ -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", report)) }
            .setNegativeButton(R.string.dismiss, null).show()
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
            Toast.makeText(this, R.string.ended_while_away, Toast.LENGTH_LONG).show()
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

    /** Android 12 only: from Android 13 the manifest routes Back to [guardBack]'s callback (Android 16 never calls this). */
    @SuppressLint("GestureBackNavigation")   // reached on Android 12 only; 13+ use the OnBackInvokedCallback
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (engine != null) confirmStop() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Android 13+: Back during a session asks before ending it, and leaves the app normally otherwise. */
    private fun guardBack(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        (backGuard as OnBackInvokedCallback?)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backGuard = if (!on) null else OnBackInvokedCallback { confirmStop() }.also {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
        }
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
            contentDescription = getString(R.string.a11y_scene)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            onTap = { yf -> if (engine != null) showControls() else if (yf < 0.12f) onStripTap() }
        }
        startStop = Button(this).apply {
            setText(R.string.start); isEnabled = false
            setOnClickListener { if (engine != null) confirmStop() else if (settings.rfSearch) askEstimate(::startSession) else startSession(null) }
        }
        styleStart()
        ranksButton = Button(this).apply { setText(R.string.ranks); secondary(); setOnClickListener { openRanks(-1, result = false) } }
        settingsButton = Button(this).apply { setText(R.string.settings); secondary(); setOnClickListener { showSettings() } }
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
        val root = FrameLayout(this).apply {
            addView(balloon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
        edgeToEdge(root)
    }

    /** Enabled = yellow, disabled = slate, running = red with white text (4.6:1): unmistakable regardless of the OEM skin. */
    private fun styleStart() {
        val running = engine != null
        startStop.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(if (running) RED else YELLOW, SLATE),
        )
        startStop.setTextColor(if (running) 0xFFFFFFFF.toInt() else if (startStop.isEnabled) NAVY else SLATE_TEXT)
        if (!running) startStop.text = if (settings.rfSearch) getString(R.string.find_my_rate) else fmt(R.string.start_at, settings.rateBpm)
    }

    /** Secondary buttons dim visibly when disabled. */
    /** A stray tap can't end a session: Stop asks first (tap outside = keep going). */
    private fun confirmStop() {
        AlertDialog.Builder(this).setMessage(R.string.end_session_q)
            .setPositiveButton(R.string.end) { _, _ -> stopSession(cue = true) }
            .setNegativeButton(R.string.keep_going, null).show()
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
            val status = getString(linkStatusRes(link, rrFresh, neverRr))
            balloon.hud = if (rrFresh) Hud.session(sceneText, dot, status, freshHr(now), settings.rateBpm, mmss(settings.durationMs)) else Hud(dot, status)
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
        live = !s.warmingUp
        if (!frozen) target = s.balloonTarget
        balloon.frozen = frozen
        val lost = when (s.lostReason) {
            LostReason.LINK -> listOf(R.string.hud_lost_link, R.string.hud_lost_reconnecting)
            LostReason.CONTACT -> listOf(R.string.hud_lost_contact, R.string.hud_lost_wet)
            LostReason.STALE -> listOf(R.string.hud_lost_stale, R.string.hud_lost_check)
            LostReason.GAP -> listOf(R.string.hud_lost_gap, R.string.hud_lost_reconnecting)
            LostReason.REJECTS -> listOf(R.string.hud_lost_rejects, R.string.hud_lost_sit_still)
            null -> emptyList()
        }.map { getString(it) }
        steady = settings.shapeGate && !s.warmingUp &&
            rhythmLimited(s.p2tBpm, s.shape, settings.floorBpm.toDouble(), steady)
        val status = when (sessionStatus(s, steady)) {
            SessionStatus.DONE -> getString(R.string.hud_done)
            SessionStatus.NO_SIGNAL -> getString(R.string.hud_no_signal)
            SessionStatus.SEARCH -> getString(R.string.hud_search)
            SessionStatus.SETTLING -> getString(R.string.hud_settling)
            SessionStatus.STEADY -> getString(R.string.hud_steady)
            SessionStatus.NONE -> ""
        }
        balloon.hud = Hud.session(sceneText, hudDot, status, if (frozen) null else freshHr(), s.rateBpm, mmss(s.remainingMs ?: s.elapsedMs), lost)
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
                    if (starCounter.update(h, starSlot(t), live)) {
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

    /** Visible text with numbers: Locale.ROOT, like the pixel HUD, so decimals look the same everywhere. */
    private fun fmt(id: Int, vararg args: Any?) = String.format(Locale.ROOT, getString(id), *args)

    private fun mmss(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60_000, ms / 1000 % 60)

    private fun earnStar(now: Long) {
        engine?.onStar(sessionNow())
        settings.lifetimeStars++
        falling += now
        balloon.stars = starCounter.count
        balloon.announceForAccessibility(getString(R.string.a11y_star))
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
        Toast.makeText(this, fmt(R.string.rate_set, settings.rateBpm), Toast.LENGTH_SHORT).show()
    }

    /** [estimate]: a search's height-and-sex start; the search begins there and ends there if it finds no peak. */
    private fun startSession(estimate: RfEstimate?) {
        val startRate = estimate?.rateBpm ?: settings.rateBpm
        val now = OffsetDateTime.now()
        csvName = if (settings.saveCsv) "hrv_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)) + ".csv" else ""
        val csv = if (!settings.saveCsv) SessionCsvWriter(NULL_STREAM) else try {
            SessionCsvWriter(MediaStoreCsv.open(this, csvName))
        } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage(R.string.csv_failed)
                .setPositiveButton(R.string.ok, null).show()
            return
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        csv.writeHeader(
            SessionMeta(
                version, settings.deviceAddress ?: "?", settings.deviceName, now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                startRate, settings.inhaleFraction, settings.durationLabel, settings.floorBpm.toDouble(), settings.ceilingBpm.toDouble(),
                estimate?.rateBpm,
            ),
        )
        startElapsed = SystemClock.elapsedRealtime()
        startWall = System.currentTimeMillis()
        engine = SessionEngine(
            SessionConfig(
                startRate, settings.inhaleFraction, settings.durationMs, settings.floorBpm.toDouble(), settings.ceilingBpm.toDouble(),
                settings.shapeGate, settings.rfSearch, estimate?.rateBpm ?: RfSearch.FALLBACK_BPM,
            ),
            csv,
        )
        guardBack(true)
        smoother = Smoother(settings.responseMs)
        starCounter = StarCounter()
        live = false
        steady = false
        falling.clear()
        balloon.stars = 0
        balloon.ceilingHitAtMs = null
        balloon.falling = falling
        balloon.traceT = DoubleArray(0)
        balloon.traceHr = DoubleArray(0)
        balloon.forecast = null
        balloon.pacerPeriodMs = 60_000 / startRate
        balloon.inhaleFraction = settings.inhaleFraction
        target = 0.0
        frozen = false
        lastInhale = null
        balloon.progress = 0.0   // a new session starts at golden hour
        balloon.running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startStop.setText(R.string.stop)
        styleStart()
        ranksButton.visibility = View.GONE
        settingsButton.visibility = View.GONE
        showControls()   // visible for the first few seconds so the reveal gesture is learned
        sessionEstimate = estimate?.rateBpm
        estimate?.let { Toast.makeText(this, fmt(if (it.extrapolated) R.string.rf_estimate_outside else R.string.rf_estimate_start, it.rateBpm), Toast.LENGTH_LONG).show() }
    }

    /** Asks height and sex each time a search starts (nothing stored); Skip starts at the current rate. */
    private fun askEstimate(onStart: (RfEstimate?) -> Unit) {
        val ui = SettingsWidgets(this, main)
        val height = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "170"
            minEms = 4
            contentDescription = getString(R.string.height_cm)
        }
        var sex: Sex? = null
        val sexChoice = ui.choice(listOf(getString(R.string.sex_male), getString(R.string.sex_female)), { if (sex == null) -1 else sex!!.ordinal }) {
            sex = Sex.entries[it]
        }
        ui.refresh()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(20), ui.px(8), ui.px(20), 0)
            addView(TextView(this@MainActivity).apply { setText(R.string.rf_estimate_message) })
            addView(ui.row(getString(R.string.height_cm), height))
            addView(ui.row(getString(R.string.sex), sexChoice))
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.rf_estimate_title).setView(content)
            .setPositiveButton(R.string.start, null)
            .setNeutralButton(R.string.skip) { _, _ -> onStart(null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cm = height.text.toString().replace(',', '.').toDoubleOrNull()
            val chosen = sex
            if (cm == null || cm !in 130.0..210.0 || chosen == null) {
                Toast.makeText(this, R.string.rf_estimate_invalid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onStart(hasuoEstimate(cm, chosen))
        }
    }

    /** [cue] = long vibration: Stop button and timer end, not a session cut short by leaving the app. */
    private fun stopSession(cue: Boolean, endAtMs: Long = sessionNow()) {
        val e = engine ?: return
        val summary = e.stop(endAtMs)
        val wasSearch = settings.rfSearch
        val finalRate = e.pacer.rateAt(endAtMs.toDouble())   // exclude any change still waiting for the next inhale
        engine = null
        guardBack(false)
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
        val outcome = sessionOutcome(wasSearch, finalRate, settings.rateBpm, summary.rfBest, summary.rfConverged, summary.rfNoPeak, summary.rfProbes)
        if (wasSearch) settings.rfSearch = false
        outcome.offerTenths?.let { settings.lastRfTenths = it; settings.lastRfNoPeak = outcome.kind == SessionRecord.KIND_RF_NO_PEAK }
        styleStart()
        SessionStore(filesDir).append(
            SessionRecord(
                startWall, summary.durationMs, outcome.rateBpm, summary.meanP, summary.meanShape,
                summary.stars, summary.meanHr, summary.rejectedPct, summary.validMs, summary.rmssd, summary.sdnn, summary.beats,
                outcome.kind,
                summary.rfProbes,
                if (wasSearch) sessionEstimate else null,
            ),
        )
        if (csvName.isNotEmpty()) Toast.makeText(this, getString(R.string.csv_saved, csvName), Toast.LENGTH_LONG).show()
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
    private fun showSettings() = SettingsDialog(this, settings, main, object : SettingsDialog.Actions {
        override fun forgetStrap() {
            settings.deviceAddress = null
            settings.deviceName = null
            client.close()
            client = HrmClient(this@MainActivity, this@MainActivity)
            link = Link.NO_DEVICE
            pick()
        }
        override fun rescan() = pick()
        override fun showHelp() = this@MainActivity.showHelp()
        override fun exportHistory() = this@MainActivity.exportHistory()
        override fun closed() { styleStart(); renderStatus() }
    }).show()

    /** The session history as a CSV in Download/HRV. */
    private fun exportHistory() {
        val name = "hrv_sessions_" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)) + ".csv"
        val message = try {
            SessionStore(filesDir).export(MediaStoreCsv.open(this, name))
            getString(R.string.history_saved, name)
        } catch (e: Exception) {
            getString(R.string.history_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle(R.string.help_title).setMessage(R.string.help_text).setPositiveButton(R.string.ok, null).show()
    }

    // ---- connection ----

    private fun hasPermissions() = PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) { connectOrPick(); return }
        link = Link.NO_PERMISSION
        renderStatus()
        val permanent = !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        AlertDialog.Builder(this)
            .setMessage(if (permanent) R.string.perm_denied else R.string.perm_required)
            .setPositiveButton(if (permanent) R.string.open_settings else R.string.retry) { _, _ ->
                if (permanent) startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                else requestPermissions(PERMS, REQ_PERMS)
            }
            .setNegativeButton(R.string.cancel, null).show()
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
        val before = link
        link = Link.SCANNING
        renderStatus()
        picker = StrapPicker(
            this, main,
            startScan = { client.stopScan(); client.startScan() },
            stopScan = { client.stopScan() },
            onPick = { address, name -> settings.deviceAddress = address; settings.deviceName = name; connectTo(address) },
            onClosed = {
                picker = null
                // Nothing picked. A live link survived the scan (CONNECTED stays CONNECTED); a pending connect was dropped
                // by the scan, so start it again for the saved strap.
                if (link == Link.SCANNING) {
                    val saved = settings.deviceAddress
                    if (saved == null) { link = Link.NO_DEVICE; renderStatus() }
                    else if (before == Link.CONNECTED) { link = before; renderStatus() }
                    else connectTo(saved)
                }
            },
        ).also { it.show() }
    }

    override fun onScanResult(address: String, name: String?) { picker?.found(address, name) }

    override fun onScanProgress(seen: Int) { picker?.progress(seen) }

    override fun onScanFailed(code: Int) { picker?.failed(code) }

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
    }
}
