package com.g992.anhud.hudbridge

import java.io.IOException
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DaemonInstallerTelnetTest {

    private val path = DaemonInstaller.QNX_PATH
    private val daemon = ByteArray(20_000).also { Random(11).nextBytes(it) }

    @Test
    fun copiesIntactOverAHealthyShell() {
        val qnx = FakeQnx()
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
    }

    @Test
    fun lineLostByThePtyIsResent() {
        val qnx = FakeQnx()
        qnx.fault = { call -> if (call.batch && call.n > 3 && call.n % 3 == 1) Fault.LOSE_LINE else Fault.NONE }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
    }

    @Test
    fun corruptedOctalDigitIsCaughtByCksum() {
        val qnx = FakeQnx()
        qnx.fault = { call -> if (call.batch && call.n == 9) Fault.LOSE_DIGIT else Fault.NONE }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
    }

    @Test
    fun resumesAfterADropBeforeTheAppend() {
        val qnx = FakeQnx()
        qnx.fault = { call -> if (call.text.contains("&& cat") && call.n == 20) Fault.DROP_BEFORE else Fault.NONE }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
        assertEquals(1, qnx.reconnects)
    }

    @Test
    fun appendWhoseAnswerWasLostIsNotRepeated() {
        val qnx = FakeQnx()
        qnx.fault = { call -> if (call.text.contains("&& cat") && call.n == 20) Fault.DROP_AFTER else Fault.NONE }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
    }

    @Test
    fun dropDuringTheResyncIsSurvived() {
        val qnx = FakeQnx()
        var dropped = 0
        qnx.fault = { call ->
            when {
                call.text.contains("&& cat") && call.n == 20 -> Fault.DROP_AFTER
                call.text == "wc -c < $path" && dropped++ == 0 -> Fault.DROP_BEFORE
                else -> Fault.NONE
            }
        }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
        assertEquals(2, qnx.reconnects)
    }

    @Test
    fun restartsWhenQnxLostThePartialFile() {
        val qnx = FakeQnx()
        qnx.fault = { call -> if (call.batch && call.n == 15) Fault.DROP_AND_WIPE else Fault.NONE }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
    }

    @Test
    fun retriesTheLoginUntilQnxIsBack() {
        val qnx = FakeQnx()
        qnx.failedLogins = 2
        qnx.fault = { call -> if (call.batch && call.n == 15) Fault.DROP_BEFORE else Fault.NONE }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
        assertEquals(3, qnx.reconnects)
    }

    @Test
    fun worksBySizeWithoutCksum() {
        val qnx = FakeQnx()
        qnx.hasCksum = false
        qnx.fault = { call -> if (call.batch && call.n % 4 == 2) Fault.LOSE_LINE else Fault.NONE }
        DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
        assertArrayEquals(daemon, qnx.files[path])
    }

    @Test
    fun givesUpWhenTheLinkKeepsDropping() {
        val qnx = FakeQnx()
        qnx.fault = { call -> if (call.batch && call.n > 5) Fault.DROP_BEFORE else Fault.NONE }
        try {
            DaemonInstaller.copyViaTelnet(qnx, daemon, reconnectDelayMs = 0)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("рвалась"))
        }
    }

    private enum class Fault { NONE, LOSE_LINE, LOSE_DIGIT, DROP_BEFORE, DROP_AFTER, DROP_AND_WIPE }

    private class Call(val n: Int, val batch: Boolean, val text: String)

    /** Just enough of the QNX ksh for the installer's commands, with injectable faults. */
    private class FakeQnx : QnxSession {
        val files = HashMap<String, ByteArray>()
        var fault: (Call) -> Fault = { Fault.NONE }
        var hasCksum = true
        var failedLogins = 0
        var reconnects = 0
        private var calls = 0
        private var connected = true

        override fun exec(command: String, timeoutMs: Long): QnxShell.Result {
            val f = before(Call(++calls, false, command))
            val r = run(command)
            after(f)
            return r
        }

        override fun execBatch(lines: List<String>, timeoutMs: Long): QnxShell.Result {
            val f = before(Call(++calls, true, lines.joinToString("\n")))
            var r = QnxShell.Result("", 0)
            for ((k, line) in lines.withIndex()) {
                when {
                    f == Fault.LOSE_LINE && k == lines.size / 2 -> continue
                    f == Fault.LOSE_DIGIT && k == lines.size / 2 && line.contains("\\0") -> {
                        val at = line.indexOf("\\0") + 2
                        r = run(line.removeRange(at, at + 1))
                    }
                    else -> r = run(line)
                }
            }
            after(f)
            return r
        }

        override fun interrupt() {
            exec("true")
        }

        override fun reconnect() {
            reconnects++
            if (failedLogins > 0) {
                failedLogins--
                throw IOException("connection refused")
            }
            connected = true
        }

        private fun before(call: Call): Fault {
            if (!connected) {
                throw QnxConnectionLostException("dead socket")
            }
            val f = fault(call)
            if (f == Fault.DROP_BEFORE) {
                connected = false
                throw QnxConnectionLostException("dropped")
            }
            return f
        }

        private fun after(f: Fault) {
            if (f == Fault.DROP_AFTER || f == Fault.DROP_AND_WIPE) {
                connected = false
                if (f == Fault.DROP_AND_WIPE) {
                    files.clear()
                }
                throw QnxConnectionLostException("dropped")
            }
        }

        private fun run(line: String): QnxShell.Result {
            Regex("""^: > (\S+)$""").find(line)?.let {
                files[it.groupValues[1]] = ByteArray(0)
                return ok()
            }
            Regex("""^print -n '([^']*)' >> (\S+)$""").find(line)?.let {
                append(it.groupValues[2], unescape(it.groupValues[1]))
                return ok()
            }
            Regex("""^wc -c < (\S+)$""").find(line)?.let {
                val f = files[it.groupValues[1]] ?: return QnxShell.Result("cannot open", 1)
                return ok("      ${f.size}")
            }
            Regex("""^cksum < (\S+); rm -f (\S+)$""").find(line)?.let {
                val out = if (hasCksum) DaemonInstaller.cksumOf(files[it.groupValues[1]]!!).replace(" ", "\t") else "ksh: cksum: not found"
                files.remove(it.groupValues[2])
                return ok(out)
            }
            Regex("""^set -- \$\(cksum < (\S+)\); \[ "\$1 \$2" = "(\d+ \d+)" ] && cat (\S+) >> (\S+)$""").find(line)?.let {
                val part = files[it.groupValues[1]] ?: return QnxShell.Result("", 1)
                if (DaemonInstaller.cksumOf(part) != it.groupValues[2]) return QnxShell.Result("", 1)
                append(it.groupValues[4], files[it.groupValues[3]]!!)
                return ok()
            }
            Regex("""^\[ \$\(wc -c < (\S+)\) -eq (\d+) ] && cat (\S+) >> (\S+)$""").find(line)?.let {
                val part = files[it.groupValues[1]] ?: return QnxShell.Result("", 1)
                if (part.size != it.groupValues[2].toInt()) return QnxShell.Result("", 1)
                append(it.groupValues[4], files[it.groupValues[3]]!!)
                return ok()
            }
            Regex("""^rm -f (.+)$""").find(line)?.let {
                it.groupValues[1].split(" ").forEach { name -> files.remove(name) }
                return ok()
            }
            if (line.startsWith("chmod ") || line == "true") {
                return ok()
            }
            throw AssertionError("unexpected command: $line")
        }

        private fun append(name: String, bytes: ByteArray) {
            val old = files[name] ?: ByteArray(0)
            files[name] = old + bytes
        }

        private fun unescape(body: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < body.length) {
                if (body[i] == '\\' && body.getOrNull(i + 1) == '0') {
                    // ksh: \0 followed by up to three octal digits
                    var j = i + 2
                    while (j < body.length && j < i + 5 && body[j] in '0'..'7') j++
                    out.write(if (j > i + 2) body.substring(i + 2, j).toInt(8) and 0xFF else 0)
                    i = j
                } else {
                    out.write(body[i].code)
                    i++
                }
            }
            return out.toByteArray()
        }

        private fun ok(output: String = "") = QnxShell.Result(output, 0)
    }
}
