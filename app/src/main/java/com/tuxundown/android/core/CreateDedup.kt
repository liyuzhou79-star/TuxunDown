package com.tuxundown.android.core

/**
 * v5.4: the ONE task-creation dedup verdict, shared by every
 * creation entry point (home paste / batch / text extract /
 * share / .txt import / sniff single + batch / scan / in-app
 * update download). Before this round each funnel carried its
 * own inline copy of the rule (exact string equality, FAILED
 * excluded) and one funnel (the update download) carried none;
 * now every funnel asks this object BEFORE a TaskRecord is
 * built, and a Duplicate verdict means: no record, no
 * DownloadCenter.enqueue, no service start. The gate is
 * deliberately NOT inside enqueue — enqueue also re-queues
 * PAUSED / FAILED tasks the user already owns, which is not
 * creation and must never be blocked by this rule.
 *
 * THE RULING (pinned by CreateDedupTest):
 *
 * - Unfinished = every status except DONE: QUEUED, RUNNING,
 *   PAUSED and FAILED all block. FAILED blocks on purpose: a
 *   failed task is still sitting in the list waiting for the
 *   user's retry, so a second record for the same link would
 *   be a true duplicate (two records, one file, and the retry
 *   button already exists on the first). DONE does NOT block:
 *   a finished download in the history is no reason to refuse
 *   downloading the same file again.
 * - Same link = the {primary + mirrors} link SETS overlap.
 *   A task's mirrors are by definition the same file as its
 *   primary (the engine validates them before taking a byte),
 *   so ANY overlap — candidate primary vs existing primary,
 *   candidate primary vs an existing mirror, candidate mirror
 *   vs existing primary / mirror — is the same file. A
 *   different mirror set does NOT exempt a candidate whose
 *   primary matches: re-downloading through other mirrors
 *   still produces the same file twice.
 * - URL comparison uses [normalize] ONLY: scheme and host are
 *   case-insensitive (lowercased), a default port (80 http /
 *   443 https / 21 ftp) is dropped, an empty path reads as
 *   "/", and the fragment is dropped (it is never sent to a
 *   server). Everything else is compared EXACTLY as written:
 *   the path and query stay case-sensitive, a trailing slash
 *   is NOT stripped ("/dir" and "/dir/" can be different
 *   resources), percent-escapes are not decoded and signed
 *   query strings are not canonicalized. The bias is
 *   deliberate: a missed duplicate costs one extra record,
 *   a false duplicate refuses a file the user asked for.
 *
 * Batch self-dedup: [planBatch] judges each candidate against
 * the existing tasks PLUS the candidates already accepted
 * from the same batch (as QUEUED snapshots), so the second
 * occurrence of a link inside one batch is a duplicate of the
 * first. The in-app batch flows create sequentially through
 * the single-create funnel, where the same effect falls out
 * of judging against the live store on every call; planBatch
 * is the pure form of that rule for any caller holding a
 * whole batch up front, and the tests pin both to the same
 * verdicts.
 *
 * Pure logic: no Android API, no I/O, no clock.
 */
object CreateDedup {

    /** Snapshot of a task that already exists. */
    data class Existing(
        val url: String,
        val status: TaskStatus,
        val mirrors: List<String> = emptyList(),
    )

    /** A task someone is trying to create. */
    data class Candidate(
        val url: String,
        val mirrors: List<String> = emptyList(),
    )

    sealed class Verdict {
        object Allow : Verdict()
        data class Duplicate(val blocker: Existing) : Verdict()
    }

    /** The plan for a whole batch: who may be created and who
     *  is a duplicate (paired with the task that blocks it —
     *  for a batch-internal duplicate, a QUEUED snapshot of
     *  the accepted earlier candidate). */
    data class BatchPlan(
        val accepted: List<Candidate>,
        val duplicates: List<Pair<Candidate, Existing>>,
    )

    /** Every status except DONE is unfinished (see the file
     *  doc for why FAILED counts). */
    fun isUnfinished(status: TaskStatus): Boolean = status != TaskStatus.DONE

    /** The one verdict every creation entry point shares. */
    fun judge(existing: List<Existing>, candidate: Candidate): Verdict {
        val candidateLinks = linkSet(candidate.url, candidate.mirrors)
        if (candidateLinks.isEmpty()) return Verdict.Allow
        for (e in existing) {
            if (!isUnfinished(e.status)) continue
            val existingLinks = linkSet(e.url, e.mirrors)
            if (candidateLinks.any { it in existingLinks }) {
                return Verdict.Duplicate(e)
            }
        }
        return Verdict.Allow
    }

    /** Judge a whole batch in order; accepted candidates join
     *  the pool (as QUEUED) for the candidates behind them. */
    fun planBatch(existing: List<Existing>, candidates: List<Candidate>): BatchPlan {
        val pool = existing.toMutableList()
        val accepted = ArrayList<Candidate>()
        val duplicates = ArrayList<Pair<Candidate, Existing>>()
        for (c in candidates) {
            when (val v = judge(pool, c)) {
                is Verdict.Allow -> {
                    accepted.add(c)
                    pool.add(Existing(c.url, TaskStatus.QUEUED, c.mirrors))
                }
                is Verdict.Duplicate -> duplicates.add(c to v.blocker)
            }
        }
        return BatchPlan(accepted, duplicates)
    }

    /** Convenience for callers holding live records: snapshot
     *  them (url + status + sources) and judge. */
    fun judgeTasks(
        tasks: List<TaskRecord>,
        url: String,
        mirrors: List<String> = emptyList(),
    ): Verdict = judge(
        tasks.map { Existing(it.url, it.status, it.sources.toList()) },
        Candidate(url, mirrors),
    )

    fun isDuplicate(
        tasks: List<TaskRecord>,
        url: String,
        mirrors: List<String> = emptyList(),
    ): Boolean = judgeTasks(tasks, url, mirrors) is Verdict.Duplicate

    /** The normalized link set of one side: primary + mirrors,
     *  blanks dropped. */
    private fun linkSet(url: String, mirrors: List<String>): Set<String> {
        val out = HashSet<String>()
        val primary = normalize(url)
        if (primary.isNotBlank()) out.add(primary)
        for (m in mirrors) {
            val n = normalize(m)
            if (n.isNotBlank()) out.add(n)
        }
        return out
    }

    /**
     * The ONLY normalization applied before comparison (see
     * the file doc for the full rule and its bias): trim,
     * lowercase scheme + host, drop a default port, empty
     * path -> "/", drop the fragment. A string without a
     * "scheme://" prefix is compared as trimmed, verbatim.
     */
    fun normalize(raw: String): String {
        val s = raw.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return s
        val scheme = s.substring(0, schemeEnd).lowercase()
        var rest = s.substring(schemeEnd + 3)
        // The fragment is never sent to a server: not part of
        // the link's identity.
        rest = rest.substringBefore('#')
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' }
            .let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authEnd)
        val tail = rest.substring(authEnd) // path + query, may be ""
        // Userinfo (if any) is case-sensitive: keep it verbatim.
        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val host: String
        val port: String?
        if (hostPort.startsWith("[")) {
            // IPv6 literal: the host runs to the closing bracket.
            val close = hostPort.indexOf(']')
            if (close < 0) return s
            host = hostPort.substring(0, close + 1).lowercase()
            port = if (close + 1 < hostPort.length && hostPort[close + 1] == ':') {
                hostPort.substring(close + 2)
            } else {
                null
            }
        } else {
            val colon = hostPort.lastIndexOf(':')
            val after = if (colon >= 0) hostPort.substring(colon + 1) else ""
            if (colon >= 0 && after.isNotEmpty() && after.all { it.isDigit() }) {
                host = hostPort.substring(0, colon).lowercase()
                port = after
            } else {
                host = hostPort.lowercase()
                port = null
            }
        }
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            "ftp" -> 21
            else -> null
        }
        val portPart = if (port != null && port.toIntOrNull() != defaultPort) {
            ":$port"
        } else {
            ""
        }
        val tailPart = when {
            tail.isEmpty() -> "/"
            tail.startsWith("?") -> "/$tail"
            else -> tail
        }
        return scheme + "://" + userinfo + host + portPart + tailPart
    }
}
