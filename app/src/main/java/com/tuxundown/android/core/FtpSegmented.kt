package com.tuxundown.android.core

/**
 * v4.8: FTP multi-connection segmentation — the PURE entry/worker
 * decisions for DownloadEngine.runFtpSegmented, unit-tested
 * (FtpSegmentedTest). The socket work itself lives in FtpClient
 * and the engine; everything that can be decided without a socket
 * lives here.
 *
 * Why a dedicated path instead of force-fitting the HTTP
 * segmented engine: the HTTP machinery (workerLoop / ActiveChunk /
 * ThreadScaler / stall eviction over HttpURLConnection) is shaped
 * around Range requests. FTP ranges are REST + RETR on a
 * per-worker control connection — a different transport with the
 * same DISCIPLINE (persisted ChunkPlanner grid + bitmap,
 * PartIntegrity gate, per-chunk retry from pos, 1 s sampler).
 *
 * Recorded narrowings (v4.8, deliberate):
 * - NO endgame split / stealTail: chunk sizes are grid-bounded,
 *   so the tail waste is at most one chunk per worker.
 * - NO ThreadScaler dynamics: the worker count is fixed per run
 *   and re-decided on the next run.
 * - NO mirrors: task.sources are HTTP concepts; an ftp task's
 *   sources are ignored by every FTP path and stay ignored.
 * - NO HostMemory recording: FTP hosts do not pollute HTTP
 *   learning; the settled callback stays HTTP-only.
 * - NO single-stream rescue when every worker fails: the run
 *   fails with progress kept, exactly like the HTTP segmented
 *   path (a server whose session cap is 1 still lets worker 0
 *   transfer — the other workers retire quietly at login — so
 *   the realistic refusal modes are already covered).
 */
object FtpSegmented {

    /**
     * Hard ceiling on simultaneous FTP control connections for
     * one task. Why 8:
     * - public FTP servers commonly cap simultaneous connections
     *   per IP in the 2–10 range, and some throttle or ban
     *   aggressive clients — 8 stays inside the common band;
     * - every FTP connection costs a full control login (there is
     *   no keep-alive pool to borrow, unlike HTTP), so each extra
     *   connection is more expensive than an HTTP one;
     * - the global connection budget (default 32) is shared with
     *   HTTP tasks — 8 leaves room for them and matches the
     *   coarsest chunk grid's useful parallelism for typical FTP
     *   file sizes.
     */
    const val MAX_CONNECTIONS = 8

    /** Why a run may not segment; null from [decide] = it may. */
    enum class Block {
        /** SIZE did not report a usable total (<= 0 / unknown). */
        SIZE_UNKNOWN,

        /** The REST probe failed — no resume, no ranges. */
        REST_UNSUPPORTED,

        /** The decided grid has fewer than 2 chunks to share. */
        TOO_FEW_CHUNKS,
    }

    /**
     * Entry decision: segmentation needs ALL of a known total, a
     * passed REST probe, and at least 2 chunks. Returns the first
     * unmet condition, or null when the run may segment. (The
     * persisted singleThreadOnly flag is NOT an input: runFtp set
     * it on every ftp task since v1.7, so a persisted true is the
     * old engine's own marking, not a user verdict — the v4.8 FTP
     * path decides by probe and rewrites the flag truthfully.)
     */
    fun decide(totalKnown: Boolean, restSupported: Boolean, chunkCount: Int): Block? = when {
        !totalKnown -> Block.SIZE_UNKNOWN
        !restSupported -> Block.REST_UNSUPPORTED
        chunkCount < 2 -> Block.TOO_FEW_CHUNKS
        else -> null
    }

    /**
     * Live worker count for a segmented run: the narrowest of the
     * FTP ceiling, the task's live global-cap allowance, the
     * task's own thread setting (clamped into 1..MAX) and the
     * chunk count — never more workers than chunks, never below
     * 1 (the caller only segments when chunkCount >= 2, but a
     * degenerate allowance must still leave worker 0 able to run:
     * the allowance is a ceiling on parallelism, not a veto).
     */
    fun workerCount(allowance: Int, taskThreads: Int, chunkCount: Int): Int {
        val byThreads = taskThreads.coerceIn(1, MAX_CONNECTIONS)
        return minOf(MAX_CONNECTIONS, allowance, byThreads, chunkCount)
            .coerceAtLeast(1)
    }
}
