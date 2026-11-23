package com.tuxundown.android

import com.tuxundown.android.core.HealthCheck
import com.tuxundown.android.core.MemoryTier
import com.tuxundown.android.core.SummaryNetStatus
import com.tuxundown.android.core.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.5: the diagnostics 一键体检 verdicts — every item's
 *  three-level ruling pinned at its boundaries, the proxy
 *  item's absence when unconfigured, and the copy text's
 *  redaction against secret-shaped values. */
class HealthCheckTest {

    private val gb = 1024L * 1024 * 1024

    private fun snap(
        network: SummaryNetStatus.NetKind = SummaryNetStatus.NetKind.WIFI,
        wifiOnlyOn: Boolean = false,
        chargeOnlyOn: Boolean = false,
        charging: Boolean = true,
        hasQueuedDueTask: Boolean = false,
        trafficBlockedTask: Boolean = false,
    ) = SummaryNetStatus.Snapshot(
        network = network,
        validated = true,
        dualActive = false,
        wifiOnlyOn = wifiOnlyOn,
        chargeOnlyOn = chargeOnlyOn,
        charging = charging,
        hasQueuedDueTask = hasQueuedDueTask,
        trafficBlockedTask = trafficBlockedTask,
    )

    // ---------- item list ----------

    @Test
    fun proxyItemExistsOnlyWhenAProxyIsActive() {
        val without = HealthCheck.itemsFor(
            proxyActive = false, thermalReadable = true,
        )
        assertFalse(without.contains(HealthCheck.Item.PROXY))
        assertEquals(HealthCheck.Item.values().size - 1, without.size)
        val with = HealthCheck.itemsFor(
            proxyActive = true, thermalReadable = true,
        )
        assertTrue(with.contains(HealthCheck.Item.PROXY))
        assertEquals(HealthCheck.Item.values().size, with.size)
    }

    @Test
    fun thermalItemExistsOnlyWhenTheStatusIsReadable() {
        // v6.1: an unreadable thermal status omits the item
        // entirely — the page must never show a faked state.
        val readable = HealthCheck.itemsFor(
            proxyActive = false, thermalReadable = true,
        )
        assertTrue(readable.contains(HealthCheck.Item.THERMAL))
        val unreadable = HealthCheck.itemsFor(
            proxyActive = false, thermalReadable = false,
        )
        assertFalse(unreadable.contains(HealthCheck.Item.THERMAL))
        assertEquals(readable.size - 1, unreadable.size)
        // The two absence rules compose: neither conditional
        // item leaks into the other's absence.
        val neither = HealthCheck.itemsFor(
            proxyActive = false, thermalReadable = false,
        )
        assertEquals(HealthCheck.Item.values().size - 2, neither.size)
    }

    // ---------- thermal (v6.1) ----------

    @Test
    fun thermalBandsMapOneToOneOntoLevels() {
        // The standing caliber: 正常 band -> 正常， 偏热 band ->
        // 注意， 发热降速中 band -> 异常 (the page's strongest
        // attention level — the device is limiting itself right
        // now; the value text says it is not an app fault).
        val normal = HealthCheck.thermalVerdict(ThermalStatus.Band.NORMAL)
        assertEquals(HealthCheck.Item.THERMAL, normal.item)
        assertEquals(HealthCheck.Level.OK, normal.level)
        assertTrue(normal.value.contains("正常"))
        val warm = HealthCheck.thermalVerdict(ThermalStatus.Band.WARM)
        assertEquals(HealthCheck.Level.WARN, warm.level)
        assertTrue(warm.value.contains("偏热"))
        val hot = HealthCheck.thermalVerdict(ThermalStatus.Band.THROTTLING)
        assertEquals(HealthCheck.Level.BAD, hot.level)
        assertTrue(hot.value.contains("发热降速中"))
        assertTrue(hot.value.contains("不是网络的问题"))
    }

    // ---------- version ----------

    @Test
    fun versionOkNeedsBothVersionAndFingerprint() {
        val r = HealthCheck.versionVerdict("5.4.0", "B73A2080")
        assertEquals(HealthCheck.Level.OK, r.level)
        assertTrue(r.value.contains("5.4.0"))
        assertTrue(r.value.contains("B73A2080"))
    }

    @Test
    fun versionBadWhenFingerprintOrVersionUnreadable() {
        assertEquals(
            HealthCheck.Level.BAD,
            HealthCheck.versionVerdict("5.4.0", "").level,
        )
        assertEquals(
            HealthCheck.Level.BAD,
            HealthCheck.versionVerdict("", "B73A2080").level,
        )
        assertEquals(
            HealthCheck.Level.BAD,
            HealthCheck.versionVerdict("  ", "  ").level,
        )
    }

    // ---------- memory tier ----------

    @Test
    fun memoryLowIsWarnStandardAndHighAreOk() {
        assertEquals(
            HealthCheck.Level.WARN,
            HealthCheck.memoryVerdict(MemoryTier.LOW).level,
        )
        assertEquals(
            HealthCheck.Level.OK,
            HealthCheck.memoryVerdict(MemoryTier.STANDARD).level,
        )
        assertEquals(
            HealthCheck.Level.OK,
            HealthCheck.memoryVerdict(MemoryTier.HIGH).level,
        )
    }

    // ---------- startup ----------

    @Test
    fun startupBandsPinnedAtBoundaries() {
        assertEquals(
            HealthCheck.Level.OK,
            HealthCheck.startupVerdict(HealthCheck.STARTUP_OK_MAX_MS).level,
        )
        assertEquals(
            HealthCheck.Level.WARN,
            HealthCheck.startupVerdict(HealthCheck.STARTUP_OK_MAX_MS + 1).level,
        )
        assertEquals(
            HealthCheck.Level.WARN,
            HealthCheck.startupVerdict(HealthCheck.STARTUP_WARN_MAX_MS).level,
        )
        assertEquals(
            HealthCheck.Level.BAD,
            HealthCheck.startupVerdict(HealthCheck.STARTUP_WARN_MAX_MS + 1).level,
        )
    }

    @Test
    fun startupUnmeasuredIsWarnNotBad() {
        // First install / never measured: missing information,
        // honestly labelled — not a fault, never a fake number.
        val r = HealthCheck.startupVerdict(0)
        assertEquals(HealthCheck.Level.WARN, r.level)
        assertTrue(r.value.contains("还没有记录"))
        assertFalse(r.value.contains("0 毫秒"))
    }

    // ---------- space ----------

    @Test
    fun spaceBandsPinnedAtBoundaries() {
        assertEquals(
            HealthCheck.Level.BAD,
            HealthCheck.spaceVerdict(gb - 1, customDir = false).level,
        )
        assertEquals(
            HealthCheck.Level.WARN,
            HealthCheck.spaceVerdict(gb, customDir = false).level,
        )
        assertEquals(
            HealthCheck.Level.WARN,
            HealthCheck.spaceVerdict(5 * gb - 1, customDir = false).level,
        )
        assertEquals(
            HealthCheck.Level.OK,
            HealthCheck.spaceVerdict(5 * gb, customDir = false).level,
        )
        assertEquals(
            HealthCheck.Level.OK,
            HealthCheck.spaceVerdict(64 * gb, customDir = false).level,
        )
    }

    @Test
    fun spaceUnreadableIsWarnAndSaysWhy() {
        // SpaceCheck's standing rule: an unmeasurable volume
        // never blocks — so it can never be 异常 here either.
        val custom = HealthCheck.spaceVerdict(-1, customDir = true)
        assertEquals(HealthCheck.Level.WARN, custom.level)
        assertTrue(custom.value.contains("自定义文件夹"))
        val plain = HealthCheck.spaceVerdict(-1, customDir = false)
        assertEquals(HealthCheck.Level.WARN, plain.level)
        assertTrue(plain.value.contains("读不到"))
    }

    // ---------- network + gates ----------

    @Test
    fun networkNoNetworkIsBad() {
        val r = HealthCheck.networkVerdict(
            snap(network = SummaryNetStatus.NetKind.NONE)
        )
        assertEquals(HealthCheck.Level.BAD, r.level)
        assertTrue(r.value.contains("无网络"))
    }

    @Test
    fun networkClosedGateHoldingATaskIsWarn() {
        // WiFi-only on, on mobile data, a queued task due: the
        // gate is holding it right now.
        val wifiGate = HealthCheck.networkVerdict(
            snap(
                network = SummaryNetStatus.NetKind.MOBILE,
                wifiOnlyOn = true, hasQueuedDueTask = true,
            )
        )
        assertEquals(HealthCheck.Level.WARN, wifiGate.level)
        assertTrue(wifiGate.value.contains("等 WiFi"))
        val chargeGate = HealthCheck.networkVerdict(
            snap(chargeOnlyOn = true, charging = false, hasQueuedDueTask = true)
        )
        assertEquals(HealthCheck.Level.WARN, chargeGate.level)
        val traffic = HealthCheck.networkVerdict(
            snap(trafficBlockedTask = true)
        )
        assertEquals(HealthCheck.Level.WARN, traffic.level)
    }

    @Test
    fun networkGateOnButNothingHeldIsOk() {
        // The switch being on is not "挡路" — only a gate
        // actually holding a queued task is (SummaryNetStatus'
        // own honesty rule).
        val r = HealthCheck.networkVerdict(
            snap(
                network = SummaryNetStatus.NetKind.MOBILE,
                wifiOnlyOn = true, hasQueuedDueTask = false,
            )
        )
        assertEquals(HealthCheck.Level.OK, r.level)
    }

    @Test
    fun networkPlainWifiIsOk() {
        assertEquals(
            HealthCheck.Level.OK,
            HealthCheck.networkVerdict(snap()).level,
        )
    }

    // ---------- proxy ----------

    @Test
    fun proxyAbsentWhenNotActive() {
        assertNull(HealthCheck.proxyVerdict(false, reachable = true, "127.0.0.1:7890"))
        assertNull(HealthCheck.proxyVerdict(false, reachable = false, "127.0.0.1:7890"))
    }

    @Test
    fun proxyReachableOkUnreachableBad() {
        assertEquals(
            HealthCheck.Level.OK,
            HealthCheck.proxyVerdict(true, reachable = true, "127.0.0.1:7890")!!.level,
        )
        val bad = HealthCheck.proxyVerdict(true, reachable = false, "127.0.0.1:7890")!!
        assertEquals(HealthCheck.Level.BAD, bad.level)
        assertTrue(bad.value.contains("不会偷偷直连"))
    }

    // ---------- DNS ----------

    @Test
    fun dnsAllSomeNone() {
        val ok = HealthCheck.DnsProbe("www.baidu.com", true, "www.baidu.com 能解析（2 个地址）")
        val ok2 = HealthCheck.DnsProbe("github.com", true, "github.com 能解析（1 个地址）")
        val fail = HealthCheck.DnsProbe("github.com", false, "github.com 解析失败")
        assertEquals(HealthCheck.Level.OK, HealthCheck.dnsVerdict(listOf(ok, ok2)).level)
        assertEquals(HealthCheck.Level.WARN, HealthCheck.dnsVerdict(listOf(ok, fail)).level)
        assertEquals(
            HealthCheck.Level.BAD,
            HealthCheck.dnsVerdict(listOf(fail, fail.copy(host = "www.baidu.com"))).level,
        )
        assertEquals(
            HealthCheck.Level.BAD,
            HealthCheck.dnsVerdict(emptyList()).level,
        )
    }

    // ---------- learning ----------

    @Test
    fun learningCountsAreInformational() {
        val r = HealthCheck.learningVerdict(12, 7)
        assertEquals(HealthCheck.Level.OK, r.level)
        assertTrue(r.value.contains("12 条"))
        assertTrue(r.value.contains("7 条"))
        // Zero is a normal state (fresh install / user cleared).
        assertEquals(HealthCheck.Level.OK, HealthCheck.learningVerdict(0, 0).level)
        assertEquals(HealthCheck.Level.WARN, HealthCheck.learningVerdict(-1, 0).level)
    }

    // ---------- tasks ----------

    @Test
    fun tasksAbnormalIsWarnNeverBad() {
        assertEquals(HealthCheck.Level.OK, HealthCheck.tasksVerdict(5, 0).level)
        assertEquals(HealthCheck.Level.OK, HealthCheck.tasksVerdict(0, 0).level)
        val r = HealthCheck.tasksVerdict(5, 2)
        assertEquals(HealthCheck.Level.WARN, r.level)
        assertTrue(r.value.contains("共 5 个"))
        assertTrue(r.value.contains("2 个异常"))
        // A corrupt abnormal > total clamps instead of printing nonsense.
        val clamped = HealthCheck.tasksVerdict(3, 99)
        assertTrue(clamped.value.contains("3 个异常"))
    }

    // ---------- copy text + redaction ----------

    @Test
    fun copyTextHasOneLinePerItemWithLevelWords() {
        val text = HealthCheck.buildCopyText(
            "5.4.0",
            listOf(
                HealthCheck.versionVerdict("5.4.0", "B73A2080"),
                HealthCheck.spaceVerdict(0, customDir = false),
            ),
        )
        assertTrue(text.contains("版本：v5.4.0"))
        assertTrue(text.contains("[正常] 版本与签名"))
        assertTrue(text.contains("[异常] 下载空间"))
        assertTrue(text.contains("没有上传"))
    }

    @Test
    fun copyTextNeverCarriesSecretShapes() {
        // Feed the sanitizer the exact shapes TaskDiagnostics
        // exists to keep on the phone: a signed URL's query,
        // userinfo credentials, a proxy password pair, a Basic
        // blob and a "v1:" Keystore ciphertext.
        val evil = HealthCheck.Result(
            HealthCheck.Item.PROXY, HealthCheck.Level.BAD,
            "代理 https://user:proxypass@proxy.example.com:7890/f?token=tok12345 连不上 " +
                "password=hunter2secret Basic dXNlcjpwYXNzd29yZA== v1:QUJDREVGR0hJSktMTU5PUA==",
        )
        val text = HealthCheck.buildCopyText("5.4.0", listOf(evil))
        for (secret in listOf(
            "proxypass", "tok12345", "hunter2secret",
            "dXNlcjpwYXNzd29yZA==", "QUJDREVGR0hJSktMTU5PUA==",
            "user:", "?token=",
        )) {
            assertFalse("copy text leaked: $secret", text.contains(secret))
        }
        // The host itself may stay (it is not a credential) —
        // and the query collapses to the standard marker.
        assertTrue(text.contains("proxy.example.com"))
        assertTrue(text.contains("参数已隐藏"))
    }

    @Test
    fun sanitizeCoversTheNewCorpusShapesViaTheSharedRedactor() {
        // v7.2: sanitizeForCopy now delegates to the single
        // Redact — the shapes this page's old private corpus
        // missed (standalone Bearer, a Cookie: line, api-key
        // style names) are covered here too. Fake data only.
        val out = HealthCheck.sanitizeForCopy(
            "Bearer FAKEHC1 api-key=FAKEHC2 Cookie: session=FAKEHC3"
        )
        assertFalse(out.contains("FAKEHC1"))
        assertFalse(out.contains("FAKEHC2"))
        assertFalse(out.contains("FAKEHC3"))
    }

    @Test
    fun sanitizeLeavesPlainValuesUntouched() {
        val plain = "下载目录可用 12.3 GB"
        assertEquals(plain, HealthCheck.sanitizeForCopy(plain))
        val line = "当前网络：WiFi · 排队任务在等充电"
        assertEquals(line, HealthCheck.sanitizeForCopy(line))
    }
}
