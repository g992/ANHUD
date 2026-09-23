package com.g992.anhud.hudbridge

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GcpTest {
    @Test
    fun helloCarriesTheNameAfterTheFixedFields() {
        val h = Gcp.hello("Навигатор")
        val name = "Навигатор".toByteArray(Charsets.UTF_8)
        assertEquals(56 + 2 + name.size, h.size)
        val b = le(h)
        assertEquals(1, b.getShort(0).toInt())
        assertEquals(Gcp.MODE_FRAME, b.getShort(2).toInt())
        assertEquals(800, b.getInt(12))
        assertEquals(480, b.getInt(16))
        assertEquals(Gcp.TRANSPARENCY_SOURCE_OVER, b.getShort(24).toInt())
        assertEquals(30, b.getShort(26).toInt())
        assertEquals(name.size, b.getShort(56).toInt())
        assertArrayEquals(name, h.copyOfRange(58, h.size))
        assertEquals(0, h[32 + 23].toInt())
    }

    @Test
    fun aLongNameIsCutOnACharacterBoundary() {
        val s = "Ж".repeat(40)
        val h = Gcp.hello(s)
        val n = le(h).getShort(56).toInt()
        assertTrue(n <= Gcp.NAME_MAX_BYTES)
        assertEquals(0, n % 2)
        assertEquals(s.substring(0, n / 2), String(h, 58, n, Charsets.UTF_8))
    }

    @Test
    fun messageHeaderIsValidAndParsesBack() {
        val m = Gcp.message(Gcp.T_PING, 0, 7, ByteArray(0))
        assertEquals(Gcp.HEADER_SIZE, m.size)
        val h = Gcp.Header(m.copyOf(Gcp.HEADER_SIZE))
        assertEquals(Gcp.T_PING, h.type)
        assertEquals(0, h.length)
        m[5] = (m[5].toInt() xor 1).toByte()
        try {
            Gcp.Header(m.copyOf(Gcp.HEADER_SIZE))
            fail("a corrupt header was accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rle32KnownVector() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(5, 6, 7, 8)
        val px = concat(a, a, a, b)
        val expected = concat(byteArrayOf(0x82.toByte()), a, byteArrayOf(0x00), b)
        assertArrayEquals(expected, Gcp.rle32(px))
    }

    @Test
    fun rle32RoundTripsRandomAndLongRuns() {
        val r = Random(42)
        val px = ByteArray(4 * 1000)
        for (i in 0 until 1000) {
            val v = if (i < 300) 0 else if (i < 600) r.nextInt() else i / 7
            ByteBuffer.wrap(px, i * 4, 4).putInt(v)
        }
        assertArrayEquals(px, decode(Gcp.rle32(px), px.size))
    }

    @Test
    fun frameUsesRleWhenSmallerAndCopiesTheRectangle() {
        val w = 10
        val h = 4
        val rgba = ByteArray(w * h * 4)
        val f = Gcp.frame(rgba, w, Gcp.Rect(2, 1, 5, 3))
        val b = le(f)
        assertEquals(Gcp.ENC_RLE32, b.getShort(0).toInt())
        assertEquals(2, b.getInt(4))
        assertEquals(1, b.getInt(8))
        assertEquals(5, b.getInt(12))
        assertEquals(3, b.getInt(16))
        assertEquals(5 * 3 * 4, b.getInt(20))
        assertArrayEquals(ByteArray(5 * 3 * 4), decode(f.copyOfRange(24, f.size), 60))
    }

    @Test
    fun frameFallsBackToRawForNoise() {
        val r = Random(7)
        val rgba = ByteArray(4 * 4 * 4).also { r.nextBytes(it) }
        val f = Gcp.frame(rgba, 4, Gcp.Rect(0, 0, 4, 4))
        assertEquals(Gcp.ENC_RAW, le(f).getShort(0).toInt())
        assertArrayEquals(rgba, f.copyOfRange(24, f.size))
    }

    @Test
    fun changedRectIsTheBoundingBoxOrNull() {
        val a = ByteArray(8 * 6 * 4)
        val b = a.clone()
        assertNull(Gcp.changedRect(a, b, 8, 6))
        assertEquals(Gcp.Rect(0, 0, 8, 6), Gcp.changedRect(null, b, 8, 6))
        b[(2 * 8 + 3) * 4] = 1
        b[(4 * 8 + 5) * 4 + 3] = 1
        assertEquals(Gcp.Rect(3, 2, 3, 3), Gcp.changedRect(a, b, 8, 6))
    }

    @Test
    fun busyErrorNamesTheHolder() {
        val p = ByteArray(64)
        le(p).putInt(0, Gcp.ERR_BUSY).putInt(4, 16)
        val msg = "busy: Навигатор".toByteArray(Charsets.UTF_8)
        System.arraycopy(msg, 0, p, 8, msg.size)
        val e = Gcp.Error(p)
        assertEquals(Gcp.ERR_BUSY, e.code)
        assertEquals(16, e.errno)
        assertEquals("Навигатор", e.busyHolder)
    }

    @Test
    fun pidinMatchesTheExactExecutableOnly() {
        val listing = "     pid Arguments\n" +
            "  100 /tmp/ghudbridgelited --port 49210\n" +
            "  200 grep ghudbridgelited\n"
        assertTrue(DaemonInstaller.isRunning(listing))
        assertFalse(
            DaemonInstaller.isRunning(
                "  200 grep ghudbridgelited\n" +
                    "  300 cat /tmp/ghudbridgelited.log\n  400 /tmp/ghudbridged --port 49200\n"
            )
        )
    }

    @Test
    fun buildIdIsReadFromVersionOutput() {
        assertEquals("ghbl-20260901", DaemonInstaller.buildOf("ghudbridgelited 1.0 build=ghbl-20260901 x"))
        assertNull(DaemonInstaller.buildOf("sh: not found"))
    }

    @Test
    fun onlyTheMarkerFollowedByTheExitCodeEndsACommand() {
        val m = "__DONE_1__"
        assertEquals(-1, QnxShell.completedMarker("# sleep 5; echo $m\$?\n", m))
        val done = "# sleep 5; echo $m\$?\n" + m + "0\n# "
        assertEquals(done.lastIndexOf(m), QnxShell.completedMarker(done, m))
    }

    @Test
    fun execOutputIsBetweenEchoAndMarker() {
        val m = "__DONE_2__"
        val raw = "# ls /tmp; echo $m\$?\nghudbridgelited\nfoo\n${m}1\n# "
        val r = QnxShell.parseExecOutput(raw, m)!!
        assertEquals("ghudbridgelited\nfoo", r.output)
        assertEquals(1, r.exitCode)
        assertNull(QnxShell.parseExecOutput("# ls; echo $m\$?\n", m))
    }

    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun decode(data: ByteArray, rawLen: Int): ByteArray {
        val out = ByteArrayOutputStream(rawLen)
        var i = 0
        while (i < data.size) {
            val tok = data[i++].toInt() and 0xff
            val n = (tok and 0x7f) + 1
            if (tok and 0x80 != 0) {
                repeat(n) { out.write(data, i, 4) }
                i += 4
            } else {
                out.write(data, i, n * 4)
                i += n * 4
            }
        }
        assertEquals(rawLen, out.size())
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it, 0, it.size) }
        return out.toByteArray()
    }
}
