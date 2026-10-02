package com.g992.anhud

internal object OverlayPositionMath {
    fun runtimeStartPx(
        positionPx: Float,
        containerPx: Float,
        contentPx: Float,
        anchorFraction: Float
    ): Float {
        return clampStartPx(
            anchoredStartPx(positionPx, contentPx, anchorFraction),
            containerPx,
            contentPx
        )
    }

    fun previewStartPx(
        positionPx: Float,
        boundsPx: Float,
        previewContainerPx: Float,
        contentPx: Float,
        anchorFraction: Float
    ): Float {
        val safePreviewContainerPx = previewContainerPx.coerceAtLeast(1f)
        val previewAnchorPx = if (boundsPx > 0f) {
            (positionPx / boundsPx) * safePreviewContainerPx
        } else {
            0f
        }
        return clampStartPx(
            anchoredStartPx(previewAnchorPx, contentPx, anchorFraction),
            safePreviewContainerPx,
            contentPx
        )
    }

    fun positionPxFromPreviewStart(
        previewStartPx: Float,
        boundsPx: Float,
        previewContainerPx: Float,
        contentPx: Float,
        anchorFraction: Float
    ): Float {
        val safePreviewContainerPx = previewContainerPx.coerceAtLeast(1f)
        val clampedPreviewStartPx = clampStartPx(previewStartPx, safePreviewContainerPx, contentPx)
        val maxPreviewStartPx = (safePreviewContainerPx - contentPx.coerceAtLeast(0f)).coerceAtLeast(0f)
        // A preview uses a different scale and sometimes different content than the live HUD.
        // Save the physical edge itself so runtime clamping can align the live view exactly.
        if (maxPreviewStartPx > 0f) {
            if (clampedPreviewStartPx <= EDGE_SNAP_TOLERANCE_PX) return 0f
            if (maxPreviewStartPx - clampedPreviewStartPx <= EDGE_SNAP_TOLERANCE_PX) {
                return boundsPx.coerceAtLeast(0f)
            }
        }
        val anchorOffsetPx = contentPx.coerceAtLeast(0f) * anchorFraction.coerceIn(0f, 1f)
        val previewAnchorPx = clampedPreviewStartPx + anchorOffsetPx
        return (previewAnchorPx / safePreviewContainerPx) * boundsPx.coerceAtLeast(0f)
    }

    private fun anchoredStartPx(positionPx: Float, contentPx: Float, anchorFraction: Float): Float {
        val safeContentPx = contentPx.coerceAtLeast(0f)
        val safeAnchorFraction = anchorFraction.coerceIn(0f, 1f)
        return positionPx - (safeContentPx * safeAnchorFraction)
    }

    private fun clampStartPx(startPx: Float, containerPx: Float, contentPx: Float): Float {
        val maxStartPx = (containerPx.coerceAtLeast(0f) - contentPx.coerceAtLeast(0f)).coerceAtLeast(0f)
        return startPx.coerceIn(0f, maxStartPx)
    }

    private const val EDGE_SNAP_TOLERANCE_PX = 1f
}
