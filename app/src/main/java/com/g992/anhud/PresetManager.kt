package com.g992.anhud

import android.content.Context
import org.json.JSONObject
import java.io.File

object PresetManager {
    enum class Source {
        SYSTEM,
        USER
    }

    data class Preset(
        val id: String,
        val name: String,
        val source: Source,
        val resId: Int? = null,
        val file: File? = null
    )

    const val SYSTEM_BASE_ID = "system:base"
    const val SYSTEM_TOP_ID = "system:top"
    private const val USER_PREFIX = "user:"

    fun loadPresets(context: Context): List<Preset> {
        val result = mutableListOf(
            Preset(
                id = SYSTEM_BASE_ID,
                name = context.getString(R.string.preset_base_with_plane),
                source = Source.SYSTEM,
                resId = R.raw.preset_base_with_plane
            ),
            Preset(
                id = SYSTEM_TOP_ID,
                name = context.getString(R.string.preset_top_no_plane),
                source = Source.SYSTEM,
                resId = R.raw.preset_top_no_plane
            )
        )

        val userPresets = loadUserPresets(context)
        result.addAll(userPresets)
        return result
    }

    fun readPresetPayload(context: Context, preset: Preset): JSONObject? {
        val json = when (preset.source) {
            Source.SYSTEM -> {
                val resId = preset.resId ?: return null
                context.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            Source.USER -> {
                val file = preset.file ?: return null
                file.readText(Charsets.UTF_8)
            }
        }
        return runCatching { JSONObject(json) }.getOrNull()
    }

    fun saveUserPreset(context: Context, fileNameBase: String, payload: JSONObject): File? {
        val dir = userPresetDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        val file = File(dir, "$fileNameBase.json")
        file.writeText(payload.toString(2), Charsets.UTF_8)
        return file
    }

    fun userPresetDir(context: Context): File {
        val dir = context.getExternalFilesDir("presets") ?: File(context.filesDir, "presets")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun presetIdForFile(file: File): String = "$USER_PREFIX${file.name}"

    private fun loadUserPresets(context: Context): List<Preset> {
        val dir = userPresetDir(context)
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.toList()
            .orEmpty()
        return files
            .sortedBy { it.nameWithoutExtension.lowercase() }
            .map { file ->
                Preset(
                    id = presetIdForFile(file),
                    name = file.nameWithoutExtension,
                    source = Source.USER,
                    file = file
                )
            }
    }
}
