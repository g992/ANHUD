package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudMapHeadingFallbackTest {
    @Test
    fun bearingBetweenPoints_returnsEastForLongitudeIncrease() {
        val bearing = bearingBetweenPoints(
            start = LatLng(55.0, 37.0),
            end = LatLng(55.0, 37.001)
        )

        assertEquals(90f, bearing, 3f)
    }

    @Test
    fun shouldPreferDerivedBearing_whenReportedBearingIsStuckAndTrackTurns() {
        assertTrue(
            shouldPreferDerivedBearing(
                previousReportedBearing = 90f,
                reportedBearing = 90f,
                derivedBearing = 135f
            )
        )
    }

    @Test
    fun shouldPreferDerivedBearing_whenTrackStillMatchesReportedHeading() {
        assertFalse(
            shouldPreferDerivedBearing(
                previousReportedBearing = 90f,
                reportedBearing = 90f,
                derivedBearing = 94f
            )
        )
    }

    @Test
    fun resolveHeadingBearing_smoothsAcrossNorthWraparound() {
        val bearing = resolveHeadingBearing(
            previousResolvedBearing = 350f,
            previousReportedBearing = 350f,
            reportedBearing = null,
            derivedBearing = 10f
        )

        assertEquals(357f, bearing ?: error("bearing expected"), 0.5f)
    }
}
