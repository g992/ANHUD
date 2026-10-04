package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderNavFormatDistanceTest {
    @Test
    fun laneDistanceUsesManeuverRoundingSteps() {
        assertEquals(ProviderNavFormat.meters(648), ProviderNavFormat.roundedDistance("648 м"))
        assertEquals("600 м", ProviderNavFormat.roundedDistance("648 м"))
        assertEquals("450 м", ProviderNavFormat.roundedDistance("437 м"))
        assertEquals("80 м", ProviderNavFormat.roundedDistance("76м"))
        assertEquals("700 м", ProviderNavFormat.roundedDistance("650"))
        assertEquals("1,2 км", ProviderNavFormat.roundedDistance("1,2 км"))
    }

    @Test
    fun blankOrUnparseableDistanceIsKept() {
        assertEquals("", ProviderNavFormat.roundedDistance("  "))
        assertEquals("скоро", ProviderNavFormat.roundedDistance("скоро"))
    }
}
