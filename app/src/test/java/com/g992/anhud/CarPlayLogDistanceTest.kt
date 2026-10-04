package com.g992.anhud

import org.junit.Assert.*
import org.junit.Test

class CarPlayLogDistanceTest {
    private val now = 1_700_000_000_000L
    private val since = now - 2000
    private fun line(text: String, age: Long = 100) =
        "${(now - age) / 1000}.${((now - age) % 1000).toString().padStart(3, '0')} 123 456 I CP-SRV : $text\n"
    private fun route(type: Int = 28, road: String = "Улица") =
        CarPlayRouteData(type, "200", 2, road, "12", 0, 600, 1, 0, 0, 0)

    @Test fun parsesExactApkPatternsAndCorrelatesTurnAndDestination() {
        val logs = line("mManeuverType = 28, mDestinationName = Улица}") +
            line("DistanceRemainingToNextManeuverDisplayString = 183, DistanceRemainingToNextManeuverDisplayUnits = 2")
        val sample = CarPlayLogDistance.parse(logs, since, now)!!
        assertEquals("183", sample.distance)
        assertEquals("183 м", CarPlayRouteData.displayDistance(sample.distance, sample.units))
        assertTrue(sample.matches(route(), since, now))
        assertFalse(sample.matches(route(29), since, now))
        assertFalse(sample.matches(route(28, "Другая улица"), since, now))
        assertFalse(sample.matches(route().copy(routeState = 7), since, now))
    }

    @Test fun acceptsSplitFieldsInEitherOrderWithoutRefreshingDistanceTimestamp() {
        for (fields in listOf(
            line("DistanceRemainingToNextManeuverStr = 0,25", 200) + line("DistanceRemainingToNextManeuverUnits = 1"),
            line("DistanceRemainingToNextManeuverUnits = 1", 200) + line("DistanceRemainingToNextManeuverString = 0,25", 200)
        )) {
            val sample = CarPlayLogDistance.parse(line("ManeuverType = 28", 500) + fields, since, now)!!
            assertEquals("0,25 миль", CarPlayRouteData.displayDistance(sample.distance, sample.units))
            assertEquals(now - 200, sample.timestamp)
        }
    }

    @Test fun staleFutureUncorrelatedAndInvalidLogsCannotSupplyDistance() {
        val record = "ManeuverType = 28, DistanceRemainingToNextManeuverStr = 183, DistanceRemainingToNextManeuverUnits = 2"
        assertNull(CarPlayLogDistance.parse(line(record, 6000), now - 10000, now))
        assertNull(CarPlayLogDistance.parse(line(record, -1), since, now))
        assertNull(CarPlayLogDistance.parse(line(record), now, now))
        assertNull(CarPlayLogDistance.parse(line(record.replace("CP-SRV", "Other")).replace("CP-SRV", "Other"), since, now))
        assertNull(CarPlayLogDistance.parse(line("DistanceRemainingToNextManeuverStr = 183, DistanceRemainingToNextManeuverUnits = 2"), since, now))
        assertNull(CarPlayLogDistance.parse(line(record.replace("183", "1.2.3")), since, now))
        assertNull(CarPlayLogDistance.parse(line(record.replace("Units = 2", "Units = 99")), since, now))
        val sample = CarPlayLogDistance.parse(line(record), since, now)!!
        assertFalse(sample.matches(route(), since, now + 5001))
    }

    @Test fun nextManeuverOrDifferentDestinationInvalidatesPreviousDistance() {
        val record = line("ManeuverType = 28, DestinationName = Улица, DistanceRemainingToNextManeuverStr = 183, DistanceRemainingToNextManeuverUnits = 2", 500)
        assertNull(CarPlayLogDistance.parse(record + line("ManeuverType = 29"), since, now))
        assertNull(CarPlayLogDistance.parse(record + line("DestinationName = Другая улица}"), since, now))
        assertNull(CarPlayLogDistance.parse(record + line("ManeuverType = 29") + line("DistanceRemainingToNextManeuverStr = 80"), since, now))
    }
}
