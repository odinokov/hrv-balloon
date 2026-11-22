package com.hrvballoon

import com.hrvballoon.core.SessionHistory
import com.hrvballoon.core.SessionRecord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Session history in [dir] (app-private storage): sessions.csv in the [SessionHistory] format. An old sessions.txt
 * (version 1) is migrated once, through a temporary file, and kept as sessions.v1.txt. A version-2 sessions.csv is
 * upgraded the same way and kept as sessions.v2.csv; while the upgrade fails, rows keep the file's own version.
 */
class SessionStore(private val dir: File, private val toV3: (String) -> String? = SessionHistory::toV3) {
    private val file = File(dir, "sessions.csv")

    fun load(): List<SessionRecord> {
        migrate()
        return if (file.exists()) SessionHistory.parse(file.readText()) else emptyList()
    }

    fun append(record: SessionRecord) {
        migrate()
        val text = if (file.exists()) file.readText() else ""
        if (text.isEmpty()) file.writeText(SessionHistory.header())
        else if (!text.endsWith("\n")) file.appendText("\n")   // a cut-off row must not swallow this one
        file.appendText(SessionHistory.row(record, SessionHistory.isV2(text)) + "\n")
    }

    /** As [append], for rows saved mid-session and at its end: a failed write (storage full) must not crash or end the session. */
    fun checkpoint(record: SessionRecord) = try { append(record); true } catch (_: IOException) { false }

    private fun migrate() {
        migrateV1()
        upgradeV2()
    }

    private fun migrateV1() {
        val legacy = File(dir, "sessions.txt")
        if (file.exists() || !legacy.exists()) return
        val tmp = File(dir, "sessions.csv.tmp")
        tmp.writeText(SessionHistory.write(SessionHistory.parse(legacy.readText())))
        if (tmp.renameTo(file)) legacy.renameTo(File(dir, "sessions.v1.txt"))
    }

    private fun writeSynced(f: File, bytes: ByteArray) = FileOutputStream(f).use { it.write(bytes); it.fd.sync() }   // no empty file after a power loss

    /**
     * The new file is written aside and must read back as the same records; the original stays as sessions.v2.csv (itself
     * written aside and renamed), never over an older backup.
     */
    private fun upgradeV2() {
        if (!file.exists()) return
        val tmp = File(dir, "sessions.csv.tmp")
        val backupTmp = File(dir, "sessions.v2.csv.tmp")
        try {
            val bytes = file.readBytes()
            val text = String(bytes)
            val v3 = toV3(text)
            if (v3 == null) { tmp.delete(); return }   // already v3: a leftover temp file is stale
            writeSynced(tmp, v3.toByteArray())
            if (SessionHistory.parse(tmp.readText()) != SessionHistory.parse(text)) { tmp.delete(); return }
            val backup = generateSequence(0) { it + 1 }.map { File(dir, if (it == 0) "sessions.v2.csv" else "sessions.v2.$it.csv") }
                .first { !it.exists() || it.readBytes().contentEquals(bytes) }   // an equal backup is a crashed earlier try
            if (!backup.exists()) {
                writeSynced(backupTmp, bytes)
                if (!backupTmp.renameTo(backup)) throw IOException("backup")
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (_: IOException) {
            tmp.delete(); backupTmp.delete()
        }
    }
}
