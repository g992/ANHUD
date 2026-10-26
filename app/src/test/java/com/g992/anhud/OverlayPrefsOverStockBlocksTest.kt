package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPrefsOverStockBlocksTest {
    @Test
    fun selectionRoundTripsInDialogOrderAndDropsUnknownKeys() {
        val stored = OverlayPrefs.formatOverStockBlocks(
            setOf(OverlayBroadcasts.PREVIEW_TARGET_MAP, "bogus", OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER)
        )
        assertEquals("speedometer,map", stored)
        assertEquals(
            listOf(OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER, OverlayBroadcasts.PREVIEW_TARGET_MAP),
            OverlayPrefs.parseOverStockBlocks("map, speedometer,,bogus").toList()
        )
    }

    @Test
    fun nothingStoredMeansNothingSelected() {
        assertTrue(OverlayPrefs.parseOverStockBlocks(null).isEmpty())
        assertTrue(OverlayPrefs.parseOverStockBlocks("").isEmpty())
        assertEquals("", OverlayPrefs.formatOverStockBlocks(emptySet()))
    }

    @Test
    fun everyOverlayBlockIsOffered() {
        assertEquals(12, OverlayPrefs.OVER_STOCK_BLOCK_KEYS.size)
        assertEquals(OverlayPrefs.OVER_STOCK_BLOCK_KEYS.size, OverlayPrefs.OVER_STOCK_BLOCK_KEYS.toSet().size)
        assertTrue(OverlayPrefs.OVER_STOCK_CUSTOM_BLOCKS in OverlayPrefs.OVER_STOCK_BLOCK_KEYS)
    }
}
