package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTimeLineTest {
    @Test
    fun allParts() {
        assertEquals("25 мин\n12 км (14:30)", NavTimeLine.format("25 мин", "12 км", "14:30"))
    }

    @Test
    fun missingParts() {
        assertEquals("25 мин (14:30)", NavTimeLine.format("25 мин", "", "14:30"))
        assertEquals("12 км (14:30)", NavTimeLine.format("", "12 км", "14:30"))
        assertEquals("25 мин\n12 км", NavTimeLine.format("25 мин", "12 км", ""))
        assertEquals("12 км", NavTimeLine.format(" ", "12 км ", ""))
        assertEquals("(14:30)", NavTimeLine.format("", "", "14:30"))
        assertEquals("", NavTimeLine.format("", "", ""))
    }
}
