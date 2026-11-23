package com.tuxundown.android.core

/**
 * Network-outage recovery policy (v1.5).
 *
 * When a download fails because the NETWORK went away (as opposed to
 * the server saying no), the task should not sit there dead: it waits
 * quietly and re-queues itself when connectivity comes back. Bounded
 * — at most [MAX_ATTEMPTS] automatic recoveries per outage streak,
 * and a manual pause/resume/delete always wins (the deferred flag is
 * transient and cleared by any user action via enqueue()).
 *
 * Pure — unit-tested. The service owns the ConnectivityManager
 * callback and the deferred flags; this object only classifies.
 */
object NetRetry {

    const val MAX_ATTEMPTS = 3

    /**
     * True when [error] (an exception message captured by the engine)
     * looks like a connectivity loss rather than a server refusal.
     * HTTP status errors ("服务器返回 HTTP 403") are NOT network
     * errors: retrying those on a network callback would be wrong.
     *
     * CONTRACT (v4.5, audit round 30): this string form may
     * ONLY be fed RAW exception text — an original
     * Throwable's message, as the Throwable overload below
     * does while walking the cause chain. NEVER feed it
     * task.error: since v2.2 that field carries only
     * Errors.human's plain Chinese, which contains none of
     * these needles, so the verdict would silently always
     * be false (exactly the round-29 headline bug). Anything
     * that starts from a task record must judge the
     * engine's recorded verdict (task.netOutage) instead.
     */
    fun isNetworkError(error: String): Boolean {
        if (error.isBlank()) return false
        if (error.contains("HTTP ")) return false
        val needles = listOf(
            "Unable to resolve host",
            "No address associated",
            "Failed to connect",
            "Network is unreachable",
            "Connection reset",
            "Connection timed out",
            "Read timed out",
            "timed out",
            "timeout",
            "ENETUNREACH",
            "EHOSTUNREACH",
            "ECONNRESET",
            "Software caused connection abort",
            "Broken pipe",
        )
        return needles.any { error.contains(it, ignoreCase = true) }
    }

    /**
     * v4.4 (audit round 29): classify the RAW failure, never
     * the humanized card text. Since v2.2 every engine failure
     * passes through Errors.human before it lands in
     * task.error, and Errors.human deliberately DROPS the raw
     * English message — so the string classifier above could
     * never match an engine failure again, and the v1.5 outage
     * deferral silently stopped working for the HTTP paths
     * (verified exhaustively in the v4.4.0 checkpoint: every
     * task.error assignment is Errors.human / Notes / a blank
     * fill). The engine now records THIS verdict on the task
     * (transient TaskRecord.netOutage) at the failure sites
     * where the raw exception still exists, and the service
     * defers on the verdict.
     *
     * The walk covers the cause chain and judges by TYPE
     * first (a connect / DNS / timeout / socket failure is a
     * connectivity loss whatever its message says), then by
     * the same message needles as the string form. Our own
     * plain-Chinese IllegalStateExceptions match neither —
     * a server refusal, a changed file or a full disk must
     * never park as an "outage".
     */
    fun isNetworkError(e: Throwable?): Boolean {
        var cur = e
        while (cur != null) {
            when (cur) {
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketTimeoutException,
                is java.net.SocketException,
                is java.io.InterruptedIOException
                -> return true
            }
            val msg = cur.message
            if (msg != null && isNetworkError(msg)) return true
            cur = cur.cause
        }
        return false
    }

    fun shouldDefer(usedAttempts: Int): Boolean = usedAttempts < MAX_ATTEMPTS

    /**
     * v6.6 (audit round 51): the drain loop's poll backstop for
     * NET parks — should this pass release them? The release
     * events (the network callback's onAvailable) are
     * signal-driven, but a net park can land AFTER the new
     * network's onAvailable already fired (the switch scenario:
     * the failure that parks the task is judged on the service
     * side once the engine returns, which can be later than the
     * callback for the network that replaced the dead one) —
     * from then on no event owns the release and the task would
     * sit parked until an unrelated validated-capabilities
     * event, a manual resume or a service restart. The drain's
     * 5 s poll is the backstop, held to the same standard the
     * portal backstop (v4.4) already uses: a VALIDATED network
     * must exist NOW — plain availability is not enough here
     * precisely because the event that proves mere availability
     * was already spent; validation is the signal that cannot
     * have been missed twice. Pure verdict over the two facts;
     * the service owns both reads and the release itself
     * (clearDeferredParks with portal = false, budget and park
     * semantics untouched).
     */
    fun pollShouldReleaseNetPark(
        hasNetParked: Boolean,
        hasValidatedNetwork: Boolean,
    ): Boolean = hasNetParked && hasValidatedNetwork

    /**
     * v5.3 (audit round 38): the drain's idle-wait notification
     * text while it sits on parked tasks — (title, text), or
     * null when nothing is parked. Both deferral families are
     * bounded by [shouldDefer] (the portal park borrows this
     * object's budget), so their shared wait wording lives here
     * too. The drain used to post 「等待网络恢复 / 断网的任务
     * 已排队」 for ANY park, overwriting the 「等待登录 WiFi」
     * text the portal park itself had just posted — for a
     * portal-only park the network is UP, only the login is
     * missing, and the card's note says so; the notification
     * now names the same reason. A network park wins when both
     * kinds are parked: with the network actually down, a login
     * cannot happen anyway, so 等待网络恢复 is the first
     * blocker — the same first-gate naming order the summary
     * card's network line uses.
     */
    fun deferredWaitText(
        hasNetParked: Boolean,
        hasPortalParked: Boolean,
    ): Pair<String, String>? = when {
        hasNetParked ->
            "等待网络恢复" to "断网的任务已排队，网络一回来就自动继续"
        hasPortalParked ->
            "等待 WiFi 登录" to "这个 WiFi 要先登录，登录后自动继续"
        else -> null
    }
}
