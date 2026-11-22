package com.hrvballoon

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Keeps the last uncaught exception's stack in [dir]/crash.txt so the next launch can show and copy it. */
class CrashLog(private val dir: File) {
    private val file get() = File(dir, "crash.txt")

    private class Hook(val prev: Thread.UncaughtExceptionHandler?, val file: File) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { file.writeText(StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()) }
            prev?.uncaughtException(t, e)
        }
    }

    /** Once per process: every onCreate calls it, and a second hook would chain onto the first and write the file twice. */
    fun install() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev !is Hook) Thread.setDefaultUncaughtExceptionHandler(Hook(prev, file))
    }

    /** The saved report, cleared once taken. */
    fun take(): String? = file.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
}
