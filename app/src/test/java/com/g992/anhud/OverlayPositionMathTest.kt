package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionMathTest {
    @Test
    fun topLeftAnchorRoundTripsBetweenRuntimeAndPreview() {
        val previewStartPx = OverlayPositionMath.previewStartPx(
            positionPx = 120f,
            boundsPx = 400f,
            previewContainerPx = 200f,
            contentPx = 40f,
            anchorFraction = 0f
        )

        val restoredPositionPx = OverlayPositionMath.positionPxFromPreviewStart(
            previewStartPx = previewStartPx,
            boundsPx = 400f,
            previewContainerPx = 200f,
            contentPx = 40f,
            anchorFraction = 0f
        )

        assertEquals(120f, restoredPositionPx, 0.001f)
    }

    @Test
    fun centerAnchorRoundTripsBetweenRuntimeAndPreview() {
        val previewStartPx = OverlayPositionMath.previewStartPx(
            positionPx = 180f,
            boundsPx = 400f,
            previewContainerPx = 200f,
            contentPx = 60f,
            anchorFraction = 0.5f
        )

        val restoredPositionPx = OverlayPositionMath.positionPxFromPreviewStart(
            previewStartPx = previewStartPx,
            boundsPx = 400f,
            previewContainerPx = 200f,
            contentPx = 60f,
            anchorFraction = 0.5f
        )

        assertEquals(180f, restoredPositionPx, 0.001f)
    }

    @Test
    fun centerAnchorKeepsLogicalCenterStableWhenWidthChanges() {
        val oldStartPx = OverlayPositionMath.runtimeStartPx(
            positionPx = 200f,
            containerPx = 400f,
            contentPx = 56f,
            anchorFraction = 0.5f
        )
        val newStartPx = OverlayPositionMath.runtimeStartPx(
            positionPx = 200f,
            containerPx = 400f,
            contentPx = 76f,
            anchorFraction = 0.5f
        )

        assertEquals(172f, oldStartPx, 0.001f)
        assertEquals(162f, newStartPx, 0.001f)
    }
}
