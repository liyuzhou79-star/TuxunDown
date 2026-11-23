package com.tuxundown.android

import com.tuxundown.android.core.SecurityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.2 (audit round 57, Step C): the diagnostics
 * 「安全现状」 assembly. Fixed fake inputs only — the point
 * is the absence caliber: what cannot be read is absent,
 * never replaced by a hard-coded figure, and the proxy
 * password's VALUE never appears in any row.
 */
class SecurityStatusTest {

    private fun snapshot(
        permissions: List<String>? = null,
        exported: Int? = null,
        proxy: SecurityStatus.ProxyPassStorage? = null,
    ) = SecurityStatus.Snapshot(permissions, exported, proxy)

    @Test
    fun allReadsFailedMeansTheWholeSectionIsAbsent() {
        // Even the fixed backup line does not stand alone.
        assertTrue(SecurityStatus.lines(snapshot()).isEmpty())
    }

    @Test
    fun emptyPermissionListDropsItsRowAndAloneDropsTheSection() {
        assertTrue(
            SecurityStatus.lines(snapshot(permissions = emptyList())).isEmpty()
        )
        assertTrue(
            SecurityStatus.lines(
                snapshot(permissions = listOf("", "  "))
            ).isEmpty()
        )
    }

    @Test
    fun permissionRowCountsAndNamesExactlyWhatWasRead() {
        val rows = SecurityStatus.lines(
            snapshot(
                permissions = listOf(
                    "android.permission.INTERNET",
                    " android.permission.CAMERA ",
                    "",
                ),
            )
        )
        val row = rows.first { it.first == "申请的权限" }
        assertEquals(
            "共 2 项：android.permission.INTERNET、android.permission.CAMERA",
            row.second,
        )
    }

    @Test
    fun exportedRowShowsARealZeroButDropsNullAndNegative() {
        val zero = SecurityStatus.lines(snapshot(exported = 0))
        assertEquals(
            "共 0 个在清单里标为对外开放（其他组件外部应用调不到）",
            zero.first { it.first == "对外开放的组件" }.second,
        )
        assertTrue(
            SecurityStatus.lines(snapshot(exported = null)).isEmpty()
        )
        assertTrue(
            SecurityStatus.lines(snapshot(exported = -1)).isEmpty()
        )
    }

    @Test
    fun proxyRowNamesTheStorageModeAndNeverThePassword() {
        val unset = SecurityStatus.lines(
            snapshot(proxy = SecurityStatus.ProxyPassStorage.UNSET)
        )
        assertEquals("未设置", unset.first { it.first == "代理密码" }.second)

        val encrypted = SecurityStatus.lines(
            snapshot(
                proxy = SecurityStatus.ProxyPassStorage.KEYSTORE_ENCRYPTED,
            )
        )
        val line = encrypted.first { it.first == "代理密码" }.second
        assertTrue(line.contains("系统钥匙串"))
        assertTrue(line.contains("AES-256/GCM"))
        assertTrue(line.contains("不显示密码本身"))

        val plain = SecurityStatus.lines(
            snapshot(proxy = SecurityStatus.ProxyPassStorage.PLAIN_FALLBACK)
        )
        val plainLine = plain.first { it.first == "代理密码" }.second
        assertTrue(plainLine.contains("原样保存"))
        assertFalse(plainLine.contains("加密保存（"))
    }

    @Test
    fun storageClassificationReadsShapeOnly() {
        assertEquals(
            SecurityStatus.ProxyPassStorage.UNSET,
            SecurityStatus.proxyPassStorageOf(null),
        )
        assertEquals(
            SecurityStatus.ProxyPassStorage.UNSET,
            SecurityStatus.proxyPassStorageOf(""),
        )
        assertEquals(
            SecurityStatus.ProxyPassStorage.KEYSTORE_ENCRYPTED,
            SecurityStatus.proxyPassStorageOf("v1:QUJDZA=="),
        )
        assertEquals(
            SecurityStatus.ProxyPassStorage.PLAIN_FALLBACK,
            SecurityStatus.proxyPassStorageOf("a-plain-legacy-value"),
        )
    }

    @Test
    fun partialReadsShowPresentRowsAbsentRowsAndTheBackupLine() {
        val rows = SecurityStatus.lines(snapshot(exported = 2))
        assertEquals(listOf("对外开放的组件", "备份脱敏"), rows.map { it.first })
        val backup = rows.first { it.first == "备份脱敏" }.second
        assertTrue(backup.contains("Cookie"))
        assertTrue(backup.contains("代理密码"))
        // The honest half of the caliber: links ride verbatim
        // in a backup (an import needs them), and the line
        // says so instead of claiming they are redacted.
        assertTrue(backup.contains("原样保留"))
    }

    @Test
    fun fullSnapshotShowsEveryRowInOrder() {
        val rows = SecurityStatus.lines(
            snapshot(
                permissions = listOf("android.permission.INTERNET"),
                exported = 2,
                proxy = SecurityStatus.ProxyPassStorage.UNSET,
            )
        )
        assertEquals(
            listOf("申请的权限", "对外开放的组件", "代理密码", "备份脱敏"),
            rows.map { it.first },
        )
    }
}
