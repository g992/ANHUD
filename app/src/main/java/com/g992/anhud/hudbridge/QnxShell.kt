package com.g992.anhud.hudbridge

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Login to the QNX telnet was refused (wrong user or password). */
class QnxAuthException(message: String) : IOException(message)

/** The telnet connection itself dropped (as opposed to a command that never answered). */
class QnxConnectionLostException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The shell calls the daemon installer needs; lets its transfer be tested without a socket. */
interface QnxSession {
    fun exec(command: String, timeoutMs: Long = QnxShell.EXEC_TIMEOUT_MS): QnxShell.Result
    fun execBatch(lines: List<String>, timeoutMs: Long = QnxShell.EXEC_TIMEOUT_MS): QnxShell.Result
    fun interrupt()
    fun reconnect()
}

/**
 * Root shell on the QNX side over telnet. [exec] appends `; echo <marker>$?` and waits
 * for the marker FOLLOWED BY A DIGIT, so the echoed command line is not mistaken for the end.
 */
class QnxShell private constructor(
    private val host: String,
    private val user: String,
    private val password: String
) : QnxSession, Closeable {

    data class Result(val output: String, val exitCode: Int) {
        val ok: Boolean
            get() = exitCode == 0
    }

    private var socket = Socket()
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val buf = ByteArray(4096)
    private var iac = 0
    private var iacVerb = 0
    private var eof = false

    @Throws(IOException::class)
    override fun exec(command: String, timeoutMs: Long): Result {
        val marker = "__DONE_" + System.nanoTime() + "__"
        val cmd = command.trim()
        if (cmd.endsWith("&")) {
            writeLine(cmd)
            writeLine("echo $marker\$?")
        } else {
            writeLine("$cmd; echo $marker\$?")
        }
        val raw = readUntilMarker(marker, timeoutMs)
        return parseExecOutput(raw, marker) ?: throw noAnswer("QNX не ответила на команду: $cmd")
    }

    /**
     * Sends every line of [lines] as its own command without waiting in between, then one
     * marker for the lot. Only the exit code of the last line is reported.
     */
    @Throws(IOException::class)
    override fun execBatch(lines: List<String>, timeoutMs: Long): Result {
        val marker = "__DONE_" + System.nanoTime() + "__"
        val sb = StringBuilder()
        for (line in lines) {
            sb.append(line.trim()).append("\r\n")
        }
        sb.append("echo ").append(marker).append("\$?\r\n")
        write(sb.toString().toByteArray(Charsets.UTF_8))
        val raw = readUntilMarker(marker, timeoutMs)
        return parseExecOutput(raw, marker) ?: throw noAnswer("QNX не ответила на пакет из ${lines.size} команд")
    }

    /** Ctrl-C to the shell, dropping a half-typed command (e.g. one with an unclosed quote). */
    @Throws(IOException::class)
    override fun interrupt() {
        write(byteArrayOf(3))
        writeLine("")
        exec("true")
    }

    /** Drops the current connection and logs in again with the same credentials. */
    @Throws(IOException::class)
    override fun reconnect() {
        close()
        connectAndLogin()
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun writeLine(line: String) {
        write("$line\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun write(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw QnxConnectionLostException("связь с QNX оборвалась: ${e.message}", e)
        }
    }

    /** A missing marker means a lost connection when the socket hit EOF, else a hung command. */
    private fun noAnswer(message: String): IOException {
        return if (eof) QnxConnectionLostException("связь с QNX оборвалась") else IOException(message)
    }

    private fun connectAndLogin() {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, TELNET_PORT), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
            iac = 0
            eof = false
        } catch (e: IOException) {
            s.close()
            throw e
        }
        var banner = readUntil(LOGIN_TIMEOUT_MS, "login:", "Password", "#")
        var credentialsSent = false
        if (banner.contains("login:")) {
            writeLine(user)
            credentialsSent = true
            banner = readUntil(LOGIN_TIMEOUT_MS, "Password", "#")
        }
        if (banner.contains("Password")) {
            writeLine(password)
            credentialsSent = true
            banner = readUntil(LOGIN_TIMEOUT_MS, "#", "incorrect", "login:")
        }
        if (!banner.trim().endsWith("#") && !banner.contains("\n#")) {
            if (credentialsSent || banner.contains("incorrect")) {
                throw QnxAuthException("вход в QNX не удался (проверьте пароль root)")
            }
            throw IOException("QNX не прислала приглашение telnet")
        }
    }

    private fun readUntil(timeoutMs: Long, vararg targets: String): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (read(sb) < 0) {
                break
            }
            if (targets.any { sb.indexOf(it) >= 0 }) {
                return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun readUntilMarker(marker: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seenAt = -1L
        val sb = StringBuilder()
        // Only the new tail is searched: base64 transfers are megabytes long.
        var searchFrom = 0
        while (System.currentTimeMillis() < deadline) {
            if (read(sb) < 0) {
                break
            }
            if (seenAt < 0) {
                val from = searchFrom
                searchFrom = (sb.length - marker.length - 1).coerceAtLeast(0)
                if (completedMarker(sb.substring(from), marker) >= 0) {
                    seenAt = System.currentTimeMillis()
                }
            }
            if (seenAt >= 0 && System.currentTimeMillis() - seenAt > QUIET_MS) {
                break
            }
        }
        return sb.toString()
    }

    /** One short read: text to [sb] (CR and NUL dropped), telnet options refused. -1 at EOF. */
    private fun read(sb: StringBuilder): Int {
        socket.soTimeout = POLL_MS
        val n = try {
            input.read(buf)
        } catch (_: SocketTimeoutException) {
            return 0
        } catch (e: IOException) {
            eof = true
            throw QnxConnectionLostException("связь с QNX оборвалась: ${e.message}", e)
        }
        if (n < 0) {
            eof = true
            return -1
        }
        val data = ByteArrayOutputStream()
        for (i in 0 until n) {
            val c = buf[i].toInt() and 0xff
            when (iac) {
                0 -> if (c == 0xFF) {
                    iac = 1
                } else if (c != '\r'.code && c != 0) {
                    data.write(c)
                }
                1 -> when {
                    c == 0xFA -> iac = 3
                    c in 0xFB..0xFE -> {
                        iacVerb = c
                        iac = 2
                    }
                    else -> iac = 0
                }
                2 -> {
                    val answer = if (iacVerb == 0xFD || iacVerb == 0xFE) 0xFC else 0xFE
                    write(byteArrayOf(0xFF.toByte(), answer.toByte(), c.toByte()))
                    iac = 0
                }
                3 -> if (c == 0xFF) iac = 4
                else -> iac = if (c == 0xF0) 0 else 3
            }
        }
        val text = String(data.toByteArray(), Charsets.UTF_8)
        sb.append(text)
        return text.length
    }

    companion object {
        const val TELNET_PORT = 23
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val LOGIN_TIMEOUT_MS = 8_000L
        const val EXEC_TIMEOUT_MS = 20_000L
        private const val QUIET_MS = 300L
        private const val POLL_MS = 50

        /** Connects and logs in; [QnxAuthException] on bad credentials, other IOExceptions on network failures. */
        @Throws(IOException::class)
        fun open(host: String, user: String, password: String): QnxShell {
            val sh = QnxShell(host, user, password)
            try {
                sh.connectAndLogin()
                return sh
            } catch (e: Throwable) {
                sh.close()
                throw e
            }
        }

        /** Index of [marker] followed by a digit (the real echo, not the typed line), or -1. */
        internal fun completedMarker(text: String, marker: String): Int {
            var from = 0
            while (true) {
                val i = text.indexOf(marker, from)
                if (i < 0) {
                    return -1
                }
                val end = i + marker.length
                if (end < text.length && text[end].isDigit()) {
                    return i
                }
                from = i + 1
            }
        }

        /** Output between the echoed command line and the marker, plus the exit code; null if incomplete. */
        internal fun parseExecOutput(raw: String, marker: String): Result? {
            val at = completedMarker(raw, marker)
            if (at < 0) {
                return null
            }
            val end = at + marker.length
            var digits = end
            while (digits < raw.length && raw[digits].isDigit()) {
                digits++
            }
            val exitCode = raw.substring(end, digits).toInt()
            var body = raw.substring(0, at)
            val echo = body.lastIndexOf("$marker\$?")
            if (echo >= 0) {
                val nl = body.indexOf('\n', echo)
                body = if (nl >= 0) body.substring(nl + 1) else ""
            }
            return Result(body.trim(), exitCode)
        }
    }
}
