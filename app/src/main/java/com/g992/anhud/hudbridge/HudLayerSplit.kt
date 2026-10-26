package com.g992.anhud.hudbridge

import kotlin.math.ceil
import kotlin.math.floor

/**
 * "Блоки поверх штатной отрисовки": the overlay is rendered once and its frame is split by the
 * selected blocks' container rectangles. Inside a rectangle the pixels go to the over-stock layer
 * ([Gcp.LAYER_OVER]) on opaque black, so the block's box hides the stock picture; everything else
 * goes to the usual layer under the stock HUD, with those rectangles cleared (no duplicates).
 *
 * Pixels are RGBA bytes, premultiplied as Android renders them: painting a pixel over opaque black
 * keeps its colour bytes and sets alpha to 255.
 */
object HudLayerSplit {

    /** [rects] clipped to a [w]x[h] frame, empty ones dropped. */
    fun clip(rects: List<Gcp.Rect>, w: Int, h: Int): List<Gcp.Rect> {
        return rects.mapNotNull { r ->
            val x0 = r.x.coerceIn(0, w)
            val y0 = r.y.coerceIn(0, h)
            val x1 = (r.x + r.w).coerceIn(0, w)
            val y1 = (r.y + r.h).coerceIn(0, h)
            if (x1 > x0 && y1 > y0) Gcp.Rect(x0, y0, x1 - x0, y1 - y0) else null
        }
    }

    /** Smallest pixel rectangle covering the float box [left]..[right] x [top]..[bottom], clipped to [w]x[h]. */
    fun pixelRect(left: Float, top: Float, right: Float, bottom: Float, w: Int, h: Int): Gcp.Rect? {
        if (!(right > left && bottom > top)) return null
        val x0 = floor(left).toInt().coerceIn(0, w)
        val y0 = floor(top).toInt().coerceIn(0, h)
        val x1 = ceil(right).toInt().coerceIn(0, w)
        val y1 = ceil(bottom).toInt().coerceIn(0, h)
        return if (x1 > x0 && y1 > y0) Gcp.Rect(x0, y0, x1 - x0, y1 - y0) else null
    }

    /** Bounding box of [rects], null for none. */
    fun bounds(rects: List<Gcp.Rect>): Gcp.Rect? {
        if (rects.isEmpty()) return null
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        for (r in rects) {
            if (r.x < x0) x0 = r.x
            if (r.y < y0) y0 = r.y
            if (r.x + r.w > x1) x1 = r.x + r.w
            if (r.y + r.h > y1) y1 = r.y + r.h
        }
        return Gcp.Rect(x0, y0, x1 - x0, y1 - y0)
    }

    fun union(a: Gcp.Rect?, b: Gcp.Rect?): Gcp.Rect? {
        if (a == null) return b
        if (b == null) return a
        return bounds(listOf(a, b))
    }

    /** [under] := [raw] with every rectangle of [rects] (clipped already) fully transparent. */
    fun fillUnder(raw: ByteArray, under: ByteArray, w: Int, rects: List<Gcp.Rect>) {
        System.arraycopy(raw, 0, under, 0, raw.size)
        for (r in rects) {
            clear(under, w, r)
        }
    }

    /**
     * [over] := transparent, except inside [rects] (clipped already), where it is [raw] painted over
     * opaque black. [over] must be transparent outside [oldRects] (what it held before), so only
     * those are cleared instead of the whole picture.
     */
    fun fillOver(raw: ByteArray, over: ByteArray, w: Int, oldRects: List<Gcp.Rect>, rects: List<Gcp.Rect>) {
        for (r in oldRects) {
            clear(over, w, r)
        }
        for (r in rects) {
            for (row in r.y until r.y + r.h) {
                val start = (row * w + r.x) * 4
                val end = start + r.w * 4
                System.arraycopy(raw, start, over, start, r.w * 4)
                var a = start + 3
                while (a < end) {
                    over[a] = OPAQUE
                    a += 4
                }
            }
        }
    }

    /**
     * Bounding box of the pixels that differ between [before] (null = all transparent, a fresh
     * daemon window) and [now], looked for only inside [area]. Null when nothing differs.
     */
    fun changedRectIn(before: ByteArray?, now: ByteArray, w: Int, area: Gcp.Rect): Gcp.Rect? {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (y in area.y until area.y + area.h) {
            for (x in area.x until area.x + area.w) {
                val o = (y * w + x) * 4
                val differs = if (before == null) {
                    now[o].toInt() != 0 || now[o + 1].toInt() != 0 || now[o + 2].toInt() != 0 || now[o + 3].toInt() != 0
                } else {
                    before[o] != now[o] || before[o + 1] != now[o + 1] ||
                        before[o + 2] != now[o + 2] || before[o + 3] != now[o + 3]
                }
                if (differs) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        return if (x1 < 0) null else Gcp.Rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun clear(px: ByteArray, w: Int, r: Gcp.Rect) {
        for (row in r.y until r.y + r.h) {
            val start = (row * w + r.x) * 4
            java.util.Arrays.fill(px, start, start + r.w * 4, 0)
        }
    }

    private const val OPAQUE = 0xFF.toByte()
}

/**
 * Per-session state of the two layers: what the daemon holds of each, so only damage is sent.
 * Not thread-safe; [HudBridgeFrameSink] uses it from its own thread. Buffers are pooled (two per
 * layer), so a frame costs one copy of the picture only while the over layer is in use.
 */
class HudLayerSplitter(private val w: Int, private val h: Int) {

    /** What to send for one frame; hand it to [commit] once it went out. */
    class Batch internal constructor(
        val parts: List<Gcp.FramePart>,
        /** Send LAYER_OFF after [parts]: the over layer is no longer wanted. */
        val dropOver: Boolean,
        internal val under: ByteArray,
        internal val over: ByteArray?,
        internal val overRects: List<Gcp.Rect>
    )

    private var previous: ByteArray? = null
    private var overPrev: ByteArray? = null
    private var overPrevRects: List<Gcp.Rect> = emptyList()
    private val underPool = arrayOfNulls<ByteArray>(2)
    private val overPool = arrayOfNulls<ByteArray>(2)
    private val overPoolRects = Array<List<Gcp.Rect>>(2) { emptyList() }

    /** The daemon's over-stock window holds something we sent (it exists). */
    var overActive = false
        private set

    /**
     * Splits [raw] (whole frame, RGBA) by [rects] (container rectangles of the selected blocks,
     * null = no over layer at all). Null when nothing changed on either layer.
     */
    fun split(raw: ByteArray, rects: List<Gcp.Rect>?): Batch? {
        val parts = ArrayList<Gcp.FramePart>(2)
        if (rects == null) {
            val rect = Gcp.changedRect(previous, raw, w, h)
            if (!overActive) {
                overPrev = null
                overPrevRects = emptyList()
            }
            if (rect == null && !overActive) return null
            rect?.let { parts += Gcp.FramePart(Gcp.LAYER_UNDER, raw, it) }
            return Batch(parts, overActive, raw, null, emptyList())
        }
        val clipped = HudLayerSplit.clip(rects, w, h)
        val under = spare(underPool, previous)
        HudLayerSplit.fillUnder(raw, under, w, clipped)
        Gcp.changedRect(previous, under, w, h)?.let { parts += Gcp.FramePart(Gcp.LAYER_UNDER, under, it) }

        val slot = if (overPrev != null && overPool[0] === overPrev) 1 else 0
        val over = overPool[slot] ?: ByteArray(w * h * 4).also { overPool[slot] = it }
        HudLayerSplit.fillOver(raw, over, w, overPoolRects[slot], clipped)
        overPoolRects[slot] = clipped
        val area = HudLayerSplit.union(HudLayerSplit.bounds(clipped), HudLayerSplit.bounds(overPrevRects))
        area?.let { HudLayerSplit.changedRectIn(overPrev, over, w, it) }
            ?.let { parts += Gcp.FramePart(Gcp.LAYER_OVER, over, it) }
        if (parts.isEmpty()) return null
        return Batch(parts, false, under, over, clipped)
    }

    /** [batch] reached the daemon. */
    fun commit(batch: Batch) {
        previous = batch.under
        if (batch.over != null) {
            overPrev = batch.over
            overPrevRects = batch.overRects
            if (batch.parts.any { it.layer == Gcp.LAYER_OVER }) overActive = true
        }
        if (batch.dropOver) {
            overPrev = null
            overPrevRects = emptyList()
            overActive = false
        }
    }

    /** A fresh daemon session: both layers start empty there, everything goes again. */
    fun reset() {
        previous = null
        overPrev = null
        overPrevRects = emptyList()
        overActive = false
    }

    private fun spare(pool: Array<ByteArray?>, inUse: ByteArray?): ByteArray {
        val slot = if (inUse != null && pool[0] === inUse) 1 else 0
        return pool[slot] ?: ByteArray(w * h * 4).also { pool[slot] = it }
    }
}
