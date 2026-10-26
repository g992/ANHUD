package com.g992.anhud.hudbridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayerSplitTest {
    private val w = 8
    private val h = 4

    // --- protocol (amendment D) ---------------------------------------------------------------

    @Test
    fun frameCarriesItsLayerInTheFormerlyReservedField() {
        val rgba = ByteArray(Gcp.WIDTH * Gcp.HEIGHT * 4)
        val under = le(Gcp.frame(rgba, Gcp.WIDTH, Gcp.Rect(1, 2, 3, 4)))
        val over = le(Gcp.frame(rgba, Gcp.WIDTH, Gcp.Rect(1, 2, 3, 4), Gcp.LAYER_OVER))
        assertEquals(0, under.getShort(2).toInt())
        assertEquals(1, over.getShort(2).toInt())
        assertEquals(1, over.getInt(4))
        assertEquals(2, over.getInt(8))
        assertEquals(3, over.getInt(12))
        assertEquals(4, over.getInt(16))
        assertEquals(3 * 4 * 4, over.getInt(20))
    }

    @Test
    fun layerOffAndCapsMatchTheDaemon() {
        assertEquals(0x0060, Gcp.T_LAYER_OFF)
        val p = Gcp.layerOff()
        assertEquals(Gcp.LAYER_OFF_SIZE, p.size)
        assertEquals(Gcp.LAYER_OVER, le(p).getInt(0))

        val ack = ByteArray(72)
        ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN).putShort(26, 0x0007)
        assertEquals(0x0007, Gcp.helloAckCaps(ack))
        assertTrue((Gcp.helloAckCaps(ack) and Gcp.CAP_LAYER_OVER) != 0)
        ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN).putShort(26, 0x0003)
        assertFalse((Gcp.helloAckCaps(ack) and Gcp.CAP_LAYER_OVER) != 0)
        assertEquals(0, Gcp.helloAckCaps(ByteArray(20)))
    }

    // --- pure helpers -------------------------------------------------------------------------

    @Test
    fun rectsAreClippedAndPixelRectsCoverFractions() {
        assertEquals(
            listOf(Gcp.Rect(0, 0, 3, 2), Gcp.Rect(6, 3, 2, 1)),
            HudLayerSplit.clip(listOf(Gcp.Rect(-2, -1, 5, 3), Gcp.Rect(6, 3, 9, 9), Gcp.Rect(9, 0, 2, 2)), w, h)
        )
        assertEquals(Gcp.Rect(1, 0, 3, 3), HudLayerSplit.pixelRect(1.5f, -2f, 3.2f, 2.01f, w, h))
        assertNull(HudLayerSplit.pixelRect(3f, 1f, 3f, 2f, w, h))
        assertNull(HudLayerSplit.pixelRect(9f, 1f, 12f, 2f, w, h))
        assertEquals(Gcp.Rect(1, 1, 6, 3), HudLayerSplit.bounds(listOf(Gcp.Rect(1, 2, 2, 2), Gcp.Rect(5, 1, 2, 1))))
        assertNull(HudLayerSplit.bounds(emptyList()))
    }

    @Test
    fun splitPutsTheBoxOnOpaqueBlackAboveAndClearsItBelow() {
        val raw = frame { x, y -> if (x in 2..3 && y == 1) px(200, 100, 50, 128) else px(10, 20, 30, 255) }
        val rects = listOf(Gcp.Rect(1, 1, 4, 2))
        val under = ByteArray(raw.size)
        val over = ByteArray(raw.size)
        HudLayerSplit.fillUnder(raw, under, w, rects)
        HudLayerSplit.fillOver(raw, over, w, emptyList(), rects)
        for (y in 0 until h) for (x in 0 until w) {
            val inside = x in 1..4 && y in 1..2
            if (inside) {
                assertArrayEquals(ByteArray(4), pixel(under, x, y))
                val src = pixel(raw, x, y)
                assertArrayEquals(byteArrayOf(src[0], src[1], src[2], 0xFF.toByte()), pixel(over, x, y))
            } else {
                assertArrayEquals(pixel(raw, x, y), pixel(under, x, y))
                assertArrayEquals(ByteArray(4), pixel(over, x, y))
            }
        }
        // A transparent pixel inside the box becomes opaque black: the stock picture is hidden there.
        val empty = ByteArray(raw.size)
        HudLayerSplit.fillOver(ByteArray(raw.size), empty, w, emptyList(), rects)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0xFF.toByte()), pixel(empty, 1, 1))
        // Re-filling clears the boxes the buffer held before.
        HudLayerSplit.fillOver(raw, empty, w, rects, listOf(Gcp.Rect(6, 0, 1, 1)))
        assertArrayEquals(ByteArray(4), pixel(empty, 1, 1))
        assertEquals(0xFF.toByte(), pixel(empty, 6, 0)[3])
    }

    @Test
    fun changedRectInLooksOnlyInsideTheArea() {
        val a = ByteArray(w * h * 4)
        val b = a.copyOf()
        put(b, 2, 1, px(1, 0, 0, 0))
        put(b, 7, 3, px(1, 0, 0, 0))
        assertEquals(Gcp.Rect(2, 1, 1, 1), HudLayerSplit.changedRectIn(a, b, w, Gcp.Rect(0, 0, 4, 4)))
        assertEquals(Gcp.Rect(2, 1, 6, 3), HudLayerSplit.changedRectIn(null, b, w, Gcp.Rect(0, 0, w, h)))
        assertNull(HudLayerSplit.changedRectIn(a, b, w, Gcp.Rect(3, 0, 2, 4)))
    }

    // --- the per-session splitter ---------------------------------------------------------------

    @Test
    fun withoutRectsTheWholeFrameGoesUnderAsBefore() {
        val s = HudLayerSplitter(w, h)
        val raw = frame { _, _ -> px(1, 2, 3, 255) }
        val first = s.split(raw, null)!!
        assertEquals(1, first.parts.size)
        assertEquals(Gcp.LAYER_UNDER, first.parts[0].layer)
        assertTrue(first.parts[0].rgba === raw)
        assertEquals(Gcp.Rect(0, 0, w, h), first.parts[0].rect)
        assertFalse(first.dropOver)
        s.commit(first)
        assertNull(s.split(raw.copyOf(), null))
        assertFalse(s.overActive)
    }

    @Test
    fun selectedBoxGoesOverWithoutDuplicateAndOnlyDamageIsSent() {
        val s = HudLayerSplitter(w, h)
        val box = listOf(Gcp.Rect(4, 0, 3, 2))
        val raw1 = frame { x, _ -> if (x == 5) px(9, 9, 9, 255) else 0 }
        val b1 = s.split(raw1, box)!!
        assertEquals(listOf(Gcp.LAYER_UNDER, Gcp.LAYER_OVER), b1.parts.map { it.layer })
        assertEquals(Gcp.Rect(0, 0, w, h), b1.parts[0].rect)          // fresh window: everything
        assertEquals(Gcp.Rect(4, 0, 3, 2), b1.parts[1].rect)          // black box incl. its edges
        val under1 = b1.parts[0].rgba
        val over1 = b1.parts[1].rgba
        assertArrayEquals(ByteArray(4), pixel(under1, 5, 0))           // not drawn twice
        assertArrayEquals(pixel(raw1, 5, 2), pixel(under1, 5, 2))      // below the box: unchanged
        assertArrayEquals(byteArrayOf(9, 9, 9, 0xFF.toByte()), pixel(over1, 5, 1))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0xFF.toByte()), pixel(over1, 4, 0))
        s.commit(b1)
        assertTrue(s.overActive)

        // Same picture again: nothing to send on either layer.
        assertNull(s.split(raw1.copyOf(), box))

        // Only the digit inside the box changes: one over-layer rect, nothing below.
        val raw2 = raw1.copyOf().also { put(it, 6, 1, px(7, 7, 7, 255)) }
        val b2 = s.split(raw2, box)!!
        assertEquals(1, b2.parts.size)
        assertEquals(Gcp.LAYER_OVER, b2.parts[0].layer)
        assertEquals(Gcp.Rect(6, 1, 1, 1), b2.parts[0].rect)
        assertFalse(b2.parts[0].rgba === over1)                       // the sent buffer stays intact
        s.commit(b2)

        // Something outside the box changes: under layer only.
        val raw3 = raw2.copyOf().also { put(it, 0, 3, px(1, 1, 1, 255)) }
        val b3 = s.split(raw3, box)!!
        assertEquals(listOf(Gcp.LAYER_UNDER), b3.parts.map { it.layer })
        assertEquals(Gcp.Rect(0, 3, 1, 1), b3.parts[0].rect)
        s.commit(b3)
    }

    @Test
    fun movingAndHidingTheBoxRepaintsBothPlaces() {
        val s = HudLayerSplitter(w, h)
        val raw = frame { _, _ -> 0 }
        s.commit(s.split(raw, listOf(Gcp.Rect(0, 0, 2, 2)))!!)
        val moved = s.split(raw, listOf(Gcp.Rect(5, 2, 2, 2)))!!
        assertEquals(listOf(Gcp.LAYER_OVER), moved.parts.map { it.layer })
        assertEquals(Gcp.Rect(0, 0, 7, 4), moved.parts[0].rect)      // old box cleared, new one drawn
        assertArrayEquals(ByteArray(4), pixel(moved.parts[0].rgba, 0, 0))
        s.commit(moved)
        // Block hidden (no rects shown, feature still on): the box goes, the window stays.
        val hidden = s.split(raw, emptyList())!!
        assertEquals(Gcp.Rect(5, 2, 2, 2), hidden.parts.single().rect)
        assertFalse(hidden.dropOver)
        s.commit(hidden)
        assertTrue(s.overActive)
    }

    @Test
    fun turningTheFeatureOffRestoresTheBlockBelowAndDropsTheLayer() {
        val s = HudLayerSplitter(w, h)
        val raw = frame { x, y -> if (x == 1 && y == 1) px(5, 5, 5, 255) else 0 }
        s.commit(s.split(raw, listOf(Gcp.Rect(0, 0, 3, 3)))!!)
        val off = s.split(raw, null)!!
        assertTrue(off.dropOver)
        assertEquals(listOf(Gcp.LAYER_UNDER), off.parts.map { it.layer })
        assertEquals(Gcp.Rect(1, 1, 1, 1), off.parts[0].rect)
        s.commit(off)
        assertFalse(s.overActive)
        assertNull(s.split(raw, null))
    }

    @Test
    fun nothingVisibleNeverCreatesTheOverWindow() {
        val s = HudLayerSplitter(w, h)
        val raw = frame { _, _ -> px(3, 3, 3, 255) }
        val b = s.split(raw, emptyList())!!
        assertEquals(listOf(Gcp.LAYER_UNDER), b.parts.map { it.layer })
        s.commit(b)
        assertFalse(s.overActive)
        val off = s.split(raw, null)
        assertNull(off)                                               // no LAYER_OFF for a window never made
    }

    @Test
    fun resetSendsBothLayersAgain() {
        val s = HudLayerSplitter(w, h)
        val raw = frame { x, _ -> if (x == 0) px(1, 1, 1, 255) else 0 }
        val box = listOf(Gcp.Rect(0, 0, 1, 1))
        s.commit(s.split(raw, box)!!)
        assertNull(s.split(raw, box))
        s.reset()
        val again = s.split(raw, box)
        assertNotNull(again)
        assertEquals(listOf(Gcp.LAYER_UNDER, Gcp.LAYER_OVER), again!!.parts.map { it.layer })
        assertEquals(Gcp.Rect(0, 0, w, h), again.parts[0].rect)
        assertEquals(Gcp.Rect(0, 0, 1, 1), again.parts[1].rect)
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun px(r: Int, g: Int, b: Int, a: Int): Int = r or (g shl 8) or (b shl 16) or (a shl 24)

    private fun frame(f: (Int, Int) -> Int): ByteArray {
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) put(out, x, y, f(x, y))
        return out
    }

    private fun put(buf: ByteArray, x: Int, y: Int, v: Int) {
        val o = (y * w + x) * 4
        buf[o] = v.toByte()
        buf[o + 1] = (v shr 8).toByte()
        buf[o + 2] = (v shr 16).toByte()
        buf[o + 3] = (v shr 24).toByte()
    }

    private fun pixel(buf: ByteArray, x: Int, y: Int): ByteArray {
        val o = (y * w + x) * 4
        return buf.copyOfRange(o, o + 4)
    }

    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
}
