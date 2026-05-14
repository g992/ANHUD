package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    @Test
    fun resolveRouteTrimStart_backtracksAcrossPreviousSegment() {
        val points = listOf(
            LatLng(55.0000, 37.0000),
            LatLng(55.0001, 37.0000),
            LatLng(55.0002, 37.0000),
            LatLng(55.0003, 37.0000),
        )

        val trimStart = resolveRouteTrimStart(
            points = points,
            targetLat = 55.00025,
            targetLon = 37.0000,
            backtrackMeters = 12.0,
            maxProjectionDistanceMeters = 35.0
        )

        assertEquals(1, trimStart.segmentIndex)
        assertTrue(trimStart.point.latitude > 55.0001)
        assertTrue(trimStart.point.latitude < 55.0002)
    }

    @Test
    fun resolveRouteTrimStart_fallsBackToRouteStartWhenLocationIsFar() {
        val points = listOf(
            LatLng(55.0000, 37.0000),
            LatLng(55.0001, 37.0000),
            LatLng(55.0002, 37.0000),
        )

        val trimStart = resolveRouteTrimStart(
            points = points,
            targetLat = 55.0010,
            targetLon = 37.0010,
            backtrackMeters = 12.0,
            maxProjectionDistanceMeters = 35.0
        )

        assertEquals(0, trimStart.segmentIndex)
        assertEquals(points.first().latitude, trimStart.point.latitude, 0.0)
        assertEquals(points.first().longitude, trimStart.point.longitude, 0.0)
    }
}
