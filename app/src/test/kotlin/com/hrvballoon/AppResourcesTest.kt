package com.hrvballoon

import com.hrvballoon.ui.TestText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Manifest and text rules that only show on a phone when they are broken. Unit tests run in the app/ directory. */
class AppResourcesTest {
    /**
     * An activity restarted for a configuration change loses the running session, and the app has no resources that
     * depend on any of them, so both activities handle them all (connecting a keyboard changes `keyboard` and `navigation`).
     */
    @Test fun noConfigurationChangeRestartsAnActivity() {
        val all = setOf(
            "mcc", "mnc", "locale", "touchscreen", "keyboard", "keyboardHidden", "navigation", "orientation", "screenLayout",
            "uiMode", "screenSize", "smallestScreenSize", "density", "layoutDirection", "colorMode", "fontScale",
            "fontWeightAdjustment", "grammaticalGender",
        )
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        assertEquals(2, activities.length)
        for (i in 0 until activities.length) {
            val a = activities.item(i).attributes
            val handled = a.getNamedItem("android:configChanges").nodeValue.split("|").toSet()
            assertEquals(a.getNamedItem("android:name").nodeValue, emptySet<String>(), all - handled)
        }
    }

    /** Spec 9.2.1: the help names the 20-minute search, not the old 18. */
    @Test fun helpNamesTheTwentyMinuteSearch() {
        val help = TestText.strings.getValue("help_text")
        assertTrue("20-min" in help)
        assertTrue("18-min" !in help)
    }

    /** Spec 9.7.4: the help covers the warm-up, the 20-minute search and the star maximum. */
    @Test fun helpCoversTheWarmUpAndTheStarMaximum() {
        val help = TestText.strings.getValue("help_text")
        val steady = help.indexOf("\n\nSteady — ")
        val warm = help.indexOf("\n\nWarm — ")
        val lift = help.indexOf("\n\nLift — ")
        assertTrue(steady in 0 until warm && warm < lift)
        assertTrue("about 6 lower" in help)
        assertTrue("after the warm-up" in help)
        assertTrue("2 min with a live estimate" in help)
        assertTrue("20-min" in help)
    }

    @Test fun helpOpensWithTheMedicalNotice() {
        assertTrue(
            TestText.strings.getValue("help_text").startsWith(
                "Not a medical device. It does not diagnose, treat, cure or prevent any medical condition. " +
                    "Ask a healthcare professional for medical advice.\n\n",
            ),
        )
    }
}
