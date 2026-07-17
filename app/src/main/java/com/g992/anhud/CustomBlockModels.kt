package com.g992.anhud

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

object CustomBlocksContract {
    const val SCHEMA_VERSION = 1
    const val MAX_BLOCKS = 12
    const val MAX_SCRIPT_LENGTH = 1_024
    const val MAX_RENDER_TEXT_LENGTH = 256
    const val MAX_ICON_BYTES = 512 * 1024
    const val MAX_ICON_DIMENSION_PX = 1_024
    const val SPEED_SENSOR_ID = 1_055_232
    const val DEFINITIONS_CHANGED_ACTION = "com.g992.anhud.CUSTOM_BLOCKS_CHANGED"

    fun newBlockId(): String = UUID.randomUUID().toString()
}

data class CustomBlockDefinition(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val script: String,
    val iconBase64: String,
    val iconFileName: String = "icon.png",
    val xDp: Float = 16f,
    val yDp: Float = 16f,
    val scale: Float = 1f,
    val alpha: Float = 1f
) {
    fun normalized(): CustomBlockDefinition {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid block ID: $id" }
        val normalizedName = name.trim().take(80)
        require(normalizedName.isNotEmpty()) { "Название не должно быть пустым" }
        val normalizedScript = CustomScriptPolicy.requireValidShape(script)
        val iconBytes = runCatching { Base64.getDecoder().decode(iconBase64) }
            .getOrElse { throw IllegalArgumentException("Некорректная PNG-иконка Base64") }
        require(iconBytes.size in 1..CustomBlocksContract.MAX_ICON_BYTES) {
            "Размер PNG должен быть не больше ${CustomBlocksContract.MAX_ICON_BYTES / 1024} КБ"
        }
        require(iconBytes.hasPngSignature()) { "Иконка должна быть PNG" }
        val dimensions = iconBytes.pngDimensions()
            ?: throw IllegalArgumentException("PNG не содержит корректный IHDR")
        require(dimensions.first in 1..CustomBlocksContract.MAX_ICON_DIMENSION_PX) {
            "Ширина PNG должна быть до ${CustomBlocksContract.MAX_ICON_DIMENSION_PX}px"
        }
        require(dimensions.second in 1..CustomBlocksContract.MAX_ICON_DIMENSION_PX) {
            "Высота PNG должна быть до ${CustomBlocksContract.MAX_ICON_DIMENSION_PX}px"
        }
        return copy(
            name = normalizedName,
            script = normalizedScript,
            iconFileName = "icon.png",
            xDp = xDp.finiteOr(16f).coerceAtLeast(0f),
            yDp = yDp.finiteOr(16f).coerceAtLeast(0f),
            scale = scale.finiteOr(1f).coerceIn(0.25f, 3f),
            alpha = alpha.finiteOr(1f).coerceIn(0f, 1f)
        )
    }
}

data class CustomBlocksDocument(
    val schemaVersion: Int = CustomBlocksContract.SCHEMA_VERSION,
    val menuVisible: Boolean = false,
    val enabled: Boolean = false,
    val blocks: List<CustomBlockDefinition> = emptyList()
) {
    fun normalized(): CustomBlocksDocument {
        require(schemaVersion == CustomBlocksContract.SCHEMA_VERSION) {
            "Unsupported custom blocks schema version: $schemaVersion"
        }
        require(blocks.size <= CustomBlocksContract.MAX_BLOCKS) {
            "Maximum ${CustomBlocksContract.MAX_BLOCKS} custom blocks"
        }
        val ids = HashSet<String>()
        val normalizedBlocks = blocks.map { block ->
            val normalized = block.normalized()
            require(ids.add(normalized.id)) { "Duplicate block ID: ${normalized.id}" }
            normalized
        }
        return copy(blocks = normalizedBlocks)
    }
}

data class CustomBlockRenderState(
    val blockId: String,
    val visible: Boolean = false,
    val text: String = "",
    val error: String? = null,
    val renderedAt: Long = 0L
) {
    fun normalized(): CustomBlockRenderState = copy(
        text = text.take(CustomBlocksContract.MAX_RENDER_TEXT_LENGTH),
        error = error?.trim()?.take(CustomBlocksContract.MAX_RENDER_TEXT_LENGTH)?.ifBlank { null }
    )

    fun hasSameRenderContent(other: CustomBlockRenderState?): Boolean =
        other != null && visible == other.visible && text == other.text && error == other.error
}

internal object CustomBlockJsonCodec {
    fun encode(document: CustomBlocksDocument): String {
        val normalized = document.normalized()
        return JSONObject().apply {
            put("schemaVersion", normalized.schemaVersion)
            put("menuVisible", normalized.menuVisible)
            put("enabled", normalized.enabled)
            put("blocks", JSONArray().apply {
                normalized.blocks.forEach { block ->
                    put(JSONObject().apply {
                        put("id", block.id)
                        put("name", block.name)
                        put("enabled", block.enabled)
                        put("script", block.script)
                        put("iconBase64", block.iconBase64)
                        put("iconFileName", block.iconFileName)
                        put("xDp", block.xDp.toDouble())
                        put("yDp", block.yDp.toDouble())
                        put("scale", block.scale.toDouble())
                        put("alpha", block.alpha.toDouble())
                    })
                }
            })
        }.toString()
    }

    fun decode(json: String): CustomBlocksDocument {
        val root = JSONObject(json)
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(schemaVersion == CustomBlocksContract.SCHEMA_VERSION) {
            "Unsupported custom blocks schema version: $schemaVersion"
        }
        val blocksJson = root.optJSONArray("blocks") ?: JSONArray()
        val blocks = buildList {
            for (index in 0 until blocksJson.length()) {
                val item = blocksJson.getJSONObject(index)
                add(
                    CustomBlockDefinition(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        enabled = item.optBoolean("enabled", false),
                        script = item.getString("script"),
                        iconBase64 = item.getString("iconBase64"),
                        iconFileName = item.optString("iconFileName", "icon.png"),
                        xDp = item.optDouble("xDp", 16.0).toFloat(),
                        yDp = item.optDouble("yDp", 16.0).toFloat(),
                        scale = item.optDouble("scale", 1.0).toFloat(),
                        alpha = item.optDouble("alpha", 1.0).toFloat()
                    )
                )
            }
        }
        return CustomBlocksDocument(
            schemaVersion = schemaVersion,
            menuVisible = root.optBoolean("menuVisible", false),
            enabled = root.optBoolean("enabled", false),
            blocks = blocks
        ).normalized()
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

internal fun ByteArray.hasPngSignature(): Boolean {
    if (size < 8) return false
    val signature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    return signature.indices.all { index -> this[index].toInt() and 0xFF == signature[index] }
}

internal fun ByteArray.pngDimensions(): Pair<Int, Int>? {
    if (size < 24 || !hasPngSignature()) return null
    if (this[12].toInt().toChar() != 'I' || this[13].toInt().toChar() != 'H' ||
        this[14].toInt().toChar() != 'D' || this[15].toInt().toChar() != 'R'
    ) return null
    fun readInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
    return readInt(16) to readInt(20)
}
