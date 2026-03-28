package com.g992.anhud

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ImageView
import com.caverock.androidsvg.SVG
import java.io.File
import java.util.Locale
import kotlin.math.max

internal data class TurnSignalCustomIcon(
    val uriString: String,
    val displayName: String,
    val baseDirection: TurnSignalBaseDirection
) {
    val uri: Uri
        get() = Uri.parse(uriString)
}

internal object TurnSignalCustomIconLoader {
    fun storeInAppStorage(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        baseDirection: TurnSignalBaseDirection
    ): TurnSignalCustomIcon? {
        if (isStoredIconUri(context, sourceUri)) {
            val drawable = loadDrawable(context, sourceUri, FALLBACK_TARGET_SIZE_PX) ?: return null
            if (drawable.intrinsicWidth == 0 && drawable.intrinsicHeight == 0) {
                return null
            }
            return TurnSignalCustomIcon(
                uriString = sourceUri.toString(),
                displayName = displayName,
                baseDirection = baseDirection
            )
        }
        val fileType = resolveFileType(context, sourceUri) ?: return null
        val targetFile = resolveStoredFile(context, fileType)
        val copied = runCatching {
            openInputStream(context, sourceUri)?.use { input ->
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }.getOrNull() != null
        if (!copied) {
            return null
        }
        val storedUri = Uri.fromFile(targetFile)
        if (loadDrawable(context, storedUri, FALLBACK_TARGET_SIZE_PX) == null) {
            targetFile.delete()
            return null
        }
        cleanupOtherStoredFiles(context, keep = fileType)
        return TurnSignalCustomIcon(
            uriString = storedUri.toString(),
            displayName = displayName,
            baseDirection = baseDirection
        )
    }

    fun clearStoredIcon(context: Context) {
        File(context.filesDir, STORAGE_DIR_NAME)
            .listFiles()
            .orEmpty()
            .forEach { it.delete() }
    }

    fun applyPair(
        context: Context,
        left: ImageView?,
        right: ImageView?,
        icon: TurnSignalCustomIcon?
    ): Boolean {
        if (icon == null) {
            return false
        }
        val targetSizePx = resolveTargetSizePx(context, left, right)
        val drawable = loadDrawable(context, icon.uri, targetSizePx) ?: return false
        val leftScale = if (icon.baseDirection == TurnSignalBaseDirection.LEFT) 1f else -1f
        val rightScale = -leftScale

        left?.apply {
            setImageDrawable(drawable.copy())
            scaleX = leftScale
        }
        right?.apply {
            setImageDrawable(drawable.copy())
            scaleX = rightScale
        }
        return true
    }

    fun canLoad(context: Context, uri: Uri): Boolean {
        return loadDrawable(context, uri, FALLBACK_TARGET_SIZE_PX) != null
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        val cursor = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
        }.getOrNull()
        cursor.useDisplayName()?.let { return it }
        val lastSegment = uri.lastPathSegment?.substringAfterLast('/')
        return lastSegment?.takeIf { it.isNotBlank() } ?: uri.toString()
    }

    private fun loadDrawable(context: Context, uri: Uri, targetSizePx: Int): Drawable? {
        val type = resolveFileType(context, uri) ?: return null
        return when (type) {
            FileType.PNG -> loadPngDrawable(context, uri, targetSizePx)
            FileType.SVG -> loadSvgDrawable(context, uri, targetSizePx)
        }
    }

    private fun loadPngDrawable(context: Context, uri: Uri, targetSizePx: Int): Drawable? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        runCatching {
            openInputStream(context, uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
        }.getOrNull()
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = resolveInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, targetSizePx)
        }
        val bitmap = runCatching {
            openInputStream(context, uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        }.getOrNull() ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun loadSvgDrawable(context: Context, uri: Uri, targetSizePx: Int): Drawable? {
        val svg = runCatching {
            openInputStream(context, uri)?.use { SVG.getFromInputStream(it) }
        }.getOrNull() ?: return null
        val safeSizePx = targetSizePx.coerceAtLeast(1)
        val picture = runCatching {
            svg.renderToPicture(safeSizePx, safeSizePx)
        }.getOrNull() ?: return null
        val bitmap = Bitmap.createBitmap(safeSizePx, safeSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPicture(picture)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun resolveFileType(context: Context, uri: Uri): FileType? {
        val mimeType = if (uri.scheme == SCHEME_CONTENT) {
            context.contentResolver.getType(uri)?.lowercase(Locale.US)
        } else {
            null
        }
        if (mimeType == MIME_TYPE_PNG) {
            return FileType.PNG
        }
        if (mimeType == MIME_TYPE_SVG) {
            return FileType.SVG
        }
        val name = resolveDisplayName(context, uri).lowercase(Locale.US)
        return when {
            name.endsWith(".png") -> FileType.PNG
            name.endsWith(".svg") -> FileType.SVG
            else -> null
        }
    }

    private fun openInputStream(context: Context, uri: Uri) = when (uri.scheme) {
        SCHEME_FILE -> uri.path?.let { File(it).inputStream() }
        else -> context.contentResolver.openInputStream(uri)
    }

    private fun resolveStoredFile(context: Context, fileType: FileType): File {
        val extension = when (fileType) {
            FileType.PNG -> "png"
            FileType.SVG -> "svg"
        }
        return File(File(context.filesDir, STORAGE_DIR_NAME), "$STORAGE_FILE_NAME.$extension")
    }

    private fun cleanupOtherStoredFiles(context: Context, keep: FileType) {
        FileType.entries
            .filter { it != keep }
            .map { resolveStoredFile(context, it) }
            .forEach { it.delete() }
    }

    private fun isStoredIconUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme != SCHEME_FILE) {
            return false
        }
        val sourcePath = uri.path ?: return false
        val storageDir = File(context.filesDir, STORAGE_DIR_NAME)
        return runCatching {
            File(sourcePath).canonicalFile.parentFile == storageDir.canonicalFile
        }.getOrDefault(false)
    }

    private fun resolveTargetSizePx(context: Context, left: ImageView?, right: ImageView?): Int {
        val layoutWidth = listOf(
            left?.layoutParams?.width ?: 0,
            right?.layoutParams?.width ?: 0,
            left?.width ?: 0,
            right?.width ?: 0
        ).maxOrNull() ?: 0
        val fallback = (OverlayPrefs.TURN_SIGNALS_ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()
        return max(layoutWidth, fallback.coerceAtLeast(FALLBACK_TARGET_SIZE_PX))
    }

    private fun resolveInSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
        var sampleSize = 1
        if (width <= targetSizePx && height <= targetSizePx) {
            return sampleSize
        }
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sampleSize >= targetSizePx && halfHeight / sampleSize >= targetSizePx) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun Drawable.copy(): Drawable {
        return constantState?.newDrawable()?.mutate() ?: mutate()
    }

    private fun Cursor?.useDisplayName(): String? {
        if (this == null) {
            return null
        }
        use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) {
                return null
            }
            return cursor.getString(index)?.takeIf { it.isNotBlank() }
        }
    }

    private enum class FileType {
        PNG,
        SVG
    }

    private const val MIME_TYPE_PNG = "image/png"
    private const val MIME_TYPE_SVG = "image/svg+xml"
    private const val FALLBACK_TARGET_SIZE_PX = 96
    private const val SCHEME_CONTENT = "content"
    private const val SCHEME_FILE = "file"
    private const val STORAGE_DIR_NAME = "turn_signals"
    private const val STORAGE_FILE_NAME = "custom_icon"
}
