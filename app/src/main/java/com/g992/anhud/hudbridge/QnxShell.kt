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

/**
 * Root shell on the QNX side over telnet. [exec] appends `; echo <marker>$?` and waits
 * for the marker FOLLOWED BY A DIGIT, so the echoed command line is not mistaken for the end.
 */
class QnxShell private constructor(private val socket: Socket) : Closeable {

    data class Result(val output: String, val exitCode: Int) {
        val ok: Boolean
            get() = exitCode == 0
    }

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val buf = ByteArray(4096)
    private var iac = 0
    private var iacVerb = 0

    @JvmOverloads
    @Throws(IOException::class)
    fun exec(command: String, timeoutMs: Long = EXEC_TIMEOUT_MS): Result {
        val marker = "__DONE_" + System.nanoTime() + "__"
        val cmd = command.trim()
        if (cmd.endsWith("&")) {
            writeLine(cmd)
            writeLine("echo $marker\$?")
        } else {
            writeLine("$cmd; echo $marker\$?")
        }
        val raw = readUntilMarker(marker, timeoutMs)
        return parseExecOutput(raw, marker) ?: throw IOException("QNX не ответила на команду: $cmd")
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun writeLine(line: String) {
        output.write("$line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
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
        while (System.currentTimeMillis() < deadline) {
            if (read(sb) < 0) {
                break
            }
            if (seenAt < 0 && completedMarker(sb.toString(), marker) >= 0) {
                seenAt = System.currentTimeMillis()
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
        }
        if (n < 0) {
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
                    output.write(byteArrayOf(0xFF.toByte(), answer.toByte(), c.toByte()))
                    iac = 0
                }
                3 -> if (c == 0xFF) iac = 4
                else -> iac = if (c == 0xF0) 0 else 3
            }
        }
        output.flush()
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
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, TELNET_PORT), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
            } catch (e: IOException) {
                s.close()
                throw e
            }
            val sh = QnxShell(s)
            try {
                var banner = sh.readUntil(LOGIN_TIMEOUT_MS, "login:", "Password", "#")
                var credentialsSent = false
                if (banner.contains("login:")) {
                    sh.writeLine(user)
                    credentialsSent = true
                    banner = sh.readUntil(LOGIN_TIMEOUT_MS, "Password", "#")
                }
                if (banner.contains("Password")) {
                    sh.writeLine(password)
                    credentialsSent = true
                    banner = sh.readUntil(LOGIN_TIMEOUT_MS, "#", "incorrect", "login:")
                }
                if (!banner.trim().endsWith("#") && !banner.contains("\n#")) {
                    if (credentialsSent || banner.contains("incorrect")) {
                        throw QnxAuthException("вход в QNX не удался (проверьте пароль root)")
                    }
                    throw IOException("QNX не прислала приглашение telnet")
                }
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
