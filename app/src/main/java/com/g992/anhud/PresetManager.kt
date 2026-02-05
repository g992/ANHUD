package com.g992.anhud

import android.content.Context
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        val file: File? = null,
        val uri: Uri? = null
    )

    const val SYSTEM_BASE_ID = "system:base"
    const val SYSTEM_TOP_ID = "system:top"
    private const val USER_PREFIX = "user:"
    private const val PRESET_DIR_NAME = "ANHUD"
    private const val PRESET_EXTENSION = ".json"
    private const val PRESET_MIME = "application/json"

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

        migrateLegacyPresets(context)
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
                val file = preset.file
                if (file != null) {
                    file.readText(Charsets.UTF_8)
                } else {
                    val uri = preset.uri ?: return null
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: return null
                }
            }
        }
        return runCatching { JSONObject(json) }.getOrNull()
    }

    fun saveUserPreset(context: Context, fileNameBase: String, payload: JSONObject): String? {
        val displayName = "$fileNameBase$PRESET_EXTENSION"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveUserPresetMediaStore(context, displayName, payload)
        } else {
            saveUserPresetLegacy(context, displayName, payload)
        }
    }

    fun userPresetDir(context: Context): File {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, PRESET_DIR_NAME)
    }

    fun presetIdForFile(file: File): String = presetIdForDisplayName(file.name)

    fun presetIdForDisplayName(displayName: String): String = "$USER_PREFIX$displayName"

    private fun loadUserPresets(context: Context): List<Preset> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadUserPresetsMediaStore(context)
        } else {
            loadUserPresetsLegacy(context)
        }
    }

    private fun saveUserPresetLegacy(context: Context, displayName: String, payload: JSONObject): String? {
        val dir = userPresetDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        val file = File(dir, displayName)
        file.writeText(payload.toString(2), Charsets.UTF_8)
        return presetIdForFile(file)
    }

    private fun saveUserPresetMediaStore(
        context: Context,
        displayName: String,
        payload: JSONObject
    ): String? {
        val resolver = context.contentResolver
        val relativePath = presetRelativePath()
        val existingUri = findPresetUri(context, displayName)
        val uri = existingUri ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, PRESET_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: return null

        resolver.openOutputStream(uri, "w")?.use { output ->
            output.write(payload.toString(2).toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }

        return presetIdForDisplayName(displayName)
    }

    private fun loadUserPresetsLegacy(context: Context): List<Preset> {
        val dir = userPresetDir(context)
        if (!dir.exists()) {
            dir.mkdirs()
        }
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

    private fun loadUserPresetsMediaStore(context: Context): List<Preset> {
        val presets = mutableListOf<Preset>()
        val resolver = context.contentResolver
        val relativePath = presetRelativePath()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(relativePath)
        val sortOrder = "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex) ?: continue
                if (!displayName.endsWith(PRESET_EXTENSION, ignoreCase = true)) {
                    continue
                }
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                presets.add(
                    Preset(
                        id = presetIdForDisplayName(displayName),
                        name = displayNameToPresetName(displayName),
                        source = Source.USER,
                        uri = uri
                    )
                )
            }
        }
        return presets
            .sortedBy { it.name.lowercase() }
    }

    private fun migrateLegacyPresets(context: Context) {
        val legacyDirs = listOfNotNull(
            context.getExternalFilesDir("presets"),
            File(context.filesDir, "presets")
        ).distinct()
        if (legacyDirs.isEmpty()) return
        legacyDirs.forEach { dir ->
            val files = dir.listFiles { file ->
                file.isFile && file.extension.equals("json", ignoreCase = true)
            } ?: return@forEach
            files.forEach { file ->
                val displayName = file.name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (findPresetUri(context, displayName) != null) return@forEach
                } else {
                    val targetFile = File(userPresetDir(context), displayName)
                    if (targetFile.exists()) return@forEach
                }
                val payload = runCatching {
                    JSONObject(file.readText(Charsets.UTF_8))
                }.getOrNull() ?: return@forEach
                saveUserPreset(context, file.nameWithoutExtension, payload)
            }
        }
    }

    private fun displayNameToPresetName(displayName: String): String {
        return if (displayName.endsWith(PRESET_EXTENSION, ignoreCase = true)) {
            displayName.dropLast(PRESET_EXTENSION.length)
        } else {
            displayName
        }
    }

    private fun presetRelativePath(): String {
        return "${Environment.DIRECTORY_DOWNLOADS}/$PRESET_DIR_NAME/"
    }

    private fun findPresetUri(context: Context, displayName: String): Uri? {
        val resolver = context.contentResolver
        val relativePath = presetRelativePath()
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(relativePath, displayName)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }
}
