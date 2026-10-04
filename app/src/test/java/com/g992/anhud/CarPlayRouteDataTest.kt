package com.g992.anhud

import org.junit.Assert.*
import org.junit.Test

class CarPlayRouteDataTest {
    @Test
    fun distanceThresholdsUseMetersForEveryCarPlayUnit() {
        assertEquals(1500, ProviderNavFormat.distanceMeters("1,5 км"))
        assertEquals(1609, ProviderNavFormat.distanceMeters("1 миль"))
        assertEquals(457, ProviderNavFormat.distanceMeters("1 500 фт"))
        assertEquals(914, ProviderNavFormat.distanceMeters("1\u202f000 ярд"))
        assertEquals(1500, ProviderNavFormat.distanceMeters("1 500 м"))
        assertEquals(300, ProviderNavFormat.distanceMeters("Через 300 м"))
        assertNull(ProviderNavFormat.distanceMeters("нет данных"))
        assertNull(ProviderNavFormat.distanceMeters("300"))
        assertEquals(300, ProviderNavFormat.distanceMeters("300", allowUnitless = true))
    }

    @Test
    fun formatsAutolinkUnitsWithoutGuessingUnknownUnits() {
        val expected = listOf("1,5 км", "1,5 миль", "1,5 м", "1,5 ярд", "1,5 фт")
        expected.forEachIndexed { unit, text ->
            assertEquals(text, CarPlayRouteData.displayDistance(" 1,5 ", unit))
        }
        assertEquals("", CarPlayRouteData.displayDistance("  ", 2))
        assertEquals("150", CarPlayRouteData.displayDistance("150", 99))
    }

    @Test
    fun usesCarPlayManeuverValuesRatherThanAndroidAutoValues() {
        assertEquals("context_ra_turn_left", CarPlayRouteData.maneuverKey(1))
        assertEquals("context_ra_turn_right", CarPlayRouteData.maneuverKey(2))
        assertEquals("context_ra_hard_turn_left", CarPlayRouteData.maneuverKey(47))
        assertEquals("context_ra_take_right", CarPlayRouteData.maneuverKey(53))
        assertEquals("context_ra_finish", CarPlayRouteData.maneuverKey(12))
        for (maneuver in 28..46) {
            assertEquals("context_ra_out_circular_movement", CarPlayRouteData.maneuverKey(maneuver))
            assertEquals(maneuver - 27, CarPlayRouteData(maneuver, "", 2, "", "", 0, 0, 1, 0, 0, 0).update().exitNumber)
        }
        assertNull(CarPlayRouteData(7, "", 2, "", "", 0, 0, 1, 0, 0, 0).exitNumber)
        assertEquals("context_ra_via", CarPlayRouteData.maneuverKey(999))
    }

    @Test
    fun recognizesRouteLifecycleAndFormatsShortTravelTimes() {
        for (state in -1..8) {
            assertEquals(state in 1..6, CarPlayRouteData(1, "", 2, "", "", 0, 0, state, 0, 0, 0).hasRoute)
        }
        assertEquals("1 мин", ProviderNavFormat.seconds(1))
        assertEquals("1 ч 1 мин", ProviderNavFormat.seconds(3601))
        assertEquals("", ProviderNavFormat.seconds(0))
        assertEquals("", ProviderNavFormat.arrival(Long.MAX_VALUE))
        assertEquals("", ProviderNavFormat.arrival(-1))
    }
}
