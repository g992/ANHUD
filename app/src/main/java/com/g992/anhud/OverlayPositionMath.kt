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
}
