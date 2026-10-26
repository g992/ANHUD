package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneGuidanceDistanceLimitTest {
    @Test
    fun hidesOnlyBeyondTheLimitWhenEnabled() {
        assertTrue(OverlayPrefs.laneGuidanceWithinDistance(300, limitEnabled = true, maxDistanceMeters = 300))
        assertFalse(OverlayPrefs.laneGuidanceWithinDistance(301, limitEnabled = true, maxDistanceMeters = 300))
        assertTrue(OverlayPrefs.laneGuidanceWithinDistance(5000, limitEnabled = false, maxDistanceMeters = 300))
        assertTrue("unknown distance", OverlayPrefs.laneGuidanceWithinDistance(null, limitEnabled = true, maxDistanceMeters = 10))
    }

    @Test
    fun stepsCoverTenMetersToOneKilometer() {
        val steps = OverlayPrefs.LANE_GUIDANCE_DISTANCE_STEPS_M
        assertEquals(10, steps.first())
        assertEquals(1000, steps.last())
        assertTrue(steps.toList().zipWithNext().all { (a, b) -> a < b })
        assertEquals(steps.indexOf(OverlayPrefs.LANE_GUIDANCE_MAX_DISTANCE_DEFAULT_M), OverlayPrefs.laneGuidanceDistanceStepIndex(300))
        assertEquals(steps.indexOf(250), OverlayPrefs.laneGuidanceDistanceStepIndex(260))
        assertEquals(0, OverlayPrefs.laneGuidanceDistanceStepIndex(1))
        assertEquals(steps.size - 1, OverlayPrefs.laneGuidanceDistanceStepIndex(5000))
    }
}
