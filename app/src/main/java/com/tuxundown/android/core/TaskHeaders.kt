package com.tuxundown.android.core

/**
 * Per-task request-header editing (v1.9): the text form of a
 * task's header map — one "Name: value" per line — plus the
 * validation that keeps pasted text from becoming header
 * injection. The engine reads task.headers every time it opens
 * a connection, so saved edits take effect on the next resume
 * (and on any connection a running task opens afterwards).
 *
 * Pure logic, unit-tested.
 */
object TaskHeaders {

    private val NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")

    data class Parsed(
        val headers: Map<String, String>,
        /** Plain-language problems with the lines that were skipped. */
        val problems: List<String>,
    )

    fun toText(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    /**
     * Parse edited text. Blank lines are ignored. A line with no
     * colon, an illegal header name, or a value containing
     * control characters (CR/LF above all — that is how header
     * injection works) is reported and skipped, never silently
     * reshaped: a mangled Cookie would fail invisibly.
     */
    fun parse(text: String): Parsed {
        val out = linkedMapOf<String, String>()
        val problems = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) {
                problems += "这一行不是「名字: 值」的格式，已跳过：${line.take(30)}"
                continue
            }
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (!NAME.matches(name)) {
                problems += "请求头名字不合法，已跳过：$name"
                continue
            }
            if (value.any { it.code < 0x20 || it.code == 0x7F }) {
                problems += "「$name」的值里有不可见字符，已跳过"
                continue
            }
            // Same header twice (any casing): the last one wins.
            val existing = out.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing != null) out.remove(existing)
            out[name] = value
        }
        return Parsed(out, problems)
    }

    /**
     * Headers whose values must never leave the device inside an
     * exported backup: credentials. v1.9 stripped only the session
     * Cookie; the v2.3 security audit adds Authorization and
     * Proxy-Authorization — a Bearer/Basic token is exactly as
     * much a login credential as a cookie, and the backup file
     * may be stored or sent anywhere.
     */
    fun isSensitive(name: String): Boolean =
        name.equals("Cookie", ignoreCase = true) ||
            name.equals("Authorization", ignoreCase = true) ||
            name.equals("Proxy-Authorization", ignoreCase = true)

    /** A copy of [headers] with the sensitive entries removed. */
    fun strippedForExport(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !isSensitive(it) }
}
