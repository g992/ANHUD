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
    const val T_CAMCFG = 0x0050
    const val T_VEHSTATE = 0x0051
    const val T_CAMSNAP = 0x0052
    const val T_CAMSNAP_DONE = 0x0053
    /** Over-stock layer (daemon 0.5.0, amendment D): removes the over window. */
    const val T_LAYER_OFF = 0x0060

    const val FRAME_LAST = 0x0001
    /** FRAME layer (u16 at payload offset 2): under the stock HUD / over it, below the cameras. */
    const val LAYER_UNDER = 0
    const val LAYER_OVER = 1
    /** HELLO_ACK caps: the daemon understands FRAME layer [LAYER_OVER] and [T_LAYER_OFF]. */
    const val CAP_LAYER_OVER = 0x0004
    const val LAYER_OFF_SIZE = 4
    private const val HELLO_ACK_CAPS_OFFSET = 26
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
    const val ERR_IO = 14

    const val WIDTH = 800
    const val HEIGHT = 480
    const val FPS = 30
    const val NAME_MAX_BYTES = 64
    const val CLIENT_ID_MAX_BYTES = 23

    const val CAMCFG_VERSION = 2
    const val CAMCFG_HEADER_SIZE = 16
    const val CAMCFG_VIEW_SIZE = 112
    const val CAMCFG_MAX_VIEWS = 4
    /** CAMCFG header flags. */
    const val CAMCFG_FLAG_ENABLED = 0x1
    const val CAMCFG_FLAG_HAZARD_BOTH = 0x2
    /**
     * View flags. NO_CORRECTION: raw fisheye frame centre-cropped into the rect, no undistortion.
     * VIGNETTE: darkened edges, the map vignette's curve (CameraViewRenderer.vignetteAlpha).
     */
    const val CAM_FLAG_ENABLED = 0x1
    const val CAM_FLAG_MIRROR = 0x2
    const val CAM_FLAG_NO_CORRECTION = 0x4
    const val CAM_FLAG_VIGNETTE = 0x8
    const val CAM_ROLE_REAR = 1
    const val CAM_ROLE_LEFT = 2
    const val CAM_ROLE_RIGHT = 3
    const val CAM_ROLE_FRONT = 4
    /** QCarCam input ids on this car (FX11 J1). */
    const val CAM_INPUT_LEFT = 0
    const val CAM_INPUT_RIGHT = 1
    const val CAM_INPUT_FRONT = 2
    const val CAM_INPUT_REAR = 3
    const val CAM_MIN_SIZE = 16
    const val CAM_Z_MIN = 5
    const val CAM_Z_MAX = 10
    const val VEHSTATE_SIZE = 12
    /** VEHSTATE flags. Turn bits mean something only together with [VEH_TURN_VALID]. */
    const val VEH_TURN_LEFT = 0x1
    const val VEH_TURN_RIGHT = 0x2
    const val VEH_HAZARD = 0x4
    const val VEH_TURN_VALID = 0x8

    /**
     * CAMSNAP (amendment C): the daemon writes one live frame of an input to [camSnapPath] as NV12
     * 1280x800 and answers CAMSNAP_DONE. Older daemons end the session on it, hence the version gate.
     */
    const val CAMSNAP_SIZE = 8
    const val CAMSNAP_DONE_SIZE = 24
    const val CAMSNAP_FLAG_SHARED = 0x1
    private val CAMSNAP_MIN_VERSION = listOf(0, 4, 0)
    private const val SERVER_ID_OFFSET = 48
    private const val SERVER_ID_MAX_BYTES = 24

    /** Android VHAL VehicleGear values as carried in VEHSTATE. */
    const val GEAR_UNKNOWN = 0
    const val GEAR_NEUTRAL = 1
    const val GEAR_REVERSE = 2
    const val GEAR_PARK = 4
    const val GEAR_DRIVE = 8

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /** One CAMCFG v2 view record: where and how ghudbridgelited draws one camera on the HUD. */
    data class CameraView(
        val role: Int,
        val enabled: Boolean,
        val mirror: Boolean,
        val inputId: Int,
        val rect: Rect,
        val z: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val k1: Float,
        val k2: Float,
        val k3: Float,
        val k4: Float,
        val outHfovDeg: Float,
        val panX: Float,
        val panY: Float,
        val yawDeg: Float = 0f,
        val pitchDeg: Float = 0f,
        val rollDeg: Float = 0f,
        val maxSpeedKmh: Int,
        val holdMs: Int = 0,
        val noCorrection: Boolean = false,
        val vignette: Boolean = false
    )

    /**
     * CAMCFG v2: master switch plus up to [CAMCFG_MAX_VIEWS] views; the daemon picks the view(s)
     * by gear/turn signals. [hazardBoth]: hazard lights show LEFT and RIGHT together.
     */
    data class CameraConfig(
        val enabled: Boolean,
        val views: List<CameraView>,
        val hazardBoth: Boolean = false
    )

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

    /** One FRAME of a batch: [rect] of the full-window RGBA picture [rgba] of [layer]. */
    class FramePart(val layer: Int, val rgba: ByteArray, val rect: Rect)

    /**
     * FRAME payload for [rect] of a window [windowW] pixels wide; RLE32 when smaller than raw.
     * [layer] other than [LAYER_UNDER] only for a daemon with [CAP_LAYER_OVER].
     */
    fun frame(rgba: ByteArray, windowW: Int, rect: Rect, layer: Int = LAYER_UNDER): ByteArray {
        val (x, y, w, h) = rect
        val raw = ByteArray(w * h * 4)
        for (row in 0 until h) {
            System.arraycopy(rgba, ((y + row) * windowW + x) * 4, raw, row * w * 4, w * 4)
        }
        val rle = rle32(raw)
        val useRle = rle.size < raw.size
        val data = if (useRle) rle else raw
        val b = le(24 + data.size)
        b.putShort((if (useRle) ENC_RLE32 else ENC_RAW).toShort()).putShort(layer.toShort())
            .putInt(x).putInt(y).putInt(w).putInt(h).putInt(raw.size)
            .put(data)
        return b.array()
    }

    /**
     * CAMCFG v2 payload: 16-byte header + [CAMCFG_VIEW_SIZE] bytes per view. Every window is
     * clamped into the 800x480 HUD (16 px minimum) and z into 5..10, so the daemon never has to
     * answer BAD_GEOMETRY.
     */
    fun camCfg(c: CameraConfig): ByteArray {
        require(c.views.size in 1..CAMCFG_MAX_VIEWS) { "CAMCFG needs 1..$CAMCFG_MAX_VIEWS views" }
        val b = le(CAMCFG_HEADER_SIZE + c.views.size * CAMCFG_VIEW_SIZE)
        var cfgFlags = 0
        if (c.enabled) cfgFlags = cfgFlags or CAMCFG_FLAG_ENABLED
        if (c.hazardBoth) cfgFlags = cfgFlags or CAMCFG_FLAG_HAZARD_BOTH
        b.putInt(CAMCFG_VERSION).putInt(cfgFlags).putInt(c.views.size).putInt(CAMCFG_VIEW_SIZE)
        for (v in c.views) {
            val start = b.position()
            val w = v.rect.w.coerceIn(CAM_MIN_SIZE, WIDTH)
            val h = v.rect.h.coerceIn(CAM_MIN_SIZE, HEIGHT)
            val x = v.rect.x.coerceIn(0, WIDTH - w)
            val y = v.rect.y.coerceIn(0, HEIGHT - h)
            var flags = 0
            if (v.enabled) flags = flags or CAM_FLAG_ENABLED
            if (v.mirror) flags = flags or CAM_FLAG_MIRROR
            if (v.noCorrection) flags = flags or CAM_FLAG_NO_CORRECTION
            if (v.vignette) flags = flags or CAM_FLAG_VIGNETTE
            b.putInt(v.role).putInt(flags).putInt(v.inputId)
                .putInt(x).putInt(y).putInt(w).putInt(h)
                .putInt(v.z.coerceIn(CAM_Z_MIN, CAM_Z_MAX))
                .putFloat(v.fx).putFloat(v.fy).putFloat(v.cx).putFloat(v.cy)
                .putFloat(v.k1).putFloat(v.k2).putFloat(v.k3).putFloat(v.k4)
                .putFloat(v.outHfovDeg).putFloat(v.panX).putFloat(v.panY)
                .putFloat(v.yawDeg).putFloat(v.pitchDeg).putFloat(v.rollDeg)
                .putInt(v.maxSpeedKmh.coerceAtLeast(0))
                .putInt(v.holdMs.coerceAtLeast(0))
            // 16 reserved bytes stay zero.
            b.position(start + CAMCFG_VIEW_SIZE)
        }
        return b.array()
    }

    /** VEHSTATE payload (12 bytes): VHAL gear, speed in km/h (negative = unknown), VEH_* flags. */
    fun vehState(gear: Int, speedKmh: Float, flags: Int = 0): ByteArray {
        val speed = if (speedKmh.isFinite() && speedKmh >= 0f) speedKmh else -1f
        return le(VEHSTATE_SIZE).putInt(gear).putFloat(speed).putInt(flags).array()
    }

    /** CAMSNAP payload (8 bytes): QCarCam input, request id echoed in CAMSNAP_DONE. */
    fun camSnap(inputId: Int, requestId: Int): ByteArray =
        le(CAMSNAP_SIZE).putInt(inputId).putInt(requestId).array()

    /** Where the daemon puts CAMSNAP's frame of [inputId]. */
    fun camSnapPath(inputId: Int): String = "/tmp/ghbl_snap_$inputId.yuv"

    /** CAMSNAP_DONE: [status] is OK or an ERR_* code; [bytes] the file size when OK. */
    data class CamSnapDone(
        val inputId: Int,
        val requestId: Int,
        val status: Int,
        val bytes: Int,
        val flags: Int,
        val elapsedMs: Int
    ) {
        val ok: Boolean get() = status == 0
        val shared: Boolean get() = (flags and CAMSNAP_FLAG_SHARED) != 0

        companion object {
            /** Null for a payload shorter than [CAMSNAP_DONE_SIZE]; a longer one's tail is ignored. */
            fun parse(payload: ByteArray): CamSnapDone? {
                if (payload.size < CAMSNAP_DONE_SIZE) return null
                val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                return CamSnapDone(b.getInt(0), b.getInt(4), b.getInt(8), b.getInt(12), b.getInt(16), b.getInt(20))
            }
        }
    }

    /** LAYER_OFF payload (4 bytes): the layer to remove, always [LAYER_OVER]. */
    fun layerOff(layer: Int = LAYER_OVER): ByteArray = le(LAYER_OFF_SIZE).putInt(layer).array()

    /** HELLO_ACK's caps field, 0 when the payload is too short. */
    fun helloAckCaps(payload: ByteArray): Int {
        if (payload.size < HELLO_ACK_CAPS_OFFSET + 2) return 0
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getShort(HELLO_ACK_CAPS_OFFSET).toInt() and 0xffff
    }

    /** HELLO_ACK's server_id ("ghudbridgelited/0.4.0"), empty when the payload is too short. */
    fun helloAckServerId(payload: ByteArray): String {
        if (payload.size < SERVER_ID_OFFSET + 1) return ""
        val endMax = minOf(payload.size, SERVER_ID_OFFSET + SERVER_ID_MAX_BYTES)
        var end = SERVER_ID_OFFSET
        while (end < endMax && payload[end].toInt() != 0) {
            end++
        }
        return String(payload, SERVER_ID_OFFSET, end - SERVER_ID_OFFSET, Charsets.UTF_8)
    }

    /** True for ghudbridgelited 0.4.0 and later (the first to answer CAMSNAP). */
    fun supportsCamSnap(serverId: String?): Boolean {
        val id = serverId ?: return false
        if (!id.startsWith(DaemonInstaller.NAME + "/")) return false
        val parts = id.substringAfter('/').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: return false }
        for (i in CAMSNAP_MIN_VERSION.indices) {
            val have = parts.getOrElse(i) { 0 }
            if (have != CAMSNAP_MIN_VERSION[i]) return have > CAMSNAP_MIN_VERSION[i]
        }
        return true
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
