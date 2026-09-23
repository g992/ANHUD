package com.g992.anhud.hudbridge

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * gcp/1 subset used to talk to ghudbridgelited. Every message is a 24-byte
 * little-endian header (magic, type, flags, seq, length, crc(payload), crc(header[0..19]))
 * followed by the payload.
 */
object Gcp {
    const val MAGIC = 0x31504347
    const val HEADER_SIZE = 24

    const val T_HELLO = 0x0001
    const val T_HELLO_ACK = 0x0002
    const val T_PING = 0x0003
    const val T_PONG = 0x0004
    const val T_BYE = 0x0005
    const val T_ERROR = 0x0006
    const val T_FRAME = 0x0010
    const val T_FRAME_ACK = 0x0011

    const val FRAME_LAST = 0x0001
    const val ENC_RAW = 0
    const val ENC_RLE32 = 1
    const val MODE_FRAME = 1
    const val TRANSPARENCY_SOURCE_OVER = 3

    const val ERR_PROTO_VER = 1
    const val ERR_BAD_TYPE = 2
    const val ERR_BAD_LENGTH = 3
    const val ERR_CRC = 4
    const val ERR_BAD_MODE = 5
    const val ERR_BAD_GEOMETRY = 6
    const val ERR_BAD_RECT = 7
    const val ERR_DECODE = 8
    const val ERR_SCREEN = 9
    const val ERR_NO_SESSION = 10
    const val ERR_BUSY = 11
    const val ERR_OOM = 12
    const val ERR_TIMEOUT = 13

    const val WIDTH = 800
    const val HEIGHT = 480
    const val FPS = 30
    const val NAME_MAX_BYTES = 64
    const val CLIENT_ID_MAX_BYTES = 23

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    fun message(type: Int, flags: Int, seq: Int, payload: ByteArray): ByteArray {
        val b = le(HEADER_SIZE + payload.size)
        b.putInt(MAGIC).putShort(type.toShort()).putShort(flags.toShort())
            .putInt(seq).putInt(payload.size).putInt(crc(payload, 0, payload.size))
        b.putInt(crc(b.array(), 0, 20))
        b.put(payload)
        return b.array()
    }

    class Header(h: ByteArray) {
        val type: Int
        val flags: Int
        val length: Int
        val payloadCrc: Int

        init {
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            require(h.size >= HEADER_SIZE && b.getInt(0) == MAGIC && b.getInt(20) == crc(h, 0, 20)) {
                "not a gcp/1 header"
            }
            type = b.getShort(4).toInt() and 0xffff
            flags = b.getShort(6).toInt() and 0xffff
            length = b.getInt(12)
            payloadCrc = b.getInt(16)
        }
    }

    fun hello(name: String?, w: Int = WIDTH, h: Int = HEIGHT, fps: Int = FPS): ByteArray {
        val nameBytes = utf8Prefix(name, NAME_MAX_BYTES)
        val b = le(56 + 2 + nameBytes.size)
        b.putShort(1.toShort())
            .putShort(MODE_FRAME.toShort())
            .putInt(0).putInt(0)
            .putInt(w).putInt(h)
            .putInt(0)
            .putShort(TRANSPARENCY_SOURCE_OVER.toShort())
            .putShort(fps.toShort())
            .putInt(0)
        b.put(utf8Prefix(name, CLIENT_ID_MAX_BYTES))
        b.position(56)
        b.putShort(nameBytes.size.toShort()).put(nameBytes)
        return b.array()
    }

    /** FRAME payload for [rect] of a window [windowW] pixels wide; RLE32 when smaller than raw. */
    fun frame(rgba: ByteArray, windowW: Int, rect: Rect): ByteArray {
        val (x, y, w, h) = rect
        val raw = ByteArray(w * h * 4)
        for (row in 0 until h) {
            System.arraycopy(rgba, ((y + row) * windowW + x) * 4, raw, row * w * 4, w * 4)
        }
        val rle = rle32(raw)
        val useRle = rle.size < raw.size
        val data = if (useRle) rle else raw
        val b = le(24 + data.size)
        b.putShort((if (useRle) ENC_RLE32 else ENC_RAW).toShort()).putShort(0.toShort())
            .putInt(x).putInt(y).putInt(w).putInt(h).putInt(raw.size)
            .put(data)
        return b.array()
    }

    /** PackBits over 32-bit pixels: 0x00..0x7F = n+1 literals, 0x80..0xFF = one pixel repeated (n&0x7F)+1 times. */
    fun rle32(px: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(px.size / 4)
        val n = px.size / 4
        var i = 0
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && samePixel(px, i, i + run)) {
                run++
            }
            if (run >= 2) {
                out.write(0x80 or (run - 1))
                out.write(px, i * 4, 4)
                i += run
                continue
            }
            val start = i
            var count = 0
            while (i < n && count < 128 && !(i + 1 < n && samePixel(px, i, i + 1))) {
                count++
                i++
            }
            out.write(count - 1)
            out.write(px, start * 4, count * 4)
        }
        return out.toByteArray()
    }

    /** Bounding box of differing pixels, whole window when [before] is null, null when nothing changed. */
    fun changedRect(before: ByteArray?, now: ByteArray, w: Int, h: Int): Rect? {
        if (before == null) {
            return Rect(0, 0, w, h)
        }
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                if (before[o] != now[o] || before[o + 1] != now[o + 1] ||
                    before[o + 2] != now[o + 2] || before[o + 3] != now[o + 3]
                ) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        return if (x1 < 0) null else Rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** ERROR payload: u32 code, u32 errno, char[56] NUL-terminated message. */
    class Error(payload: ByteArray) {
        val code: Int
        val errno: Int
        val message: String

        init {
            val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            code = if (payload.size >= 4) b.getInt(0) else -1
            errno = if (payload.size >= 8) b.getInt(4) else 0
            var end = 8
            while (end < payload.size && payload[end].toInt() != 0) {
                end++
            }
            message = if (payload.size > 8) String(payload, 8, end - 8, Charsets.UTF_8) else ""
        }

        /** For ERR_BUSY: the name of the app holding the HUD ("busy: <name>"). */
        val busyHolder: String
            get() = if (message.startsWith("busy: ")) message.substring(6) else message
    }

    fun crc(b: ByteArray, off: Int, len: Int): Int {
        val c = CRC32()
        c.update(b, off, len)
        return c.value.toInt()
    }

    /** UTF-8 bytes of [s], at most [max], never cutting a character in half. */
    fun utf8Prefix(s: String?, max: Int): ByteArray {
        val all = (s ?: "").toByteArray(Charsets.UTF_8)
        if (all.size <= max) {
            return all
        }
        var n = max
        while (n > 0 && (all[n].toInt() and 0xC0) == 0x80) {
            n--
        }
        return all.copyOf(n)
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun samePixel(px: ByteArray, a: Int, b: Int): Boolean {
        val oa = a * 4
        val ob = b * 4
        return px[oa] == px[ob] && px[oa + 1] == px[ob + 1] &&
            px[oa + 2] == px[ob + 2] && px[oa + 3] == px[ob + 3]
    }
}
