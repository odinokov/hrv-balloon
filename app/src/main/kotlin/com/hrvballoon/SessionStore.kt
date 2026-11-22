package com.hrvballoon

import com.hrvballoon.core.SessionHistory
import com.hrvballoon.core.SessionRecord
import java.io.File
import java.io.IOException

/**
 * Session history in [dir] (app-private storage): sessions.csv in the [SessionHistory] format. An old sessions.txt
 * (version 1) is migrated once, through a temporary file, and kept as sessions.v1.txt.
 */
class SessionStore(private val dir: File) {
    private val file = File(dir, "sessions.csv")

    fun load(): List<SessionRecord> {
        migrate()
        return if (file.exists()) SessionHistory.parse(file.readText()) else emptyList()
    }

    fun append(record: SessionRecord) {
        migrate()
        if (!file.exists() || file.length() == 0L) file.writeText(SessionHistory.header())
        else if (!file.readText().endsWith("\n")) file.appendText("\n")   // a cut-off row must not swallow this one
        file.appendText(SessionHistory.row(record) + "\n")
    }

    /** As [append], for rows saved mid-session and at its end: a failed write (storage full) must not crash or end the session. */
    fun checkpoint(record: SessionRecord) = try { append(record); true } catch (_: IOException) { false }

    private fun migrate() {
        val legacy = File(dir, "sessions.txt")
        if (file.exists() || !legacy.exists()) return
        val tmp = File(dir, "sessions.csv.tmp")
        tmp.writeText(SessionHistory.write(SessionHistory.parse(legacy.readText())))
        if (tmp.renameTo(file)) legacy.renameTo(File(dir, "sessions.v1.txt"))
    }
}
