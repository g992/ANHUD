package com.g992.anhud

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class HudOverlayController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var navContainer: LinearLayout? = null
    private var maneuverView: ImageView? = null
    private var maneuverLabel: TextView? = null
    private var primaryView: TextView? = null
    private var secondaryView: TextView? = null
    private var timeView: TextView? = null
    private var speedLimitView: TextView? = null
    private var currentDisplayId: Int? = null
    private var lastState: NavigationHudState = NavigationHudState()
    private var navPositionDp: PointF = OverlayPrefs.navPositionDp(context)
    private var speedPositionDp: PointF = OverlayPrefs.speedPositionDp(context)
    private var navScale: Float = OverlayPrefs.navScale(context)
    private var speedScale: Float = OverlayPrefs.speedScale(context)
    private var navAlpha: Float = OverlayPrefs.navAlpha(context)
    private var speedAlpha: Float = OverlayPrefs.speedAlpha(context)
    private var previewMode: Boolean = false
    private var previewTarget: String? = null
    private var previewShowOthers: Boolean = false

    fun refresh() {
        handler.post {
            if (!OverlayPrefs.isEnabled(context) || !Settings.canDrawOverlays(context)) {
                removeOverlay()
                return@post
            }
            val display = HudDisplayUtils.resolveDisplay(context, OverlayPrefs.displayId(context))
            if (display == null) {
                removeOverlay()
                UiLogStore.append(LogCategory.SYSTEM, "Оверлей: экран не найден")
                return@post
            }
            if (overlayView != null && currentDisplayId == display.displayId) {
                applyLayout()
                applyState(lastState)
                return@post
            }
            removeOverlay()
            createOverlay(display)
            applyLayout()
            applyState(lastState)
        }
    }

    fun updateNavigation(state: NavigationHudState) {
        handler.post {
            lastState = state
            applyState(state)
        }
    }

    fun updateLayout(
        navPosition: PointF?,
        speedPosition: PointF?,
        navScale: Float?,
        speedScale: Float?,
        navAlpha: Float?,
        speedAlpha: Float?,
        preview: Boolean? = null,
        previewTarget: String? = null,
        previewShowOthers: Boolean? = null
    ) {
        handler.post {
            if (navPosition != null) {
                navPositionDp = navPosition
            }
            if (speedPosition != null) {
                speedPositionDp = speedPosition
            }
            if (navScale != null) {
                this.navScale = navScale.coerceAtLeast(0f)
            }
            if (speedScale != null) {
                this.speedScale = speedScale.coerceAtLeast(0f)
            }
            if (navAlpha != null) {
                this.navAlpha = navAlpha.coerceIn(0f, 1f)
            }
            if (speedAlpha != null) {
                this.speedAlpha = speedAlpha.coerceIn(0f, 1f)
            }
            if (preview != null) {
                previewMode = preview
            }
            if (previewTarget != null) {
                this.previewTarget = previewTarget
            }
            if (previewShowOthers != null) {
                this.previewShowOthers = previewShowOthers
            }
            applyLayout()
            applyState(lastState)
        }
    }

    fun destroy() {
        handler.post {
            removeOverlay()
        }
    }

    private fun createOverlay(display: android.view.Display) {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java)
        if (wm == null) {
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: WindowManager недоступен")
            return
        }
        val metrics = displayContext.resources.displayMetrics
        val padding = (8 * metrics.density).roundToInt()
        val iconSize = (48 * metrics.density).roundToInt()
        val iconMargin = (8 * metrics.density).roundToInt()
        val speedSize = (40 * metrics.density).roundToInt()

        val root = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val navBlock = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(padding, padding, padding, padding)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val maneuverContainer = FrameLayout(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconMargin
            }
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_direction_box)
        }

        val maneuverImage = ImageView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val maneuverText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            text = displayContext.getString(R.string.preview_direction_label)
            setTextColor(Color.parseColor("#cfcfcf"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        }

        maneuverContainer.addView(maneuverImage)
        maneuverContainer.addView(maneuverText)

        val textColumn = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        val primaryText = TextView(displayContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val secondaryText = TextView(displayContext).apply {
            setTextColor(Color.parseColor("#f5f5f5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val timeText = TextView(displayContext).apply {
            setTextColor(Color.parseColor("#cfcfcf"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        textColumn.addView(primaryText)
        textColumn.addView(secondaryText)
        textColumn.addView(timeText)
        navBlock.addView(maneuverContainer)
        navBlock.addView(textColumn)

        val speedText = TextView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(speedSize, speedSize)
            background = ContextCompat.getDrawable(displayContext, R.drawable.bg_speed_limit)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.8f)
            setTypeface(typeface, Typeface.BOLD)
        }

        root.addView(navBlock)
        root.addView(speedText)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(root, layoutParams)
            windowManager = wm
            overlayView = root
            navContainer = navBlock
            maneuverView = maneuverImage
            maneuverLabel = maneuverText
            primaryView = primaryText
            secondaryView = secondaryText
            timeView = timeText
            speedLimitView = speedText
            currentDisplayId = display.displayId
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: показан на экране ${display.displayId}")
        } catch (e: Exception) {
            UiLogStore.append(LogCategory.SYSTEM, "Оверлей: ошибка addView: ${e.message}")
            windowManager = null
            overlayView = null
            navContainer = null
            maneuverView = null
            maneuverLabel = null
            primaryView = null
            secondaryView = null
            timeView = null
            speedLimitView = null
            currentDisplayId = null
        }
    }

    private fun removeOverlay() {
        val wm = windowManager
        val view = overlayView
        if (wm != null && view != null) {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                UiLogStore.append(LogCategory.SYSTEM, "Оверлей: ошибка удаления: ${e.message}")
            }
        }
        windowManager = null
        overlayView = null
        navContainer = null
        maneuverView = null
        maneuverLabel = null
        primaryView = null
        secondaryView = null
        timeView = null
        speedLimitView = null
        currentDisplayId = null
    }

    private fun applyState(state: NavigationHudState) {
        val primary = primaryView ?: return
        val secondary = secondaryView ?: return
        val time = timeView ?: return
        val container = overlayView
        val showPreview = previewMode
        val target = previewTarget
        val previewNav = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_NAV ||
                previewShowOthers
            )
        val previewSpeed = showPreview && (
            target == null ||
                target == OverlayBroadcasts.PREVIEW_TARGET_SPEED ||
                previewShowOthers
            )

        if (state.isEmpty() && !showPreview) {
            container?.visibility = View.GONE
            return
        }
        container?.visibility = View.VISIBLE

        val primaryText = if (previewNav) {
            context.getString(R.string.preview_distance_text)
        } else {
            val rawPrimary = state.rawNextText.ifBlank { state.primaryText }
            if (state.rawNextText.isNotBlank()) {
                rawPrimary
            } else {
                appendUnitIfMissing(rawPrimary, state.distanceUnit)
            }
        }

        val secondaryText = if (previewNav) {
            context.getString(R.string.preview_street_text)
        } else {
            state.rawNextStreet.ifBlank { state.secondaryText }
        }

        val timeText = if (previewNav) {
            context.getString(R.string.preview_time_text)
        } else {
            buildTimeLine(state)
        }

        setTextOrHide(primary, primaryText)
        setTextOrHide(secondary, secondaryText)
        setTextOrHide(time, timeText)
        updateSpeedLimit(state.speedLimit, previewSpeed)
        updateManeuver(state.maneuverBitmap, previewNav)

        val navHasContent = primaryText.isNotBlank() ||
            secondaryText.isNotBlank() ||
            timeText.isNotBlank() ||
            state.maneuverBitmap != null
        val navVisible = if (showPreview) previewNav else navHasContent
        val speedVisible = if (showPreview) previewSpeed else state.speedLimit.isNotBlank()
        navContainer?.visibility = if (navVisible) View.VISIBLE else View.GONE
        speedLimitView?.visibility = if (speedVisible) View.VISIBLE else View.GONE
        container?.visibility = if (navVisible || speedVisible) View.VISIBLE else View.GONE
        applyLayout()
    }

    private fun updateManeuver(bitmap: android.graphics.Bitmap?, preview: Boolean) {
        val image = maneuverView ?: return
        val label = maneuverLabel ?: return
        if (preview) {
            image.visibility = View.GONE
            label.visibility = View.VISIBLE
            return
        }
        if (bitmap != null) {
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            label.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            label.visibility = View.GONE
        }
    }

    private fun setTextOrHide(view: TextView, text: String) {
        if (text.isBlank()) {
            view.visibility = View.GONE
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    private fun buildTimeLine(state: NavigationHudState): String {
        val time = state.time.trim()
        val arrival = state.arrival.trim()
        if (time.isNotBlank() && arrival.isNotBlank()) {
            return "$time ($arrival)"
        }
        if (time.isNotBlank()) {
            return time
        }
        if (arrival.isNotBlank()) {
            return "($arrival)"
        }
        return ""
    }

    private fun updateSpeedLimit(speedLimit: String, preview: Boolean) {
        val view = speedLimitView ?: return
        val text = when {
            preview -> context.getString(R.string.preview_speed_limit_text)
            speedLimit.isNotBlank() -> speedLimit
            else -> ""
        }
        if (text.isBlank()) {
            view.visibility = View.GONE
            return
        }
        view.text = text
        view.visibility = View.VISIBLE
    }

    private fun appendUnitIfMissing(text: String, unit: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank() || unit.isBlank()) {
            return text
        }
        if (!trimmed.matches(Regex("\\d+(?:[.,]\\d+)?"))) {
            return text
        }
        return "$trimmed $unit"
    }

    private fun applyLayout() {
        overlayView?.post { applyLayoutInternal() }
    }

    private fun applyLayoutInternal() {
        val displayId = currentDisplayId ?: return
        val display = HudDisplayUtils.resolveDisplay(context, displayId) ?: return
        val metrics = context.createDisplayContext(display).resources.displayMetrics
        navContainer?.let { positionView(it, navPositionDp, navScale, navAlpha, metrics) }
        speedLimitView?.let { positionView(it, speedPositionDp, speedScale, speedAlpha, metrics) }
    }

    private fun positionView(
        view: View,
        positionDp: PointF,
        scale: Float,
        alpha: Float,
        metrics: android.util.DisplayMetrics
    ) {
        val (rawWidth, rawHeight) = resolveViewSize(view)
        if (rawWidth <= 0f || rawHeight <= 0f) {
            return
        }
        val scaledWidth = rawWidth * scale
        val scaledHeight = rawHeight * scale
        if (scaledWidth <= 0f || scaledHeight <= 0f) {
            return
        }
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = alpha
        val xPx = positionDp.x * metrics.density
        val yPx = positionDp.y * metrics.density
        val maxX = (metrics.widthPixels - scaledWidth).coerceAtLeast(0f)
        val maxY = (metrics.heightPixels - scaledHeight).coerceAtLeast(0f)
        view.x = xPx.coerceIn(0f, maxX)
        view.y = yPx.coerceIn(0f, maxY)
    }

    private fun resolveViewSize(view: View): Pair<Float, Float> {
        var width = view.width
        var height = view.height
        if (width <= 0 || height <= 0) {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(widthSpec, heightSpec)
            width = view.measuredWidth
            height = view.measuredHeight
        }
        return width.toFloat() to height.toFloat()
    }
}
