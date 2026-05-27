package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPrefsDynamicHideTurnHysteresisTest {
    @Test
    fun `starts with bucket for current speed`() {
        val bucket = OverlayPrefs.applyDynamicHideTurnSpeedBucketHysteresis(
            currentBucket = null,
            speedKmh = 59
        )

        assertEquals(OverlayPrefs.DynamicHideTurnSpeedBucket.FROM_40_TO_60, bucket)
    }

    @Test
    fun `holds lower bucket until speed exceeds upper threshold plus hysteresis`() {
        var bucket = OverlayPrefs.DynamicHideTurnSpeedBucket.UP_TO_40

        bucket = OverlayPrefs.applyDynamicHideTurnSpeedBucketHysteresis(bucket, 40)
        assertEquals(OverlayPrefs.DynamicHideTurnSpeedBucket.UP_TO_40, bucket)

        bucket = OverlayPrefs.applyDynamicHideTurnSpeedBucketHysteresis(bucket, 44)
        assertEquals(OverlayPrefs.DynamicHideTurnSpeedBucket.UP_TO_40, bucket)

        bucket = OverlayPrefs.applyDynamicHideTurnSpeedBucketHysteresis(bucket, 45)
        assertEquals(OverlayPrefs.DynamicHideTurnSpeedBucket.FROM_40_TO_60, bucket)
    }

    @Test
    fun `holds higher bucket until speed drops below lower threshold minus hysteresis`() {
        var bucket = OverlayPrefs.DynamicHideTurnSpeedBucket.FROM_40_TO_60

        bucket = OverlayPrefs.applyDynamicHideTurnSpeedBucketHysteresis(bucket, 40)
        assertEquals(OverlayPrefs.DynamicHideTurnSpeedBucket.FROM_40_TO_60, bucket)

        bucket = OverlayPrefs.applyDynamicHideTurnSpeedBucketHysteresis(bucket, 35)
        assertEquals(OverlayPrefs.DynamicHideTurnSpeedBucket.FROM_40_TO_60, bucket)

        bucket = OverlayPrefs.applyDynamicHideTurnSpeedBucketHysteresis(bucket, 34)
        assertEquals(OverlayPrefs.DynamicHideTurnSpeedBucket.UP_TO_40, bucket)
    }
}
