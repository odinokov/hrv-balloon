package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatusTest {
    private fun state(finished: Boolean = false, signal: Signal = Signal.OK, warmingUp: Boolean = false, probe: RfProbe? = null, boundaryMs: Long = 0) =
        UiState(0, signal, null, 60, null, null, null, warmingUp, 0, 0.0, true, 0.0, 0, null, finished,
            DoubleArray(0), DoubleArray(0), probe, 5.5, null, boundaryMs)

    private val searching = RfProbe(5.5, 1, null, false, 0, false)
    private val done = RfProbe(5.5, 6, 5.5, true, 0, true)

    @Test fun mostImportantLabelWins() {
        assertEquals(SessionStatus.DONE, sessionStatus(state(finished = true, signal = Signal.LOST, warmingUp = true), steady = true))
        assertEquals(SessionStatus.NO_SIGNAL, sessionStatus(state(signal = Signal.LOST, warmingUp = true, probe = searching), steady = true))
        assertEquals(SessionStatus.SEARCH, sessionStatus(state(warmingUp = true, probe = searching), steady = true))
        assertEquals(SessionStatus.SETTLING, sessionStatus(state(warmingUp = true, probe = done), steady = true))
        assertEquals(SessionStatus.STEADY, sessionStatus(state(probe = done), steady = true))
        assertEquals(SessionStatus.NONE, sessionStatus(state(), steady = false))
    }

    /** Spec 3.5: order DONE, NO_SIGNAL, WARMUP, SEARCH, SETTLING, STEADY. */
    @Test fun warmupRanksAfterNoSignalAndBeforeSearchAndSettling() {
        assertEquals(SessionStatus.WARMUP, sessionStatus(state(boundaryMs = 1), steady = false))
        assertEquals(SessionStatus.WARMUP, sessionStatus(state(boundaryMs = 60_000, warmingUp = true, probe = searching), steady = true))
        assertEquals(SessionStatus.DONE, sessionStatus(state(finished = true, boundaryMs = 5), steady = false))
        assertEquals(SessionStatus.NO_SIGNAL, sessionStatus(state(signal = Signal.LOST, boundaryMs = 5), steady = false))
        assertEquals(SessionStatus.SEARCH, sessionStatus(state(probe = searching, boundaryMs = 0), steady = false))
        assertEquals(SessionStatus.NONE, SessionStatus.entries.last())
    }
}
