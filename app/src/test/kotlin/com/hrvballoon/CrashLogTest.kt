package com.hrvballoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashLogTest {
    @Test fun savesTheStackAndHandsItOverOnce() {
        val dir = Files.createTempDirectory("crash").toFile()
        val log = CrashLog(dir)
        assertNull(log.take())
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        log.install()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        Thread.setDefaultUncaughtExceptionHandler(before)
        val report = log.take()!!
        assertTrue(report.startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(report.contains("CrashLogTest"))
        assertNull(log.take())
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun installingTwiceKeepsOneHandlerAndOneWrite() {
        val first = Files.createTempDirectory("crash").toFile()
        val second = Files.createTempDirectory("crash").toFile()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        try {
            CrashLog(first).install()
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            CrashLog(second).install()
            assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        assertEquals(1, first.list()!!.size)
        assertEquals(0, second.list()!!.size)
    }
}
