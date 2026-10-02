package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexSignalPolicyTest {
    @Test fun passedJunctionDoesNotEndRouteEvenWhenApkSaysRouteGone() {
        assertFalse(isYandexRouteEnd("yandex_lane_clear", true))
        assertTrue(isYandexRouteEnd("route_end", true))
        assertFalse(isYandexRouteEnd("route_end", false))
    }

    @Test fun emptyLimitClearsDuringRouteButKeepsLastLimitAfterRoute() {
        assertEquals("", yandexSpeedLimitAfterUpdate("90", "", true))
        assertEquals("90", yandexSpeedLimitAfterUpdate("90", "", false))
        assertEquals("", yandexSpeedLimitAfterUpdate("90", "--", true))
    }

    @Test fun laneQueueFallbackUsesFirstJunction() {
        assertEquals("[←]  ↑", formatYandexLanes("LEFT90:LEFT90:PLAIN_LANE|STRAIGHT_AHEAD:NONE:BUS_LANE;RIGHT90:RIGHT90:PLAIN_LANE"))
    }
}
