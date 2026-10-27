package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Карта только когда есть маршрут" decision, isolated from the view/Context-heavy controller. */
class HudOverlayControllerMapImageTest {
    @Test
    fun `live image always wins regardless of the setting`() {
        assertEquals("live", HudOverlayController.resolveMapImage("live", "last", onlyWithRoute = true))
        assertEquals("live", HudOverlayController.resolveMapImage("live", "last", onlyWithRoute = false))
        assertEquals("live", HudOverlayController.resolveMapImage("live", null, onlyWithRoute = false))
    }

    @Test
    fun `onlyWithRoute hides the map once the route ends`() {
        assertNull(HudOverlayController.resolveMapImage(null, "last", onlyWithRoute = true))
    }

    @Test
    fun `off keeps the last known picture past route end`() {
        assertEquals("last", HudOverlayController.resolveMapImage(null, "last", onlyWithRoute = false))
    }

    @Test
    fun `nothing received yet shows nothing either way`() {
        assertNull(HudOverlayController.resolveMapImage<String>(null, null, onlyWithRoute = true))
        assertNull(HudOverlayController.resolveMapImage<String>(null, null, onlyWithRoute = false))
    }
}
