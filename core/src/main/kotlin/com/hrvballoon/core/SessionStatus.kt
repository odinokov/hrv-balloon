package com.hrvballoon.core

/** The label in the session status line; the UI maps each to its text. */
enum class SessionStatus { DONE, NO_SIGNAL, WARMUP, SEARCH, SETTLING, STEADY, NONE }

/** Most important first. [steady]: the swing is there but does not follow the pacer (see [rhythmLimited]). */
fun sessionStatus(s: UiState, steady: Boolean): SessionStatus = when {
    s.finished -> SessionStatus.DONE
    s.signal == Signal.LOST -> SessionStatus.NO_SIGNAL
    s.boundaryRemainingMs > 0 -> SessionStatus.WARMUP   // before T: UI reads the engine's boundary, never computes it (spec 3.5)
    s.rfProbe?.finished == false -> SessionStatus.SEARCH
    s.warmingUp -> SessionStatus.SETTLING
    steady -> SessionStatus.STEADY
    else -> SessionStatus.NONE
}
