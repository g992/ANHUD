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
    private val styles = listOf(
        TurnSignalStyle(1, R.drawable.turn_signal_style_1, TurnSignalBaseDirection.LEFT),
        TurnSignalStyle(2, R.drawable.turn_signal_style_12, TurnSignalBaseDirection.LEFT),
        TurnSignalStyle(3, R.drawable.turn_signal_style_2, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(4, R.drawable.turn_signal_style_3, TurnSignalBaseDirection.LEFT),
        TurnSignalStyle(5, R.drawable.turn_signal_style_4, TurnSignalBaseDirection.LEFT),
        TurnSignalStyle(6, R.drawable.turn_signal_style_5, TurnSignalBaseDirection.LEFT),
        TurnSignalStyle(7, R.drawable.turn_signal_style_6, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(8, R.drawable.turn_signal_style_7, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(9, R.drawable.turn_signal_style_8, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(10, R.drawable.turn_signal_style_9, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(11, R.drawable.turn_signal_style_10, TurnSignalBaseDirection.RIGHT),
        TurnSignalStyle(12, R.drawable.turn_signal_style_11, TurnSignalBaseDirection.RIGHT)
    )

    fun all(): List<TurnSignalStyle> = styles

    fun sanitize(styleId: Int): Int {
        return styles.firstOrNull { it.id == styleId }?.id ?: OverlayPrefs.TURN_SIGNALS_ICON_STYLE_DEFAULT
    }

    fun resolve(styleId: Int): TurnSignalStyle {
        val sanitizedId = sanitize(styleId)
        return styles.first { it.id == sanitizedId }
    }

    fun label(context: Context, styleId: Int): String {
        return context.getString(R.string.turn_signal_icon_option_value, sanitize(styleId))
    }

    fun applyPair(left: ImageView?, right: ImageView?, styleId: Int) {
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
