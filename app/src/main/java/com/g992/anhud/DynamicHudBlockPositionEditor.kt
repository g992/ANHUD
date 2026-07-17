package com.g992.anhud

import android.graphics.Color
import android.graphics.PointF
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

data class DynamicHudBlockPositionConfig(
    val blockId: String,
    val title: String,
    val xDp: Float,
    val yDp: Float,
    val scale: Float,
    val alpha: Float,
    val containerSizeDp: PointF,
    val previewFactory: (FrameLayout) -> View
)

data class DynamicHudBlockPositionResult(
    val blockId: String,
    val xDp: Float,
    val yDp: Float,
    val scale: Float,
    val alpha: Float
)

fun MainActivity.showDynamicHudBlockPositionEditor(
    config: DynamicHudBlockPositionConfig,
    onSave: (DynamicHudBlockPositionResult) -> Unit
) {
    val previewWidthDp = 320
    val previewHeightDp = 180
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dynamicDp(20), dynamicDp(8), dynamicDp(20), dynamicDp(8))
    }
    val preview = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(dynamicDp(previewWidthDp), dynamicDp(previewHeightDp))
    }
    val previewView = config.previewFactory(preview)
    val xLabel = TextView(this).apply { setTextColor(Color.WHITE) }
    val xSeek = SeekBar(this).apply {
        max = config.containerSizeDp.x.roundToInt().coerceAtLeast(1)
        progress = config.xDp.roundToInt().coerceIn(0, max)
    }
    val yLabel = TextView(this).apply { setTextColor(Color.WHITE) }
    val ySeek = SeekBar(this).apply {
        max = config.containerSizeDp.y.roundToInt().coerceAtLeast(1)
        progress = config.yDp.roundToInt().coerceIn(0, max)
    }
    val scaleLabel = TextView(this).apply { setTextColor(Color.WHITE) }
    val scaleSeek = SeekBar(this).apply {
        max = 275
        progress = ((config.scale * 100).roundToInt() - 25).coerceIn(0, max)
    }
    val alphaLabel = TextView(this).apply { setTextColor(Color.WHITE) }
    val alphaSeek = SeekBar(this).apply {
        max = 100
        progress = (config.alpha * 100).roundToInt().coerceIn(0, max)
    }

    fun updatePreview() {
        val scale = (scaleSeek.progress + 25) / 100f
        xLabel.text = getString(R.string.custom_block_position_x, xSeek.progress)
        yLabel.text = getString(R.string.custom_block_position_y, ySeek.progress)
        scaleLabel.text = getString(R.string.custom_block_scale, (scale * 100).roundToInt())
        alphaLabel.text = getString(R.string.custom_block_alpha, alphaSeek.progress)
        previewView.scaleX = scale
        previewView.scaleY = scale
        previewView.alpha = alphaSeek.progress / 100f
        previewView.x = (xSeek.progress / config.containerSizeDp.x.coerceAtLeast(1f)) * dynamicDp(previewWidthDp)
        previewView.y = (ySeek.progress / config.containerSizeDp.y.coerceAtLeast(1f)) * dynamicDp(previewHeightDp)
    }

    val listener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updatePreview()
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
    listOf(xSeek, ySeek, scaleSeek, alphaSeek).forEach { it.setOnSeekBarChangeListener(listener) }
    root.addView(preview)
    root.addView(xLabel)
    root.addView(xSeek)
    root.addView(yLabel)
    root.addView(ySeek)
    root.addView(scaleLabel)
    root.addView(scaleSeek)
    root.addView(alphaLabel)
    root.addView(alphaSeek)
    updatePreview()

    AlertDialog.Builder(this)
        .setTitle(config.title)
        .setView(root)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            onSave(
                DynamicHudBlockPositionResult(
                    blockId = config.blockId,
                    xDp = xSeek.progress.toFloat(),
                    yDp = ySeek.progress.toFloat(),
                    scale = (scaleSeek.progress + 25) / 100f,
                    alpha = alphaSeek.progress / 100f
                )
            )
        }
        .show()
}

private fun MainActivity.dynamicDp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()
