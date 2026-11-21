package com.tuxundown.android.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

/**
 * Minimal hand-written FTP client (v1.7) — just enough of RFC 959
 * for downloading one file, with no new dependency:
 *
 * - passive mode only (PASV): the phone is always behind NAT, so
 *   active mode could never work;
 * - TYPE I (binary) transfers;
 * - SIZE probe for the total length (-1 when the server refuses);
 * - REST probe + restart offset for resume.
 *
 * One client instance = one control connection = one transfer.
 * All failures surface as IllegalStateException with a plain
 * Chinese reason, matching the HTTP engine's style.
 */
class FtpClient {

    private var control: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null
    private var dataSocket: Socket? = null
    private var controlHost: String = ""

    /** Connect and read the greeting (220). */
    fun connect(host: String, port: Int) {
        controlHost = host
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        control = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
        val code = readReply()
        if (code != 220) throw IllegalStateException("FTP 服务器拒绝连接（$code）")
    }

    /** USER/PASS login; [user] "anonymous" needs no real password. */
    fun login(user: String, password: String) {
        var code = command("USER $user")
        if (code == 331) code = command("PASS $password")
        if (code != 230 && code != 202) {
            throw IllegalStateException("FTP 登录失败：账号或密码不对（$code）")
        }
    }

    /** Binary transfer mode (TYPE I) — without it servers may mangle bytes. */
    fun binary() {
        val code = command("TYPE I")
        if (code != 200) throw IllegalStateException("FTP 服务器不支持二进制模式（$code）")
    }

    /** File size via SIZE; -1 when the server will not say. */
    fun size(path: String): Long {
        val (code, text) = commandFull("SIZE $path")
        if (code != 213) return -1
        return text.substringAfter(' ').trim().toLongOrNull() ?: -1
    }

    /**
     * Whether the server accepts REST (resume). REST 0 is the
     * standard probe: 350 means restarts are honoured. The pending
     * marker is 0, so a following plain RETR is unaffected.
     */
    fun restSupported(): Boolean = command("REST 0") == 350

    /**
     * Stream the file at [path] starting at byte [offset] (REST when
     * offset > 0) into [sink]. Returns the bytes streamed. The sink
     * may throw to abort (pause); the data connection is then closed
     * and the exception propagates.
     */
    fun retrieve(path: String, offset: Long, sink: (ByteArray, Int) -> Unit): Long {
        val data = openDataConnection()
        dataSocket = data
        try {
            if (offset > 0) {
                val code = command("REST $offset")
                if (code != 350) {
                    throw IllegalStateException("FTP 服务器拒绝断点续传（$code）")
                }
            }
            val code = command("RETR $path")
            if (code != 150 && code != 125) {
                throw IllegalStateException(
                    if (code == 550) "FTP 服务器上没有这个文件（550）"
                    else "FTP 服务器拒绝下载（$code）"
                )
            }
            var total = 0L
            data.getInputStream().use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink(buf, n)
                    total += n
                }
            }
            // Transfer complete (226) — or aborted (426) if the sink
            // threw, in which case we never get here.
            val done = readReply()
            if (done != 226 && done != 250) {
                throw IllegalStateException("FTP 传输没正常结束（$done）")
            }
            return total
        } finally {
            runCatching { data.close() }
            dataSocket = null
        }
    }

    fun close() {
        runCatching { dataSocket?.close() }
        // v1.8 audit: after an aborted transfer the server may owe
        // us a 426 reply, or none at all — waiting for the QUIT
        // reply on the normal 30 s read timeout could park the
        // engine thread for half a minute on every pause. Give the
        // goodbye 2 s, then just drop the socket.
        runCatching {
            control?.soTimeout = 2_000
            command("QUIT")
        }
        runCatching { control?.close() }
        dataSocket = null
        control = null
    }

    // ---------- protocol plumbing ----------

    /** Open the PASV data connection. */
    private fun openDataConnection(): Socket {
        val (code, text) = commandFull("PASV")
        if (code != 227) throw IllegalStateException("FTP 服务器不支持被动模式（$code）")
        val (_, port) = parsePasv(text)
            ?: throw IllegalStateException("FTP 服务器的被动模式地址看不懂")
        // The address inside the 227 reply is the server's INTERNAL
        // address behind its NAT more often than not — real clients
        // ignore it and connect to the control connection's host.
        val s = Socket()
        s.connect(InetSocketAddress(controlHost, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        return s
    }

    private fun command(cmd: String): Int = commandFull(cmd).first

    private fun commandFull(cmd: String): Pair<Int, String> {
        // v1.8 audit: paths and credentials come from a
        // percent-decoded URL — a CR/LF smuggled in there would
        // inject extra commands into the control channel.
        if (cmd.contains('\r') || cmd.contains('\n')) {
            throw IllegalStateException("FTP 地址里有非法字符")
        }
        val w = writer ?: throw IllegalStateException("FTP 还没连接")
        w.write(cmd)
        w.write("\r\n")
        w.flush()
        val sb = StringBuilder()
        val code = readReply { line -> sb.append(line) }
        return code to sb.toString()
    }

    /**
     * Read one (possibly multi-line) reply and return its code.
     * Multi-line replies use "250-…" lines and end with "250 …".
     */
    private fun readReply(onLine: (String) -> Unit = {}): Int {
        val r = reader ?: throw IllegalStateException("FTP 还没连接")
        var code = -1
        while (true) {
            val line = r.readLine()
                ?: throw IllegalStateException("FTP 服务器断开了连接")
            onLine(line)
            // v1.8 audit: a bare "226" with no text after it is a
            // complete reply too — the old code fell through and
            // reported code -1 (a fake failure) for such servers.
            if (line.length == 3 && line.all { it.isDigit() }) {
                return line.toInt()
            }
            if (line.length >= 4) {
                val c = line.substring(0, 3).toIntOrNull()
                if (c != null) {
                    if (code < 0) code = c
                    if (line[3] == ' ') return code
                    if (line[3] != '-') return code
                } else {
                    return code
                }
            } else {
                return code
            }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val DEFAULT_PORT = 21

        /** Parse a 227 PASV reply: "(h1,h2,h3,h4,p1,p2)" → host to port. Pure. */
        fun parsePasv(text: String): Pair<String, Int>? {
            val start = text.indexOf('(')
            val end = text.indexOf(')', start + 1)
            if (start < 0 || end < 0) return null
            val parts = text.substring(start + 1, end).split(',')
                .map { it.trim().toIntOrNull() ?: return null }
            if (parts.size != 6) return null
            val host = parts.take(4).joinToString(".")
            val port = parts[4] * 256 + parts[5]
            if (port !in 1..65535) return null
            return host to port
        }

        /** A parsed ftp:// URL. */
        data class FtpUrl(
            val host: String,
            val port: Int,
            val user: String,
            val password: String,
            val path: String,
        )

        /**
         * Parse an ftp:// URL: userinfo (percent-encoded) becomes
         * the login, defaulting to anonymous; the path is
         * percent-decoded. Returns null for non-FTP or broken URLs.
         * Pure — unit-tested.
         */
        fun parseUrl(raw: String): FtpUrl? {
            val u = runCatching { URL(raw.trim()) }.getOrNull() ?: return null
            if (!u.protocol.equals("ftp", ignoreCase = true)) return null
            val host = u.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (u.port > 0) u.port else DEFAULT_PORT
            var user = "anonymous"
            var password = "anonymous@"
            val info = u.userInfo
            if (!info.isNullOrEmpty()) {
                val decoded = runCatching {
                    URLDecoder.decode(info, "UTF-8")
                }.getOrDefault(info)
                user = decoded.substringBefore(':').ifBlank { "anonymous" }
                password = decoded.substringAfter(':', "")
            }
            // Percent-decode the path, but a literal '+' in a path
            // is a plus, not a space (same rule as file names).
            val path = runCatching {
                URLDecoder.decode((u.path ?: "").replace("+", "%2B"), "UTF-8")
            }.getOrDefault(u.path ?: "")
            if (path.isBlank() || path == "/") return null
            return FtpUrl(host, port, user, password, path)
        }

        fun isFtpUrl(raw: String): Boolean =
            raw.trimStart().startsWith("ftp://", ignoreCase = true)
    }
}
