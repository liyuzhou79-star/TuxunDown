package com.tuxundown.android.core

/**
 * v5.1 memory-tiered buffer budgets (Step B): the device's memory
 * class decides ONE budget table for the whole process, and every
 * buffer consumer (segmented read buffers, single-stream buffer,
 * the HLS in-flight byte budget) reads from that single table —
 * there is no second source of truth.
 *
 * Why tiers at all (Step A ledger, base a99e26eb): the segmented
 * account held on a 128 MB memory-class device (~74 MB worst
 * against a 128 MB class), but the HLS account did NOT — its
 * 48 MB in-flight budget was PER TASK, one segment could occupy
 * 64 MB whole, the write-cursor segment was exempt without any
 * bound, and AES decryption made a second full copy, so ONE
 * pathological HLS task could pin ~112-176 MB and five concurrent
 * HLS tasks carried 240 MB of budgets alone. This file is the
 * pure half of the fix; DownloadEngine / DownloadCenter hold the
 * thin Android-facing half (reading ActivityManager once).
 *
 * Tier table (all sizes in bytes; MB = 1024 * 1024):
 *
 *   tier      per-task read   HLS global   HLS max      cursor
 *             buffer budget   budget       segment      exempt cap
 *   LOW       2 MB            16 MB        16 MB        16 MB (1 seg)
 *   STANDARD  4 MB            48 MB        32 MB        32 MB (1 seg)
 *   HIGH      6 MB            64 MB        64 MB        64 MB (1 seg)
 *
 * STANDARD's per-task 4 MB is EXACTLY the pre-v5.1 account
 * (bufferSizeFor's doc), so mid-range devices see zero behaviour
 * change — a hard requirement of this step. The HLS global budget
 * is shared by ALL concurrent HLS tasks (it replaces the old
 * per-task 48 MB): STANDARD's global 48 MB therefore never
 * exceeds what a SINGLE task could already pin before.
 *
 * Worst-case re-check against the Step A ledger, LOW tier
 * (128 MB memory class, maxConcurrent = 5, global conn cap 128):
 *   baseline app heap (Compose + runtime, Step A estimate) ~40 MB
 *   segmented read buffers: 2 MB/task, so even 5 segmented tasks
 *     pin at most 5 x 2 MB = 10 MB (and any task mix that includes
 *     HLS tasks pins LESS segmented memory, never more)
 *   HLS, shared across every HLS task together, not per task:
 *     buffered-but-unwritten <= global budget          16 MB
 *     write-cursor segment in flight <= exempt cap      16 MB
 *     AES decrypt second copy of that one segment     <= 16 MB
 *     HLS subtotal                                    <= 48 MB
 *   task records + chunk bitmaps (Step A)             ~13 MB
 *   TOTAL worst ~ 40 + 10 + 48 + 13 = 111 MB < 128 MB class,
 *   and that total double-counts: the mix that maximises the
 *   segmented line (5 segmented tasks) has no HLS task at all,
 *   so the realistic worst is ~40 + 8 + 48 + 13 = 109 MB.
 * STANDARD (class >= 192 MB): 40 + 20 + (48 + 32 + 32) + 13
 *   = 185 MB worst against a >= 192 MB class — holds, tightest
 *   exactly at the 192 MB floor. HIGH (class >= 384 MB):
 *   40 + 30 + (64 + 64 + 64) + 13 = 275 MB — holds with room.
 *
 * The tier is decided ONCE per process (DownloadCenter caches
 * it at init) and a budget is read ONCE at a run's start (the
 * engine snapshots it at construction); a running task's buffers
 * are NEVER resized mid-run.
 */
enum class MemoryTier { LOW, STANDARD, HIGH }

object MemoryTiers {

    /** At or below this memory class (MB) the device is LOW —
     *  covers the 128 MB class with margin for 144/160 MB
     *  classes whose usable headroom is barely larger. */
    const val LOW_MAX_MEMORY_CLASS_MB = 160

    /** At or above this memory class (MB) the device is HIGH. */
    const val HIGH_MIN_MEMORY_CLASS_MB = 384

    /**
     * The full budget table for one tier. [perTaskBufferBytes]
     * is the segmented/single-stream read-buffer budget per task
     * (the number bufferSizeFor divides by the thread count).
     * [hlsGlobalBudgetBytes] bounds fetched-but-unwritten HLS
     * segment bytes across ALL HLS tasks together.
     * [hlsMaxSegmentBytes] bounds ONE segment held whole in
     * memory; a larger segment fails with a plain-language
     * reason instead of risking an OOM. [hlsCursorExemptCapBytes]
     * bounds the write-cursor exemption: cursor segments may
     * bypass the global budget, but the bytes they may pin that
     * way (in flight + buffered) never exceed this cap — with
     * the table above that is exactly one maximum-size segment.
     */
    data class Budget(
        val perTaskBufferBytes: Long,
        val hlsGlobalBudgetBytes: Long,
        val hlsMaxSegmentBytes: Int,
        val hlsCursorExemptCapBytes: Long,
    )

    private const val MB = 1024L * 1024L

    fun budgetFor(tier: MemoryTier): Budget = when (tier) {
        MemoryTier.LOW -> Budget(
            perTaskBufferBytes = 2 * MB,
            hlsGlobalBudgetBytes = 16 * MB,
            hlsMaxSegmentBytes = (16 * MB).toInt(),
            hlsCursorExemptCapBytes = 16 * MB,
        )
        MemoryTier.STANDARD -> Budget(
            perTaskBufferBytes = 4 * MB,
            hlsGlobalBudgetBytes = 48 * MB,
            hlsMaxSegmentBytes = (32 * MB).toInt(),
            hlsCursorExemptCapBytes = 32 * MB,
        )
        MemoryTier.HIGH -> Budget(
            perTaskBufferBytes = 6 * MB,
            hlsGlobalBudgetBytes = 64 * MB,
            hlsMaxSegmentBytes = (64 * MB).toInt(),
            hlsCursorExemptCapBytes = 64 * MB,
        )
    }

    /**
     * Pure tier decision from the two Android signals.
     * [isLowRamDevice] (the platform's own low-RAM verdict)
     * forces LOW no matter how large the nominal class is —
     * Android Go-class devices can report a mid-size class
     * while still being memory-starved in practice. An
     * unreadable class (<= 0) falls back to STANDARD: the
     * isLowRamDevice flag is the reliable distress signal,
     * and an unknown class alone must not cripple a device
     * that may be perfectly capable.
     */
    fun tierFor(memoryClassMb: Int, isLowRamDevice: Boolean): MemoryTier = when {
        isLowRamDevice -> MemoryTier.LOW
        memoryClassMb in 1..LOW_MAX_MEMORY_CLASS_MB -> MemoryTier.LOW
        memoryClassMb >= HIGH_MIN_MEMORY_CLASS_MB -> MemoryTier.HIGH
        else -> MemoryTier.STANDARD
    }

    /** Plain-language tier name for the diagnostics page. */
    fun tierLabel(tier: MemoryTier): String = when (tier) {
        MemoryTier.LOW -> "低（这台设备内存较小，缓冲已自动收紧）"
        MemoryTier.STANDARD -> "标准（按这台设备的内存用的默认缓冲）"
        MemoryTier.HIGH -> "高（这台设备内存充足，缓冲已适当放宽）"
    }
}

/**
 * The HLS in-flight byte ledger (v5.1, Step B): ONE instance is
 * shared by every HLS run in the process (see
 * [HlsSharedBudget]), which is what turns the old per-task
 * 48 MB budget into a global one. Pure bookkeeping — no Android,
 * no clock; the engine drives it.
 *
 * Two accounts, one lock:
 * - [bufferedBytes]: segment bytes fetched but not yet written,
 *   across all HLS runs. Prefetch submissions are gated on this
 *   staying under [globalBudgetBytes]. (Bytes still IN FLIGHT —
 *   a fetch that has started but not landed — are not counted
 *   here, exactly as the pre-v5.1 per-task counter behaved; the
 *   submit window and the per-segment size cap bound them.)
 * - the write-cursor exemption: the segment AT a run's write
 *   cursor must always be fetchable or that run can never
 *   finish, so cursor fetches bypass the buffered gate — but
 *   the exemption is BOUNDED by claims: each claimed cursor
 *   fetch reserves [maxSegmentBytes] of exemption, and claims
 *   stop being granted once [cursorExemptCapBytes] is fully
 *   reserved. A claim is held from submit until that segment
 *   is written (or the run ends), so cursor bytes in flight +
 *   buffered can never exceed the cap.
 *
 * Release discipline: every release floors at zero — a double
 * release or a release after a cancel-cleanup can never drive
 * an account negative and silently inflate the budget.
 */
class HlsByteBudget(
    val globalBudgetBytes: Long,
    val maxSegmentBytes: Int,
    val cursorExemptCapBytes: Long,
) {
    private var buffered: Long = 0
    private var cursorClaims: Int = 0

    /** How many cursor fetches the exemption cap can cover at
     *  once (at least 1: a single run's cursor must always be
     *  claimable eventually). */
    val maxCursorClaims: Int =
        (cursorExemptCapBytes / maxSegmentBytes.coerceAtLeast(1)).toInt().coerceAtLeast(1)

    @Synchronized
    fun bufferedBytes(): Long = buffered

    @Synchronized
    fun cursorClaims(): Int = cursorClaims

    /** May a NON-cursor (prefetch) segment be submitted now? */
    @Synchronized
    fun canSubmitPrefetch(): Boolean = buffered < globalBudgetBytes

    /**
     * Atomically reserve [bytes] of buffered budget; false (and
     * nothing reserved) when they would exceed the global
     * budget. For callers that know a segment's size up front.
     */
    @Synchronized
    fun tryAcquire(bytes: Long): Boolean {
        if (bytes <= 0) return true
        if (buffered + bytes > globalBudgetBytes) return false
        buffered += bytes
        return true
    }

    /** A fetch landed: its bytes join the buffered account. */
    @Synchronized
    fun onFetched(bytes: Long) {
        if (bytes > 0) buffered += bytes
    }

    /** Bytes left the account (written, or discarded at run
     *  end). Floors at zero — never negative. */
    @Synchronized
    fun release(bytes: Long) {
        if (bytes <= 0) return
        buffered = (buffered - bytes).coerceAtLeast(0)
    }

    /** Claim the write-cursor exemption for one fetch; false
     *  when the exemption cap is already fully reserved. */
    @Synchronized
    fun tryClaimCursor(): Boolean {
        if (cursorClaims >= maxCursorClaims) return false
        cursorClaims++
        return true
    }

    /** Force a cursor claim past the cap — the engine's
     *  last-resort after its wait times out, so a writer can
     *  never starve forever behind another run's stuck cursor
     *  fetch. Still counted, still must be released. */
    @Synchronized
    fun forceClaimCursor() {
        cursorClaims++
    }

    @Synchronized
    fun releaseCursorClaim() {
        if (cursorClaims > 0) cursorClaims--
    }
}

/**
 * Process-wide holder for the single [HlsByteBudget]. The tier
 * is fixed for the process, so the first caller's budget table
 * wins; a later call with a different table (impossible in
 * production, possible across tests) keeps the existing ledger
 * rather than orphaning bytes other runs still hold against it.
 */
object HlsSharedBudget {
    @Volatile
    private var ledger: HlsByteBudget? = null

    fun get(budget: MemoryTiers.Budget): HlsByteBudget {
        ledger?.let { return it }
        synchronized(this) {
            ledger?.let { return it }
            val created = HlsByteBudget(
                globalBudgetBytes = budget.hlsGlobalBudgetBytes,
                maxSegmentBytes = budget.hlsMaxSegmentBytes,
                cursorExemptCapBytes = budget.hlsCursorExemptCapBytes,
            )
            ledger = created
            return created
        }
    }
}
