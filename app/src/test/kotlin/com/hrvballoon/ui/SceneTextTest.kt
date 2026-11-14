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

    @Test fun quotedResourcesKeepTheirSpaces() {
        assertEquals("MOST STARS  #1 OF %d", TestText.scene.mostStars)
        assertEquals("ON  ", TestText.strings["switch_on"])
    }
}
