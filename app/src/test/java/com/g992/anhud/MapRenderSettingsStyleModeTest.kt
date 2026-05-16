package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapRenderSettingsStyleModeTest {
    @Test
    fun normalized_dropsCustomStylePayloadWhenSystemModeSelected() {
        val normalized = MapRenderSettings(
            styleModeId = MapStyleMode.SYSTEM.id,
            customStyleName = "custom.json",
            customStyleJson = """{"version":8,"sources":{},"layers":[]}""",
        ).normalized()

        assertEquals(MapStyleMode.SYSTEM, normalized.effectiveMapStyleMode())
        assertNull(normalized.customStyleName)
        assertNull(normalized.customStyleJson)
    }

    @Test
    fun normalized_fallsBackToSystemWhenCustomJsonMissing() {
        val normalized = MapRenderSettings(
            styleModeId = MapStyleMode.USER.id,
            customStyleName = "custom.json",
            customStyleJson = "   ",
        ).normalized()

        assertEquals(MapStyleMode.SYSTEM.id, normalized.styleModeId)
        assertEquals(MapStyleMode.SYSTEM, normalized.effectiveMapStyleMode())
        assertNull(normalized.customStyleName)
        assertNull(normalized.customStyleJson)
    }

    @Test
    fun normalized_keepsCustomStyleWhenUserModeHasJson() {
        val normalized = MapRenderSettings(
            styleModeId = MapStyleMode.USER.id,
            customStyleName = "custom.json",
            customStyleJson = """{"version":8,"sources":{},"layers":[]}""",
        ).normalized()

        assertEquals(MapStyleMode.USER.id, normalized.styleModeId)
        assertEquals(MapStyleMode.USER, normalized.effectiveMapStyleMode())
        assertEquals("custom.json", normalized.customStyleName)
        assertEquals("""{"version":8,"sources":{},"layers":[]}""", normalized.customStyleJson)
    }
}
