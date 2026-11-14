package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactFilterTest {
    private fun classify(beats: List<Beat>): List<ClassifiedBeat> {
        val f = ArtifactFilter()
        return beats.flatMap { f.push(it) } + f.flush()
    }

    private fun classify(rr: DoubleArray) = classify(SynthBeats(0.0, rr).beats())

    private fun rejected(c: List<ClassifiedBeat>) = c.withIndex().filter { !it.value.accepted }.map { it.index }.toSet()

    @Test fun cleanSwingsAreNotRejected() {
        for ((hr, p) in listOf(60 to 20, 60 to 30, 70 to 40)) {
            val c = classify(synthBeats(hr.toDouble(), p.toDouble(), 5.5, 300.0).beats())
            assertEquals("HR$hr P$p", emptySet<Int>(), rejected(c))
        }
    }

    @Test fun ectopicPairsRejected() {
        val s = synthBeats(60.0, 30.0, 5.5, 300.0)
        val bad = injectPairs(s.rrMs, 0.6, 1.4)
        assertEquals(bad, rejected(classify(s.beats())))
    }

    @Test fun missedBeatsRejected() {
        val s = synthBeats(60.0, 30.0, 5.5, 300.0)
        val bad = injectMissed(s.rrMs)
        assertEquals(bad, rejected(classify(s.beats())))
    }

    @Test fun outOfRangeRejected() {
        val c = classify(doubleArrayOf(1000.0, 1000.0, 250.0, 1000.0, 1000.0, 2100.0, 1000.0, 1000.0))
        assertEquals(setOf(2, 5), rejected(c))
    }

    @Test fun lagAndOrder() {
        val beats = SynthBeats(0.0, DoubleArray(7) { 1000.0 + it }).beats()
        val f = ArtifactFilter()
        assertTrue(f.push(beats[0]).isEmpty())
        assertTrue(f.push(beats[1]).isEmpty())
        val out = beats.drop(2).flatMap { f.push(it) } + f.flush()
        assertEquals(beats.map { it.rrMs }, out.map { it.beat.rrMs })
    }

    @Test fun fewBeatsAccepted() {
        val c = classify(doubleArrayOf(1000.0, 1300.0))
        assertTrue(c.all { it.accepted })
        val d = classify(doubleArrayOf(2500.0, 1000.0, 2500.0))
        assertFalse(d[0].accepted)
        assertTrue(d[1].accepted)
        assertFalse(d[2].accepted)
    }
}
