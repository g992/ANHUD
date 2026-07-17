package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object CustomBlockViewFactory {
    class ViewHolder internal constructor(
        val root: LinearLayout,
        private val icon: ImageView,
        private val text: TextView
    ) {
        private var lastSignature: BindSignature? = null

        fun bind(definition: CustomBlockDefinition, state: CustomBlockRenderState) {
            val signature = BindSignature(
                iconKey = iconKey(definition),
                visible = definition.enabled && state.visible && state.error == null,
                text = state.text
            )
            if (signature == lastSignature) return
            lastSignature = signature
            if (signature.visible) {
                val bitmap = runCatching { loadBitmap(definition) }.getOrElse {
                    root.visibility = View.GONE
                    return
                }
                icon.setImageBitmap(bitmap)
                text.text = signature.text
                root.visibility = View.VISIBLE
            } else {
                root.visibility = View.GONE
            }
        }

        fun bindPreview(definition: CustomBlockDefinition, textValue: String, visible: Boolean = true) {
            val state = CustomBlockRenderState(
                blockId = definition.id,
                visible = visible,
                text = textValue,
                renderedAt = System.currentTimeMillis()
            )
            bind(definition.copy(enabled = true), state)
        }
    }

    fun create(context: Context, parent: ViewGroup? = null): ViewHolder {
        val density = context.resources.displayMetrics.density
        val iconSize = (40f * density).toInt().coerceAtLeast(1)
        val margin = (8f * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            pivotX = 0f
            pivotY = 0f
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = margin }
        }
        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(icon)
        root.addView(text)
        parent?.addView(root)
        return ViewHolder(root, icon, text)
    }

    private data class BindSignature(
        val iconKey: String,
        val visible: Boolean,
        val text: String
    )

    private fun iconKey(definition: CustomBlockDefinition): String =
        "${definition.id}:${definition.iconBase64.hashCode()}"

    private fun loadBitmap(definition: CustomBlockDefinition): Bitmap {
        val key = iconKey(definition)
        synchronized(bitmapCache) {
            bitmapCache.get(key)?.takeUnless { it.isRecycled }?.let { return it }
        }
        val decoded = CustomBlockIconStore.decode(definition)
        synchronized(bitmapCache) { bitmapCache.put(key, decoded) }
        return decoded
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
}
