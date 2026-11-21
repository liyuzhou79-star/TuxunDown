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

    fun shouldDefer(usedAttempts: Int): Boolean = usedAttempts < MAX_ATTEMPTS
}
