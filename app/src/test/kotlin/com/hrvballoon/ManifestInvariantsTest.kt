package com.hrvballoon

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Build and manifest invariants: no INTERNET, no third-party runtime dependency, fixed SDK levels. Unit tests run in the app/ directory. */
class ManifestInvariantsTest {
    @Test fun onlyTheThreePermissions() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val perms = doc.getElementsByTagName("uses-permission")
        val names = (0 until perms.length).map { perms.item(it).attributes.getNamedItem("android:name").nodeValue }
        assertEquals(
            setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.VIBRATE"),
            names.toSet(),
        )
        assertEquals(names.toSet().size, names.size)
        assertEquals(0, doc.getElementsByTagName("uses-permission-sdk-23").length)
    }

    private fun gradle(path: String) = File(path).readText().replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//.*"), "")

    private fun dependencies(path: String) =
        gradle(path).substringAfter("dependencies {").lines().map { it.trim() }.filter { "(" in it }

    @Test fun noDependencyBesidesJunitAndCore() {
        assertEquals(listOf("implementation(project(\":core\"))", "testImplementation(\"junit:junit:4.13.2\")"), dependencies("build.gradle.kts"))
        assertEquals(listOf("testImplementation(\"junit:junit:4.13.2\")"), dependencies("../core/build.gradle.kts"))
    }

    @Test fun sdkLevels() {
        val gradle = gradle("build.gradle.kts")
        assertEquals(listOf("31"), Regex("""minSdk = (\d+)""").findAll(gradle).map { it.groupValues[1] }.toList())
        assertEquals(listOf("36"), Regex("""targetSdk = (\d+)""").findAll(gradle).map { it.groupValues[1] }.toList())
    }
}
