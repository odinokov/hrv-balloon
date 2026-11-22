package com.hrvballoon.ui

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
}
