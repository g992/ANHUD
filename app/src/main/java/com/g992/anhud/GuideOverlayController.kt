package com.g992.anhud

import android.app.Activity
import android.graphics.Rect
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

class GuideOverlayController(
    private val activity: Activity,
    private val scrollView: ScrollView,
    private val steps: List<GuideContent.GuideItem>,
    private val onFinish: (FinishReason) -> Unit,
    private val overlayRootProvider: () -> ViewGroup? = { activity.findViewById(android.R.id.content) },
    private val viewFinder: (Int) -> View? = { id -> activity.findViewById(id) }
) {
    enum class FinishReason {
        Completed,
        Skipped
    }
    private var currentIndex = -1
    private var overlayContainer: FrameLayout? = null
    private var overlayRoot: ViewGroup? = null
    private var spotlightView: GuideSpotlightView? = null
    private var tooltipView: View? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var progressView: TextView? = null
    private var nextButton: Button? = null
    private var skipButton: TextView? = null

    fun start() {
        if (overlayContainer != null) return
        val root = overlayRootProvider() ?: return
        overlayContainer = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }
        spotlightView = GuideSpotlightView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val tooltip = LayoutInflater.from(activity)
            .inflate(R.layout.view_guide_tooltip, overlayContainer, false)
        tooltipView = tooltip
        titleView = tooltip.findViewById(R.id.guideTitle)
        bodyView = tooltip.findViewById(R.id.guideBody)
        progressView = tooltip.findViewById(R.id.guideProgress)
        nextButton = tooltip.findViewById(R.id.guideNextButton)
        skipButton = tooltip.findViewById(R.id.guideSkipButton)

        overlayContainer?.addView(spotlightView)
        overlayContainer?.addView(tooltip)
        root.addView(overlayContainer)
        overlayRoot = root

        nextButton?.setOnClickListener { showNextStep() }
        skipButton?.setOnClickListener { finishGuide(FinishReason.Skipped) }

        showNextStep()
    }

    fun stop() {
        overlayContainer?.let { overlayRoot?.removeView(it) }
        overlayContainer = null
        overlayRoot = null
        spotlightView = null
        tooltipView = null
        titleView = null
        bodyView = null
        progressView = null
        nextButton = null
        skipButton = null
    }

    private fun showNextStep() {
        val nextIndex = findNextIndex(currentIndex + 1)
        if (nextIndex < 0) {
            finishGuide(FinishReason.Completed)
            return
        }
        currentIndex = nextIndex
        val step = steps[currentIndex]
        val targetId = step.targetId ?: run {
            showNextStep()
            return
        }
        val target = viewFinder(targetId)
        if (target == null || !target.isShown) {
            showNextStep()
            return
        }
        updateTooltipText(step)
        scrollToTarget(target) {
            updateSpotlight(target)
        }
    }

    private fun updateTooltipText(step: GuideContent.GuideItem) {
        val visible = visibleSteps()
        val currentVisibleIndex = visible.indexOf(currentIndex).takeIf { it >= 0 } ?: 0
        val total = if (visible.isEmpty()) 1 else visible.size
        titleView?.setText(step.titleRes)
        bodyView?.setText(step.bodyRes)
        progressView?.text = activity.getString(
            R.string.guide_progress_format,
            currentVisibleIndex + 1,
            total
        )
        val isLast = visible.isNotEmpty() && currentVisibleIndex == visible.lastIndex
        nextButton?.setText(if (isLast) R.string.guide_done else R.string.guide_next)
    }

    private fun visibleSteps(): List<Int> {
        val result = ArrayList<Int>(steps.size)
        for (index in steps.indices) {
            val targetId = steps[index].targetId ?: continue
            val view = viewFinder(targetId) ?: continue
            if (view.isShown) {
                result.add(index)
            }
        }
        return result
    }

    private fun findNextIndex(startIndex: Int): Int {
        var index = startIndex
        while (index < steps.size) {
            val targetId = steps[index].targetId
            if (targetId != null) {
                val view = viewFinder(targetId)
                if (view != null && view.isShown) {
                    return index
                }
            }
            index++
        }
        return -1
    }

    private fun scrollToTarget(target: View, onReady: () -> Unit) {
        val scrollPadding = dp(24)
        val rect = Rect()
        target.getDrawingRect(rect)
        scrollView.offsetDescendantRectToMyCoords(target, rect)
        val targetY = (rect.top - scrollPadding).coerceAtLeast(0)
        scrollView.post {
            scrollView.smoothScrollTo(0, targetY)
            scrollView.postDelayed({
                if (overlayContainer != null) {
                    onReady()
                }
            }, 180)
        }
    }

    private fun updateSpotlight(target: View) {
        val overlay = overlayContainer ?: return
        val spotlight = spotlightView ?: return
        val tooltip = tooltipView ?: return

        val targetLocation = IntArray(2)
        val overlayLocation = IntArray(2)
        target.getLocationInWindow(targetLocation)
        overlay.getLocationInWindow(overlayLocation)
        val padding = dp(8).toFloat()
        val left = targetLocation[0].toFloat() - overlayLocation[0].toFloat() - padding
        val top = targetLocation[1].toFloat() - overlayLocation[1].toFloat() - padding
        val rectF = RectF(
            left,
            top,
            left + target.width.toFloat() + padding * 2,
            top + target.height.toFloat() + padding * 2
        )
        spotlight.targetRect = rectF

        overlay.post {
            positionTooltip(overlay, tooltip, rectF)
        }
    }

    private fun positionTooltip(overlay: View, tooltip: View, targetRect: RectF) {
        val margin = dp(16)
        val maxWidth = (overlay.width - margin * 2).coerceAtLeast(0)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        tooltip.measure(widthSpec, heightSpec)

        val tooltipWidth = tooltip.measuredWidth
        val tooltipHeight = tooltip.measuredHeight
        val spaceBelow = overlay.height - targetRect.bottom
        val placeBelow = spaceBelow >= tooltipHeight + margin
        val x = (targetRect.centerX() - tooltipWidth / 2f)
            .coerceIn(margin.toFloat(), (overlay.width - tooltipWidth - margin).toFloat())
        val y = if (placeBelow) {
            targetRect.bottom + margin
        } else {
            (targetRect.top - tooltipHeight - margin).coerceAtLeast(margin.toFloat())
        }

        val params = tooltip.layoutParams as FrameLayout.LayoutParams
        params.width = tooltipWidth
        params.leftMargin = x.roundToInt()
        params.topMargin = y.roundToInt()
        tooltip.layoutParams = params
    }

    private fun finishGuide(reason: FinishReason) {
        stop()
        onFinish(reason)
    }

    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).roundToInt()
    }
}
