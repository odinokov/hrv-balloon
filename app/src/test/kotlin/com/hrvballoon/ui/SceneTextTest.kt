package com.hrvballoon.ui

import com.hrvballoon.core.SessionStatus
import com.hrvballoon.core.statusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SceneTextTest {
    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

    @Test fun everyPixelDrawnStringHasGlyphs() {
        val pixel = TestText.strings.filterKeys { it.startsWith("scene_") || it.startsWith("hud_") }
        assertTrue(pixel.size > 40)
        val missing = pixel.mapValues { (_, v) -> v.replace(placeholder, "").uppercase(Locale.ROOT).filterNot(PixelFont::has) }.filterValues { it.isNotEmpty() }
        assertTrue("characters the pixel font cannot draw: $missing", missing.isEmpty())
    }

    /** Drawn as written: scene text and the lost-signal lines; the other hud_* strings are uppercased by PixelScene (mixed case stays for TalkBack). */
    @Test fun drawnTextUsesOnlyTheScenesCharset() {
        val allowed = Regex("[A-Z0-9 #.%:>/+*^-]*")
        val asDrawn = TestText.strings.filterKeys { it.startsWith("scene_") || it.startsWith("hud_lost_") }
        val uppercased = TestText.strings.filterKeys { it.startsWith("hud_") && !it.startsWith("hud_lost_") }
        assertTrue(asDrawn.size > 20 && uppercased.isNotEmpty())
        val bad = asDrawn.mapValues { (_, v) -> v.replace(placeholder, "") } + uppercased.mapValues { (_, v) -> v.replace(placeholder, "").uppercase(Locale.ROOT) }
        val off = bad.filterValues { !allowed.matches(it) }
        assertTrue("characters outside the drawn charset: $off", off.isEmpty())
    }

    @Test fun quotedResourcesKeepTheirSpaces() {
        assertEquals("MOST STARS  #1 OF %d", TestText.scene.mostStars)
        assertEquals("ON  ", TestText.strings["switch_on"])
    }

    /** Spec 3.5: "WARM 42", at most eight characters, charset glyphs only, and the whole HUD line still fits beside the timer. */
    @Test fun theWarmupStatusDrawsWithinTheCharsetAndFitsTheHudLine() {
        val allowed = Regex("[A-Z0-9 #.%:>/+*^-]*")
        for (s in 1..60) {
            val status = statusText(SessionStatus.WARMUP, TestText.strings.getValue("hud_warm"), s * 1000L, "").drawn.uppercase(Locale.ROOT)
            assertEquals("WARM $s", status)
            assertTrue(status.length <= 8 && allowed.matches(status))
            val line = Hud.session(TestText.scene, Palette.DOT_RED, status, 188, 10.5, "20:00").status.uppercase(Locale.ROOT)
            assertTrue("HUD line $line", allowed.matches(line) && PixelFont.width(line) <= 160 - PixelFont.width("20:00") - 4 - 6 - 12)
        }
    }
}
