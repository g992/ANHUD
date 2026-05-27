package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.android.location.LocationComponentConstants

class HudMapLayerOrderTest {
    @Test
    fun locationLayerAnchor_matchesTopNavigationOverlayLayer() {
        assertEquals("anhud-map-lane-maneuver-layer", MAP_LOCATION_LAYER_ABOVE_ID)
    }

    @Test
    fun resolveFirstAvailableLayerId_returnsFirstPresentPreferredTarget() {
        val resolved = resolveFirstAvailableLayerId(
            availableLayerIds = setOf(
                "anhud-map-route-layer",
                "anhud-map-lane-maneuver-layer",
                LocationComponentConstants.FOREGROUND_LAYER
            ),
            preferredLayerIds = listOf(
                "anhud-map-route-alert-layer",
                "anhud-map-lane-maneuver-layer",
                LocationComponentConstants.FOREGROUND_LAYER
            )
        )

        assertEquals("anhud-map-lane-maneuver-layer", resolved)
    }

    @Test
    fun resolveFirstAvailableLayerId_returnsNullWhenNoPreferredTargetsExist() {
        val resolved = resolveFirstAvailableLayerId(
            availableLayerIds = setOf("background", "road-label"),
            preferredLayerIds = listOf(
                "anhud-map-route-alert-layer",
                "anhud-map-lane-maneuver-layer",
                LocationComponentConstants.FOREGROUND_LAYER
            )
        )

        assertNull(resolved)
    }
}
