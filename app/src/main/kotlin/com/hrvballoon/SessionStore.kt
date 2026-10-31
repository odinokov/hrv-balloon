package com.hrvballoon

import android.content.Context
import com.hrvballoon.core.SessionRecord
import java.io.File

/** Session history: one SessionRecord line per finished session in app-private storage. */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "sessions.txt")

    fun append(record: SessionRecord) = file.appendText(record.toLine() + "\n")

    fun load(): List<SessionRecord> = if (file.exists()) file.readLines().mapNotNull(SessionRecord::fromLine) else emptyList()
}
