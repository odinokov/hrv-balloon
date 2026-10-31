package com.hrvballoon

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Keeps the last uncaught exception's stack in [dir]/crash.txt so the next launch can show and copy it. */
class CrashLog(private val dir: File) {
    private val file get() = File(dir, "crash.txt")

    fun install() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText(StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()) }
            prev?.uncaughtException(t, e)
        }
    }

    /** The saved report, cleared once taken. */
    fun take(): String? = file.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
}
