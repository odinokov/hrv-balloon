package com.hrvballoon.ui

import com.hrvballoon.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** strings.xml as the app reads it, so tests and the app share one English source. Unit tests run in the app/ directory. */
object TestText {
    val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).let { it.attributes.getNamedItem("name").nodeValue to unescape(it.textContent) } }
    }
    private val names by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }

    fun get(id: Int) = strings.getValue(names.getValue(id))

    val scene by lazy { SceneText(::get) }

    /** Android rules: quoted text keeps its spaces, otherwise runs of whitespace collapse; then the backslash escapes. */
    private fun unescape(raw: String): String {
        val s = raw.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it.replace(Regex("\\s+"), " ") }
        return s.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
