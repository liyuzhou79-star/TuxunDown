package com.tuxundown.android.core

/**
 * v5.2: the home summary card's network status line — a pure
 * aggregation, unit-tested (see SummaryNetStatusTest).
 *
 * The Android side (DownloadCenter.summaryNetLine) packs ONE
 * ConnectivityManager read plus the live gate/task state into a
 * [Snapshot]; this object only decides what the line SAYS. The
 * card re-reads it on its standing 1 s tick — no new polling,
 * callback or thread exists for this line.
 *
 * Honesty rules, all pinned by tests:
 * - The network segment is always present and always one of
 *   WiFi / 流量 / 无网络. NONE is its own verdict: a missing or
 *   internet-less network is NEVER reported as 流量 (the old
 *   isOnWifi()-style binary read did exactly that).
 * - The dual-channel segment appears ONLY when the cellular
 *   leg was actually granted (the service's dualActive, mirrored
 *   into DownloadCenter) — the switch being on is not enough.
 * - The gate segment names the FIRST closed gate in the drain's
 *   own order (v3.9: WiFi first, then charge; traffic last) and
 *   appears only when a task is actually being held by it. The
 *   subject is a not-yet-started task: QUEUED and due (the
 *   Android side computes [Snapshot.hasQueuedDueTask] and the
 *   per-task traffic evidence — see DownloadCenter). Running
 *   tasks are never "held" by these gates (the charge gate's
 *   doc says so explicitly), so they never feed the segment.
 */
object SummaryNetStatus {

    /** What the one capabilities read proved about the network. */
    enum class NetKind { WIFI, MOBILE, NONE }

    /** The gate that can be named on the line, in drain order. */
    enum class Gate { WIFI, CHARGE, TRAFFIC }

    data class Snapshot(
        val network: NetKind,
        /** The same read also saw NET_CAPABILITY_VALIDATED.
         *  Carried for completeness/diagnostics; the wording
         *  below does not need it (a portal WiFi is still WiFi —
         *  portal parking is a different surface's story). */
        val validated: Boolean,
        /** Dual channel REALLY active (cellular leg granted). */
        val dualActive: Boolean,
        /** 仅 WiFi switch. */
        val wifiOnlyOn: Boolean,
        /** 充电时才下载 switch. */
        val chargeOnlyOn: Boolean,
        /** External power connected right now. */
        val charging: Boolean,
        /** A QUEUED, due, un-parked task exists — the only kind
         *  of task a gate can currently be holding. */
        val hasQueuedDueTask: Boolean,
        /** Per-task traffic evidence: a QUEUED due task that
         *  TrafficGuard.shouldBlock judges blocked under the
         *  service's own inputs (and no active dual channel,
         *  which the service counts as confirmation). NOT the
         *  threshold switch alone. */
        val trafficBlockedTask: Boolean,
    )

    /** The first closed gate holding a queued task, or null. */
    fun gateOf(s: Snapshot): Gate? = when {
        // Drain order (DownloadService.drainQueue): the WiFi
        // gate is judged first, the charge gate second — when
        // both are shut the card names WiFi, exactly like the
        // drain's per-task notes do.
        s.wifiOnlyOn && s.network != NetKind.WIFI && s.hasQueuedDueTask ->
            Gate.WIFI
        ChargeGuard.shouldPark(s.chargeOnlyOn, s.charging) &&
            s.hasQueuedDueTask -> Gate.CHARGE
        s.trafficBlockedTask -> Gate.TRAFFIC
        else -> null
    }

    /** The full one-line text: network, then dual-channel when
     *  really active, then the first closed gate when it is
     *  really holding a task. Segments join with " · ". */
    fun line(s: Snapshot): String = buildString {
        append(
            when (s.network) {
                NetKind.WIFI -> "当前网络：WiFi"
                NetKind.MOBILE -> "当前网络：流量"
                NetKind.NONE -> "当前网络：无网络"
            }
        )
        if (s.dualActive) append(" · 双通道生效中")
        when (gateOf(s)) {
            Gate.WIFI -> append(" · 排队任务在等 WiFi")
            Gate.CHARGE -> append(" · 排队任务在等充电")
            Gate.TRAFFIC -> append(" · 排队任务被流量保护挡着")
            null -> {}
        }
    }
}
