package com.tuxundown.android.core

/**
 * Queue preheat decision (v4.5): while at least one task is
 * RUNNING, warm the FIRST waiting task's primary host — DNS
 * pre-resolve + one pooled connection — so when its turn comes
 * the engine's probe / first chunks skip the cold DNS + TCP +
 * TLS round trips. The execution lives in DownloadService (a
 * daemon side thread on the drain loop's own beat); this object
 * is only the pure guard decision, so every guard is pinned by
 * unit tests without a service or a network.
 *
 * Preheat is INVISIBLE and DISPOSABLE by design:
 * - It never occupies a download slot, never touches the
 *   speed limiter / connection budget, and writes NO task
 *   fields. Its whole product is the platform DNS cache and
 *   the platform keep-alive pool.
 * - A failed or stale preheat changes nothing: the real start
 *   re-does DNS, connection and every validation exactly as
 *   before. A pre-resolved address is a connection-layer
 *   accelerator only — the engine's probe, validator binding
 *   and integrity checks are never skipped or pre-answered.
 * - It is NOT a start decision. The target task's own gates
 *   （仅 WiFi switch, its per-task traffic-guard verdict,
 *   schedule gates) are deliberately NOT inputs here:
 *   preheating spends no file bytes, and when the task really
 *   starts every gate judges again as usual. Queue ELIGIBILITY
 *   is the one queue-side input, and only because it defines
 *   WHICH task is "first in line" ([QueueOrder.nextEligible]
 *   order — priority first, FIFO within a tier, deferred /
 *   not-yet-due tasks are not in line at all). The one global
 *   gate that IS an input is the charge park (see the guard
 *   list below): it vetoes preheating altogether, not because
 *   it judges the task, but because while it is closed NO
 *   queued task can start at all (v5.0, audit round 35).
 *
 * Guards (each one is a parameter, each one vetoes):
 * - No RUNNING task -> no preheat. With nobody downloading,
 *   the next start is the ordinary start path's own business
 *   (enqueue-time DNS pre-resolve + the engine's run-start
 *   warmup already cover it).
 * - Network: WiFi present, or the dual channel (WiFi+cellular)
 *   actually active. Pure cellular never preheats — a warm-up
 *   request is still traffic the user did not ask to download.
 * - Traffic protection ON (a threshold is set) -> never,
 *   outright, whatever the network: the user asked for mobile
 *   data to be guarded, and an optimisation does not get to
 *   spend any of it on a task that has not started.
 * - Charge park ACTIVE (v5.0, audit round 35): the 充电时才下载
 *   gate is currently closed (switch on, no power) -> never.
 *   No queued task can start while the gate is closed — the
 *   drain marks the picked task chargeDeferred and breaks —
 *   so a warm connection could only sit in the pool until it
 *   expires, spending exactly the battery the switch exists
 *   to save. This is a LIVE service-side condition, not the
 *   task's chargeDeferred flag: the flag can be stale-true
 *   after power returns (it clears only when the drain next
 *   picks the task), and a queued task the drain never got to
 *   pick (slots full) carries no flag at all while the gate
 *   is just as closed. Net / portal parks need no such
 *   parameter: QueueOrder.nextEligible already excludes
 *   netDeferred / portalDeferred candidates outright.
 * - Proxy active -> never (Warmup's standing rule: behind a
 *   proxy, resolution happens on the proxy side, and a direct
 *   warm connection would break the v1.9 never-go-direct rule).
 * - One target at a time: while a preheat is in flight, no
 *   second one starts.
 * - Once per task+host: a task whose primary host was already
 *   warmed while it waited is not warmed again, however often
 *   the drain re-judges (queue jitter / poll beats must not
 *   spam connections). The service clears the mark when the task
 *   really starts, so a later re-queue may warm it again.
 * - Only the PRIMARY link's host: mirrors are the engine
 *   warmup's job at real start (as since v4.2), and a queued
 *   task's mirrors have not been validated yet anyway.
 * - Non-http(s) primaries warm nothing ([Warmup.hostToPreResolve]
 *   rules: FTP resolves inside its own client).
 *
 * Pure — unit-tested (QueuePreheatTest).
 */
object QueuePreheat {

    /** One approved preheat: warm [host] for task [taskId]
     *  (whose primary link is [url]). [key] is the once-only
     *  mark — task id + host — the service records while the
     *  task waits and clears when it really starts. */
    data class Target(
        val taskId: String,
        val url: String,
        val host: String,
        val key: String,
    )

    /** The once-only mark for a task+host pair. Hosts are
     *  case-insensitive, so the mark is too. */
    fun keyFor(taskId: String, host: String): String =
        taskId + "\n" + host.lowercase()

    /**
     * Decide the preheat target for this drain pass, or null
     * when any guard vetoes. [alreadyWarmed] holds the
     * [keyFor] marks of task+host pairs already warmed during
     * this wait; [inFlightKey] is the mark of the preheat
     * currently running, or null.
     */
    fun decide(
        tasks: List<TaskRecord>,
        now: Long,
        onWifi: Boolean,
        dualActive: Boolean,
        trafficProtectionOn: Boolean,
        chargeParked: Boolean,
        proxyActive: Boolean,
        alreadyWarmed: Set<String>,
        inFlightKey: String?,
    ): Target? {
        // Someone must actually be downloading (see class doc).
        if (tasks.none { it.status == TaskStatus.RUNNING }) return null
        // Network guard: WiFi, or the dual channel really up.
        if (!onWifi && !dualActive) return null
        // Traffic protection vetoes outright.
        if (trafficProtectionOn) return null
        // Charge park vetoes outright (see class doc): while
        // the gate is closed no queued task can start, so a
        // warmed connection would only expire unused.
        if (chargeParked) return null
        // Proxy guard (also enforced again by hostToPreResolve).
        if (proxyActive) return null
        // Exactly one preheat at a time.
        if (inFlightKey != null) return null
        // First in line, in the drain's own order.
        val candidate = QueueOrder.nextEligible(tasks, now) ?: return null
        // The host decision reuses Warmup's rules verbatim
        // (proxy -> null, http/https only, hostless -> null).
        val host = Warmup.hostToPreResolve(candidate.url, proxyActive) ?: return null
        val key = keyFor(candidate.id, host)
        // Once per task+host while it waits.
        if (key in alreadyWarmed) return null
        return Target(candidate.id, candidate.url, host, key)
    }

    /**
     * Is a finished / in-flight preheat's result still worth
     * keeping? The target must still exist, still be QUEUED,
     * still be FIRST in line, and still point at the same host.
     * Anything else — deleted, paused, started, queue order
     * changed, link edited — and the result is discarded (the
     * caller disconnects instead of pooling the connection and
     * never writes anything anywhere).
     */
    fun stillTarget(
        tasks: List<TaskRecord>,
        now: Long,
        taskId: String,
        host: String,
    ): Boolean {
        val t = tasks.firstOrNull { it.id == taskId } ?: return false
        if (t.status != TaskStatus.QUEUED) return false
        if (QueueOrder.nextEligible(tasks, now)?.id != taskId) return false
        val current = Warmup.hostToPreResolve(t.url, proxyActive = false)
            ?: return false
        return current.equals(host, ignoreCase = true)
    }
}
