package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.util.Base64

data class ImportedCustomBlockIcon(
    val base64: String,
    val previewBitmap: Bitmap,
    val byteCount: Int,
    val width: Int,
    val height: Int
)

object CustomBlockIconStore {
    fun importPng(context: Context, uri: Uri): ImportedCustomBlockIcon {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= CustomBlocksContract.MAX_ICON_BYTES) {
                    "PNG больше ${CustomBlocksContract.MAX_ICON_BYTES / 1024} КБ"
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("Не удалось прочитать выбранный файл")

        require(bytes.hasPngSignature()) { "Выбранный файл не является PNG" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..CustomBlocksContract.MAX_ICON_DIMENSION_PX) {
            "Ширина PNG должна быть до ${CustomBlocksContract.MAX_ICON_DIMENSION_PX}px"
        }
        require(bounds.outHeight in 1..CustomBlocksContract.MAX_ICON_DIMENSION_PX) {
            "Высота PNG должна быть до ${CustomBlocksContract.MAX_ICON_DIMENSION_PX}px"
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("PNG не декодируется")
        return ImportedCustomBlockIcon(
            base64 = Base64.getEncoder().encodeToString(bytes),
            previewBitmap = bitmap,
            byteCount = bytes.size,
            width = bounds.outWidth,
            height = bounds.outHeight
        )
    }

    fun decode(definition: CustomBlockDefinition): Bitmap {
        val bytes = runCatching { Base64.getDecoder().decode(definition.iconBase64) }
            .getOrElse { throw IllegalArgumentException("Некорректная PNG-иконка Base64") }
        require(bytes.size <= CustomBlocksContract.MAX_ICON_BYTES && bytes.hasPngSignature()) {
            "Некорректная PNG-иконка"
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("PNG не декодируется")
    }

    fun materialize(repository: CustomBlockRepository, definition: CustomBlockDefinition) {
        val bytes = Base64.getDecoder().decode(definition.iconBase64)
        val directory = repository.blockDirectory(definition.id)
        check(directory.exists() || directory.mkdirs()) { "Не удалось создать каталог иконки" }
        val atomicFile = AtomicFile(repository.iconFile(definition.id))
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }
}
