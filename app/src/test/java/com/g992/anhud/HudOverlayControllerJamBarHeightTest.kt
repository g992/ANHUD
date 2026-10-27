package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The jams bar height is added below the map, never carved out of its configured size. */
class HudOverlayControllerJamBarHeightTest {
    @Test
    fun `scales with map height, within a minimum`() {
        // 12% of 300px is 36px, comfortably above the 6dp minimum at density 1.
        assertEquals(36, HudOverlayController.resolveJamBarHeightPx(300, density = 1f, hasProgress = false))
    }

    @Test
    fun `falls back to the density minimum on a short map`() {
        // 12% of 20px is 2px, below the 6dp minimum, so the minimum wins.
        assertEquals(6, HudOverlayController.resolveJamBarHeightPx(20, density = 1f, hasProgress = false))
    }

    @Test
    fun `the arrow variant is taller than the plain bar`() {
        val plain = HudOverlayController.resolveJamBarHeightPx(300, density = 1f, hasProgress = false)
        val withArrow = HudOverlayController.resolveJamBarHeightPx(300, density = 1f, hasProgress = true)
        assertTrue(withArrow > plain)
    }

    @Test
    fun `never exceeds the map's own height`() {
        val height = HudOverlayController.resolveJamBarHeightPx(10, density = 3f, hasProgress = true)
        assertTrue(height <= 10)
    }

    @Test
    fun `degenerate input yields zero, never a crash`() {
        assertEquals(0, HudOverlayController.resolveJamBarHeightPx(0, density = 2f, hasProgress = false))
        assertEquals(0, HudOverlayController.resolveJamBarHeightPx(100, density = 0f, hasProgress = false))
        assertEquals(0, HudOverlayController.resolveJamBarHeightPx(-5, density = 2f, hasProgress = false))
    }
}
