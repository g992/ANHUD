package com.g992.anhud

import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes

internal enum class TurnSignalBaseDirection {
    LEFT,
    RIGHT
}

internal data class TurnSignalStyle(
    val id: Int,
    @DrawableRes val drawableRes: Int,
    val baseDirection: TurnSignalBaseDirection
)

internal object TurnSignalIcons {
    const val CUSTOM_STYLE_ID = 1000

    private val styles = listOf(
        TurnSignalStyle(1, R.drawable.turn_signal_style_1, TurnSignalBaseDirection.LEFT),
        TurnSignalStyle(6, R.drawable.turn_signal_style_5, TurnSignalBaseDirection.LEFT),
        TurnSignalStyle(7, R.drawable.turn_signal_style_6, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(8, R.drawable.turn_signal_style_7, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(9, R.drawable.turn_signal_style_8, TurnSignalBaseDirection.RIGHT)
    )

    fun all(): List<TurnSignalStyle> = styles

    fun displayNumber(styleId: Int): Int {
        val resolvedId = sanitize(styleId)
        return styles.indexOfFirst { it.id == resolvedId }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: 1
    }

    fun sanitize(styleId: Int): Int {
        return when {
            styleId == CUSTOM_STYLE_ID -> CUSTOM_STYLE_ID
            else -> styles.firstOrNull { it.id == styleId }?.id ?: OverlayPrefs.TURN_SIGNALS_ICON_STYLE_DEFAULT
        }
    }

    fun resolve(styleId: Int): TurnSignalStyle {
        val sanitizedId = sanitize(styleId)
        if (isCustom(sanitizedId)) {
            return styles.first { it.id == OverlayPrefs.TURN_SIGNALS_ICON_STYLE_DEFAULT }
        }
        return styles.first { it.id == sanitizedId }
    }

    fun label(context: Context, styleId: Int): String {
        if (isCustom(styleId)) {
            return context.getString(R.string.turn_signal_icon_option_value_custom)
        }
        return context.getString(R.string.turn_signal_icon_option_value, displayNumber(styleId))
    }

    fun summary(context: Context, styleId: Int): String {
        if (!isCustom(styleId)) {
            return label(context, styleId)
        }
        val customIcon = OverlayPrefs.turnSignalsCustomIcon(context)
        if (customIcon == null) {
            return context.getString(R.string.turn_signal_icon_custom_missing)
        }
        val directionLabel = when (customIcon.baseDirection) {
            TurnSignalBaseDirection.LEFT -> context.getString(R.string.turn_signal_icon_direction_left)
            TurnSignalBaseDirection.RIGHT -> context.getString(R.string.turn_signal_icon_direction_right)
        }
        return context.getString(
            R.string.turn_signal_icon_custom_summary,
            customIcon.displayName,
            directionLabel
        )
    }

    fun isCustom(styleId: Int): Boolean = styleId == CUSTOM_STYLE_ID

    fun applyPair(context: Context, left: ImageView?, right: ImageView?, styleId: Int) {
        if (isCustom(styleId)) {
            val applied = TurnSignalCustomIconLoader.applyPair(
                context = context,
                left = left,
                right = right,
                icon = OverlayPrefs.turnSignalsCustomIcon(context)
            )
            if (applied) {
                return
            }
        }
        val style = resolve(styleId)
        val leftScale = if (style.baseDirection == TurnSignalBaseDirection.LEFT) 1f else -1f
        val rightScale = -leftScale

        left?.apply {
            setImageResource(style.drawableRes)
            scaleX = leftScale
        }
        right?.apply {
            setImageResource(style.drawableRes)
            scaleX = rightScale
        }
    }
}
