package com.tuxundown.android.core

/**
 * v4.4 (start-latency): the drain loop's wake-up signal.
 *
 * Until v4.3 every drain wait was a plain Thread.sleep — 5 s
 * for the WiFi / charge / deferred waits, up to 30 s for the
 * scheduled-task wait — and NOTHING could cut one short.
 * ensureWorker only starts a thread when the old one is DEAD,
 * so a task enqueued while the drain sat in the scheduled
 * wait started up to ~30 s late (up to ~5 s in a gate wait):
 * the new start command arrived, found the worker alive, and
 * went away, and the sleeping drain never heard about it.
 * (Interrupting was never an option: every sleep's catch
 * breaks the whole loop into stopSelf.)
 *
 * This signal replaces the sleep with a TIMED MONITOR WAIT:
 * the wait still blocks the thread for the full timeout when
 * nothing happens (no polling, no busy loop — the timeouts
 * and the verdicts that choose them are unchanged), but a
 * signal cuts it short and the drain re-loops and re-judges
 * everything from the top, exactly as it does when a sleep
 * expires naturally.
 *
 * No lost signals — the standard snapshot paradigm:
 * - the SENDER first makes its state visible (the task is
 *   QUEUED in the store / the park is cleared), THEN calls
 *   [signal];
 * - the DRAIN takes its [snapshot] BEFORE it evaluates the
 *   queue (at the top of the loop pass), and waits with
 *   [await] on that snapshot.
 * A signal that lands before the snapshot is covered by the
 * evaluation that follows the snapshot (the state is already
 * visible); a signal that lands after the snapshot has
 * bumped the generation, so [await] either never blocks or
 * is woken by notifyAll. There is no window in between.
 *
 * A REAL thread interrupt keeps its old meaning: [await]
 * lets InterruptedException propagate, and the drain's catch
 * breaks the loop into stopSelf, exactly as the old sleeps
 * did. Signals and interrupts are different mechanisms and
 * neither borrows the other's semantics.
 *
 * Plain JVM logic (one monitor + a generation counter), so
 * the paradigm itself is unit-testable without a service.
 */
class DrainWakeSignal {

    private val lock = Object()

    /** Guarded by [lock]. Bumped by every [signal]. */
    private var generation = 0L

    /** Wake every drain wait in progress. The caller must
     *  have made the state the drain should act on visible
     *  BEFORE calling this (see the class doc). */
    fun signal() {
        synchronized(lock) {
            generation++
            lock.notifyAll()
        }
    }

    /** The generation to wait against. Take it BEFORE
     *  evaluating the conditions that may lead to a wait. */
    fun snapshot(): Long = synchronized(lock) { generation }

    /**
     * Block until a signal lands after [since] (returns
     * true), the timeout expires (returns false), or the
     * thread is interrupted (InterruptedException, as with
     * Thread.sleep). Spurious wakeups are absorbed: the wait
     * only ends early on a real generation change, and the
     * remaining time is recomputed from a monotonic deadline,
     * so a spurious wake can neither shorten nor lengthen
     * the total wait.
     */
    fun await(timeoutMs: Long, since: Long): Boolean {
        if (timeoutMs <= 0L) return snapshot() != since
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (generation == since) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return false
                // Round up: wait(0) would block forever, and a
                // sub-millisecond remainder must not busy-spin.
                val waitMs = (remainingNs + 999_999L) / 1_000_000L
                lock.wait(waitMs)
            }
            return true
        }
    }

    companion object {
        /** The scheduled-task wait quantum, unchanged since
         *  the wait was introduced: at least 2 s (never a
         *  busy poll for a task due "now"), at most 30 s
         *  (a far-future slot never parks the drain longer
         *  than that in one wait). Pure — unit-tested. */
        fun scheduledWaitMs(nextAtMs: Long, nowMs: Long): Long =
            (nextAtMs - nowMs).coerceIn(2_000L, 30_000L)
    }
}
