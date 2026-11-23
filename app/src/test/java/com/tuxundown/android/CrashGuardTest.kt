package com.tuxundown.android

import com.tuxundown.android.core.CrashGuard
import com.tuxundown.android.core.ExportStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashGuardTest {

    @Test
    fun safeModeStartsAtSecondConsecutiveCrash() {
        assertFalse(CrashGuard.shouldSafeMode(0))
        assertFalse(CrashGuard.shouldSafeMode(1))
        assertTrue(CrashGuard.shouldSafeMode(2))
        assertTrue(CrashGuard.shouldSafeMode(5))
    }

    @Test
    fun describeNamesTheFailureKind() {
        assertTrue(CrashGuard.describe("java.lang.OutOfMemoryError").contains("内存"))
        assertTrue(
            CrashGuard.describe("android.database.sqlite.SQLiteException").contains("记录")
        )
        assertTrue(CrashGuard.describe("java.lang.SecurityException").contains("权限"))
        val generic = CrashGuard.describe("com.example.WhateverException")
        assertTrue(generic.contains("没预料到"))
        // The plain summary never leaks the raw class name.
        assertFalse(generic.contains("WhateverException"))
    }

    @Test
    fun truncateKeepsTheHead() {
        val long = "x".repeat(CrashGuard.MAX_LOG_CHARS + 5000)
        val out = CrashGuard.truncateLog(long)
        assertTrue(out.startsWith("x".repeat(100)))
        assertTrue(out.contains("截断"))
        assertTrue(out.length < long.length)
        assertEquals("short", CrashGuard.truncateLog("short"))
    }

    @Test
    fun buildLogCarriesAnExportStampTimeLine() {
        // v6.4 step B: the handler hands buildLog an
        // ExportStamp string (local time + explicit zone
        // offset); the log's 时间 line must carry it verbatim.
        val stamp = ExportStamp.format(
            1795417445000L, java.util.TimeZone.getTimeZone("Asia/Taipei"),
        )
        val log = CrashGuard.buildLog(stamp, "6.3.0", "main", IllegalStateException("x"))
        assertTrue(log.contains("时间：2026-11-23 15:04:05 (UTC+08:00)"))
    }

    @Test
    fun buildLogCarriesVersionAndTrace() {
        val boom = IllegalStateException("kaput")
        val log = CrashGuard.buildLog("2026-11-22 10:00:00", "2.2.0", "main", boom)
        assertTrue(log.contains("2.2.0"))
        assertTrue(log.contains("IllegalStateException"))
        assertTrue(log.contains("kaput"))
        assertTrue(log.contains("2026-11-22"))
    }
}
