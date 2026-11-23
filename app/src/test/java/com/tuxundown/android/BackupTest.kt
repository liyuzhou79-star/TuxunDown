package com.tuxundown.android

import com.tuxundown.android.core.Backup
import com.tuxundown.android.core.FailureKind
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.8: full-backup format — round trip + v1.4 legacy compat. */
class BackupTest {

    @Test
    fun buildParseRoundTrip() {
        val task = TaskRecord(
            url = "https://example.com/a.zip",
            fileName = "a.zip",
            status = TaskStatus.PAUSED,
            scheduleDaily = true,
            scheduledAt = 1_700_000_000_000,
        )
        val tasksJson = TaskStore.serialize(listOf(task))
        val settings = JSONObject().put("wifiOnly", true).put("maxConcurrent", 3)
        val text = Backup.build(tasksJson, settings, 1_700_000_000_000)
        val parsed = Backup.parse(text)
        assertNotNull(parsed.tasksJson)
        assertNotNull(parsed.settings)
        assertTrue(parsed.settings!!.getBoolean("wifiOnly"))
        assertEquals(3, parsed.settings.getInt("maxConcurrent"))
        val restored = TaskStore.deserialize(parsed.tasksJson!!)
        assertEquals(1, restored.size)
        assertEquals("a.zip", restored[0].fileName)
        // The v1.8 daily flag survives the round trip.
        assertTrue(restored[0].scheduleDaily)
        assertEquals(1_700_000_000_000, restored[0].scheduledAt)
    }

    @Test
    fun tailRaceEnabledSurvivesBuildParse() {
        // Audit round 68 (G1): the tail-race toggle must be
        // carried in the backup's settings shape verbatim.
        val tasksJson = TaskStore.serialize(
            listOf(TaskRecord(url = "https://example.com/d.bin", fileName = "d.bin"))
        )
        val settings = JSONObject().put("tailRaceEnabled", true)
        val parsed = Backup.parse(Backup.build(tasksJson, settings, 1_700_000_000_000))
        assertNotNull(parsed.settings)
        assertTrue(parsed.settings!!.getBoolean("tailRaceEnabled"))
    }

    @Test
    fun legacyV14ArrayStillImports() {
        val tasksJson = TaskStore.serialize(
            listOf(TaskRecord(url = "https://example.com/b.bin", fileName = "b.bin"))
        )
        val parsed = Backup.parse(tasksJson)
        assertNotNull(parsed.tasksJson)
        assertNull(parsed.settings)
        assertEquals(1, TaskStore.deserialize(parsed.tasksJson!!).size)
    }

    @Test
    fun failureKindSurvivesEveryBackupShape() {
        // v5.0 (audit round 35): the v4.5 verdict field must
        // round-trip through BOTH backup shapes (v1 object and
        // v2 migration pack), not just through TaskStore alone.
        val task = TaskRecord(
            url = "https://example.com/c.bin",
            fileName = "c.bin",
            status = TaskStatus.FAILED,
        ).apply {
            failureKind = FailureKind.HTTP_GONE
            failureHttpCode = 410
        }
        val tasksJson = TaskStore.serialize(listOf(task))
        val settings = JSONObject()
        for (text in listOf(
            Backup.build(tasksJson, settings, 1_700_000_000_000),
            Backup.buildMigration(tasksJson, settings, null, null, 1_700_000_000_000),
        )) {
            val restored = TaskStore.deserialize(Backup.parse(text).tasksJson!!).single()
            assertEquals(FailureKind.HTTP_GONE, restored.failureKind)
            assertEquals(410, restored.failureHttpCode)
        }
    }

    @Test
    fun failureKindMissingInOldShapesDefaultsSafely() {
        // A pre-v4.5 record (no failureKind field at all),
        // carried by the bare v1.4 array shape: the field must
        // come back as the safe UNKNOWN default, never a crash
        // and never a guessed verdict.
        val oldJson = """[{"id":"old1","url":"https://e.com/f","status":"FAILED"}]"""
        val parsed = Backup.parse(oldJson)
        assertNotNull(parsed.tasksJson)
        val restored = TaskStore.deserialize(parsed.tasksJson!!).single()
        assertEquals(FailureKind.UNKNOWN, restored.failureKind)
        assertEquals(0, restored.failureHttpCode)
    }

    @Test
    fun garbageYieldsEmptyParsed() {
        assertNull(Backup.parse("not json at all").tasksJson)
        assertNull(Backup.parse("[broken").tasksJson)
        assertNull(Backup.parse("{}").tasksJson)
        assertNull(Backup.parse("{}").settings)
    }
}
