package com.tuxundown.android

import com.tuxundown.android.core.SummaryNetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.2: the summary card's network status line (pure). */
class SummaryNetStatusTest {

    private fun snap(
        network: SummaryNetStatus.NetKind = SummaryNetStatus.NetKind.WIFI,
        validated: Boolean = true,
        dualActive: Boolean = false,
        wifiOnlyOn: Boolean = false,
        chargeOnlyOn: Boolean = false,
        charging: Boolean = true,
        hasQueuedDueTask: Boolean = false,
        trafficBlockedTask: Boolean = false,
    ) = SummaryNetStatus.Snapshot(
        network = network,
        validated = validated,
        dualActive = dualActive,
        wifiOnlyOn = wifiOnlyOn,
        chargeOnlyOn = chargeOnlyOn,
        charging = charging,
        hasQueuedDueTask = hasQueuedDueTask,
        trafficBlockedTask = trafficBlockedTask,
    )

    // ---- segment 1: the network itself ----

    @Test
    fun wifiNetworkAlone() {
        assertEquals("当前网络：WiFi", SummaryNetStatus.line(snap()))
    }

    @Test
    fun mobileNetworkAlone() {
        assertEquals(
            "当前网络：流量",
            SummaryNetStatus.line(snap(network = SummaryNetStatus.NetKind.MOBILE)),
        )
    }

    @Test
    fun noNetworkIsHonestNeverMobile() {
        // The honesty pin: NONE renders as 无网络, full stop —
        // it must not fall through to the 流量 wording.
        val line = SummaryNetStatus.line(
            snap(network = SummaryNetStatus.NetKind.NONE, validated = false)
        )
        assertEquals("当前网络：无网络", line)
        assertFalse(line.contains("流量"))
    }

    // ---- segment 2: dual channel only when really active ----

    @Test
    fun dualActiveShows() {
        assertEquals(
            "当前网络：WiFi · 双通道生效中",
            SummaryNetStatus.line(snap(dualActive = true)),
        )
    }

    @Test
    fun dualNotActiveStaysSilent() {
        // Switch-on-but-no-cellular-leg arrives here as
        // dualActive = false (the service only mirrors a granted
        // leg); the line must not claim it.
        assertFalse(SummaryNetStatus.line(snap(dualActive = false)).contains("双通道"))
    }

    // ---- segment 3: first closed gate, only with a held task ----

    @Test
    fun wifiGateShowsWhenClosedAndTaskHeld() {
        assertEquals(
            "当前网络：流量 · 排队任务在等 WiFi",
            SummaryNetStatus.line(
                snap(
                    network = SummaryNetStatus.NetKind.MOBILE,
                    wifiOnlyOn = true, hasQueuedDueTask = true,
                )
            ),
        )
    }

    @Test
    fun wifiGateAlsoClosedWithNoNetwork() {
        // 无网络 is also "not on WiFi": the drain's WiFi gate
        // holds the task just the same.
        assertEquals(
            SummaryNetStatus.Gate.WIFI,
            SummaryNetStatus.gateOf(
                snap(
                    network = SummaryNetStatus.NetKind.NONE, validated = false,
                    wifiOnlyOn = true, hasQueuedDueTask = true,
                )
            ),
        )
    }

    @Test
    fun wifiGateOpenOnWifiStaysSilent() {
        assertEquals(
            null,
            SummaryNetStatus.gateOf(
                snap(wifiOnlyOn = true, hasQueuedDueTask = true)
            ),
        )
    }

    @Test
    fun chargeGateShowsWhenUnpluggedAndTaskHeld() {
        assertEquals(
            "当前网络：WiFi · 排队任务在等充电",
            SummaryNetStatus.line(
                snap(chargeOnlyOn = true, charging = false, hasQueuedDueTask = true)
            ),
        )
    }

    @Test
    fun chargeGateOpenWhileChargingStaysSilent() {
        assertEquals(
            null,
            SummaryNetStatus.gateOf(
                snap(chargeOnlyOn = true, charging = true, hasQueuedDueTask = true)
            ),
        )
    }

    @Test
    fun wifiBeatsChargeWhenBothClosed() {
        // Drain order (v3.9): WiFi first — only WiFi is named.
        val line = SummaryNetStatus.line(
            snap(
                network = SummaryNetStatus.NetKind.MOBILE,
                wifiOnlyOn = true, chargeOnlyOn = true, charging = false,
                hasQueuedDueTask = true,
            )
        )
        assertTrue(line.contains("排队任务在等 WiFi"))
        assertFalse(line.contains("等充电"))
    }

    @Test
    fun chargeBeatsTrafficWhenBothClosed() {
        val line = SummaryNetStatus.line(
            snap(
                chargeOnlyOn = true, charging = false,
                hasQueuedDueTask = true, trafficBlockedTask = true,
            )
        )
        assertTrue(line.contains("排队任务在等充电"))
        assertFalse(line.contains("流量保护"))
    }

    @Test
    fun trafficGateShowsOnItsEvidence() {
        assertEquals(
            "当前网络：流量 · 排队任务被流量保护挡着",
            SummaryNetStatus.line(
                snap(
                    network = SummaryNetStatus.NetKind.MOBILE,
                    hasQueuedDueTask = true, trafficBlockedTask = true,
                )
            ),
        )
    }

    @Test
    fun noHeldTaskNoGateSegment() {
        // Every gate shut, but nothing queued-and-due for them
        // to hold: the gate segment must be absent entirely.
        val line = SummaryNetStatus.line(
            snap(
                network = SummaryNetStatus.NetKind.MOBILE,
                wifiOnlyOn = true, chargeOnlyOn = true, charging = false,
                hasQueuedDueTask = false, trafficBlockedTask = false,
            )
        )
        assertEquals("当前网络：流量", line)
    }

    @Test
    fun fullLineOrdersAllThreeSegments() {
        assertEquals(
            "当前网络：流量 · 双通道生效中 · 排队任务在等 WiFi",
            SummaryNetStatus.line(
                snap(
                    network = SummaryNetStatus.NetKind.MOBILE,
                    dualActive = true, wifiOnlyOn = true,
                    hasQueuedDueTask = true,
                )
            ),
        )
    }
}
