package com.hrvballoon.core

/** Whole seconds to the warm-up boundary, rounded up (spec 3.5): 59 001 ms shows 60. */
fun warmupSeconds(boundaryRemainingMs: Long): Long = (boundaryRemainingMs + 999) / 1000

/** [drawn] goes on the HUD line, [spoken] to TalkBack. */
class StatusText(val drawn: String, val spoken: String)

/**
 * [word] is the status's own text. In WARMUP the drawn text counts down but the spoken text is the fixed [warmupSpoken],
 * so the live region changes (and TalkBack speaks) once on entry and not with every second (spec 3.5).
 */
fun statusText(status: SessionStatus, word: String, boundaryRemainingMs: Long, warmupSpoken: String) =
    if (status == SessionStatus.WARMUP) StatusText("$word ${warmupSeconds(boundaryRemainingMs)}", warmupSpoken)
    else StatusText(word, word)
