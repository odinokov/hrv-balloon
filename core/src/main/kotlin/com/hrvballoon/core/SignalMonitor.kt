package com.hrvballoon.core

enum class Signal { OK, LOST }

enum class LostReason { LINK, CONTACT, STALE, GAP, REJECTS }

/**
 * Signal is LOST when the link is down, the strap reports no skin contact, no RR arrived for > 4 s,
 * the timeline gapped > 3 s, or ≥ 5 consecutive beats were rejected. Deliberately not "no accepted
 * beat for N s": classification lags two beats, so a routine ectopic pair at low HR would false-trip.
 * Recovery needs link + contact + a fresh RR + one accepted beat classified after the loss.
 * Every method returns the new state only when it changed.
 */
class SignalMonitor(startMs: Long) {
    var state = Signal.OK
        private set

    /** Why the signal was last lost (null until the first loss). */
    var reason: LostReason? = null
        private set
    private var linkUp = true
    private var contactOk = true
    private var lastRrMs = startMs
    private var rejectRun = 0
    private var acceptedSinceLoss = false
    private var gapped = false

    fun onLink(connected: Boolean, nowMs: Long): Signal? {
        linkUp = connected
        return evaluate(nowMs)
    }

    fun onNotification(nowMs: Long, contact: Contact, hasRr: Boolean): Signal? {
        if (hasRr) lastRrMs = nowMs
        contactOk = contact != Contact.NOT_DETECTED
        return evaluate(nowMs)
    }

    fun onGap(gapMs: Double, nowMs: Long): Signal? {
        if (gapMs > GAP_MS) gapped = true
        return evaluate(nowMs)
    }

    fun onClassified(accepted: Boolean, nowMs: Long): Signal? {
        if (accepted) { rejectRun = 0; acceptedSinceLoss = true } else rejectRun++
        return evaluate(nowMs)
    }

    fun tick(nowMs: Long): Signal? = evaluate(nowMs)

    private fun evaluate(nowMs: Long): Signal? {
        val stale = nowMs - lastRrMs > STALE_MS
        val cause = when {
            !linkUp -> LostReason.LINK
            !contactOk -> LostReason.CONTACT
            stale -> LostReason.STALE
            gapped -> LostReason.GAP
            rejectRun >= MAX_REJECTS -> LostReason.REJECTS
            else -> null
        }
        val next = when (state) {
            Signal.OK -> if (cause != null) Signal.LOST else Signal.OK
            Signal.LOST -> if (linkUp && contactOk && !stale && acceptedSinceLoss) Signal.OK else Signal.LOST
        }
        gapped = false
        if (next == state) return null
        state = next
        if (next == Signal.LOST) { reason = cause; acceptedSinceLoss = false; rejectRun = 0 }
        return next
    }

    private companion object {
        const val STALE_MS = 4000L
        const val GAP_MS = 3000.0
        const val MAX_REJECTS = 5
    }
}
