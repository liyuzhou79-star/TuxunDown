package com.tuxundown.android

import com.tuxundown.android.core.Backup
import com.tuxundown.android.core.HostMemory
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.6: the full migration pack — Backup format version 2
 * (settings + tasks + statistics + per-host learning data),
 * its round trip, the sensitive-data exclusion, and backward
 * compatibility with the v1.8 backup and the v1.4 bare array.
 */
class MigrationPackTest {

    private fun statsJson(): JSONObject = JSONObject()
        .put("totalBytes", 123_456_789L)
        .put("doneCount", 42)
        .put("todayKey", "2026-11-22")
        .put("todayBytes", 1_024L)
        .put("speedSum", 9_000_000L)
        .put("speedCount", 3)
        .put("hostStats", "example.com\t123456\t7")
        .put("days", "2026-11-21\t2048\n2026-11-22\t1024")

    private fun hostMemoryText(): String = HostMemory.serialize(
        listOf(
            HostMemory.Entry("example.com", 16, 0, 1_700_000_000_000, cap = 8),
            HostMemory.Entry("slow.org", 4, 1, 1_700_000_100_000),
        )
    )

    @Test
    fun migrationRoundTripCarriesEverySection() {
        val task = TaskRecord(
            url = "https://example.com/a.zip",
            fileName = "a.zip",
            status = TaskStatus.PAUSED,
        )
        val settings = JSONObject()
            .put("wifiOnly", true)
            .put("sniffHistory", org.json.JSONArray(listOf("https://example.com/page")))
        val text = Backup.buildMigration(
            TaskStore.serialize(listOf(task)),
            settings,
            statsJson(),
            hostMemoryText(),
            1_700_000_000_000,
        )
        val parsed = Backup.parse(text)
        assertEquals(Backup.MIGRATION_VERSION, parsed.formatVersion)
        assertNotNull(parsed.tasksJson)
        assertEquals(1, TaskStore.deserialize(parsed.tasksJson!!).size)
        assertNotNull(parsed.settings)
        assertTrue(parsed.settings!!.getBoolean("wifiOnly"))
        // Sniff history rides inside the settings section.
        assertEquals(
            "https://example.com/page",
            parsed.settings.getJSONArray("sniffHistory").getString(0),
        )
        assertNotNull(parsed.stats)
        assertEquals(123_456_789L, parsed.stats!!.getLong("totalBytes"))
        assertEquals(42, parsed.stats.getInt("doneCount"))
        assertEquals("example.com\t123456\t7", parsed.stats.getString("hostStats"))
        // Host learning survives as HostMemory entries, cap included.
        val entries = HostMemory.parse(parsed.hostMemory!!)
        assertEquals(2, entries.size)
        val capped = entries.first { it.host == "example.com" }
        assertEquals(16, capped.threads)
        assertEquals(8, capped.cap)
    }

    @Test
    fun migrationPackKeepsCredentialsOut() {
        val task = TaskRecord(
            url = "https://example.com/secret.zip",
            fileName = "secret.zip",
            headers = mutableMapOf(
                "Cookie" to "session=topsecret-cookie",
                "Authorization" to "Bearer topsecret-token",
                "Proxy-Authorization" to "Basic topsecret-basic",
                "Referer" to "https://example.com/page",
            ),
        )
        // The export path the UI uses (TaskStore.exportJson)
        // strips sensitive headers BEFORE Backup ever sees them.
        val tasksJson = TaskStore.serialize(listOf(task), stripSensitiveHeaders = true)
        val text = Backup.buildMigration(
            tasksJson, JSONObject(), statsJson(), hostMemoryText(), 0L,
        )
        assertFalse(text.contains("topsecret-cookie"))
        assertFalse(text.contains("topsecret-token"))
        assertFalse(text.contains("topsecret-basic"))
        // Non-sensitive headers are not collateral damage.
        assertTrue(text.contains("https://example.com/page"))
    }

    @Test
    fun v18BackupParsesWithNoMigrationSections() {
        val text = Backup.build(
            TaskStore.serialize(
                listOf(TaskRecord(url = "https://example.com/b.bin", fileName = "b.bin"))
            ),
            JSONObject().put("wifiOnly", false),
            1_700_000_000_000,
        )
        val parsed = Backup.parse(text)
        assertEquals(Backup.FORMAT_VERSION, parsed.formatVersion)
        assertNotNull(parsed.tasksJson)
        assertNotNull(parsed.settings)
        // Absent sections parse as null: the importer leaves the
        // current statistics / host learning untouched.
        assertNull(parsed.stats)
        assertNull(parsed.hostMemory)
    }

    @Test
    fun legacyV14ArrayStillParsesAsTasksOnly() {
        val tasksJson = TaskStore.serialize(
            listOf(TaskRecord(url = "https://example.com/c.bin", fileName = "c.bin"))
        )
        val parsed = Backup.parse(tasksJson)
        assertEquals(0, parsed.formatVersion)
        assertNotNull(parsed.tasksJson)
        assertNull(parsed.settings)
        assertNull(parsed.stats)
        assertNull(parsed.hostMemory)
    }

    @Test
    fun migrationRoundTripCarriesEveryStatsFieldVerbatim() {
        // v6.0 audit round 45: the round-trip test above asserts
        // only 3 of the 8 statistics keys. All eight stored keys
        // (DownloadCenter.statsSnapshotJson's exact set) must
        // survive buildMigration -> parse verbatim, or a phone
        // move silently loses part of the totals / day series.
        val text = Backup.buildMigration(
            "[]", JSONObject(), statsJson(), null, 0L,
        )
        val stats = Backup.parse(text).stats
        assertNotNull(stats)
        assertEquals(123_456_789L, stats!!.getLong("totalBytes"))
        assertEquals(42, stats.getInt("doneCount"))
        assertEquals("2026-11-22", stats.getString("todayKey"))
        assertEquals(1_024L, stats.getLong("todayBytes"))
        assertEquals(9_000_000L, stats.getLong("speedSum"))
        assertEquals(3, stats.getInt("speedCount"))
        assertEquals("example.com\t123456\t7", stats.getString("hostStats"))
        assertEquals(
            "2026-11-21\t2048\n2026-11-22\t1024", stats.getString("days"),
        )
    }

    @Test
    fun migrationPackReExportIsStable() {
        // v6.0 audit round 45: export -> import -> export again
        // must be a fixed point at the Backup layer — the second
        // pack carries exactly the sections the first one did
        // (settings incl. sniff history / collapsed sites, tasks,
        // all statistics, host learning), so a backup of a
        // restored phone is the same backup.
        val settings = JSONObject()
            .put("wifiOnly", true)
            .put("maxConcurrent", 3)
            .put("sniffHistory", org.json.JSONArray(listOf("https://example.com/page")))
            .put("collapsedSites", org.json.JSONArray(listOf("example.com")))
            .put("proxyHost", "127.0.0.1")
            .put("proxyPort", 7890)
        val task = TaskRecord(
            url = "https://example.com/a.zip",
            fileName = "a.zip",
            status = TaskStatus.PAUSED,
        )
        val first = Backup.parse(
            Backup.buildMigration(
                TaskStore.serialize(listOf(task)),
                settings, statsJson(), hostMemoryText(), 1_700_000_000_000,
            )
        )
        val second = Backup.parse(
            Backup.buildMigration(
                first.tasksJson!!, first.settings!!,
                first.stats, first.hostMemory, 1_700_000_100_000,
            )
        )
        assertEquals(Backup.MIGRATION_VERSION, second.formatVersion)
        assertEquals(first.hostMemory, second.hostMemory)
        assertNotNull(second.settings)
        assertTrue(second.settings!!.getBoolean("wifiOnly"))
        assertEquals(3, second.settings.getInt("maxConcurrent"))
        assertEquals(
            "https://example.com/page",
            second.settings.getJSONArray("sniffHistory").getString(0),
        )
        assertEquals(
            "example.com",
            second.settings.getJSONArray("collapsedSites").getString(0),
        )
        assertEquals("127.0.0.1", second.settings.getString("proxyHost"))
        assertEquals(7890, second.settings.getInt("proxyPort"))
        assertNotNull(second.stats)
        assertEquals(123_456_789L, second.stats!!.getLong("totalBytes"))
        assertEquals(
            "2026-11-21\t2048\n2026-11-22\t1024",
            second.stats.getString("days"),
        )
        val restored = TaskStore.deserialize(second.tasksJson!!)
        assertEquals(1, restored.size)
        assertEquals("a.zip", restored[0].fileName)
        assertEquals(2, HostMemory.parse(second.hostMemory!!).size)
    }

    @Test
    fun blankSectionsAreOmittedNotStored() {
        val text = Backup.buildMigration("[]", JSONObject(), null, "  ", 0L)
        val parsed = Backup.parse(text)
        assertEquals(Backup.MIGRATION_VERSION, parsed.formatVersion)
        assertNull(parsed.stats)
        assertNull(parsed.hostMemory)
    }
}
