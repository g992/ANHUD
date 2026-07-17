package com.g992.anhud

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets

class CustomBlockRepository(context: Context) {
    private val appContext = context.applicationContext
    private val rootDirectory = File(appContext.filesDir, "custom_blocks")
    private val atomicFile = AtomicFile(File(rootDirectory, "definitions.json"))

    fun load(): CustomBlocksDocument = synchronized(lock) {
        val file = atomicFile.baseFile
        if (!file.isFile) return@synchronized CustomBlocksDocument()
        runCatching {
            atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                CustomBlockJsonCodec.decode(reader.readText())
            }
        }.onFailure { error ->
            UiLogStore.append(
                LogCategory.SYSTEM,
                "Пользовательский вывод: ошибка чтения настроек: ${error.message}"
            )
        }.getOrDefault(CustomBlocksDocument())
    }

    fun update(transform: (CustomBlocksDocument) -> CustomBlocksDocument): CustomBlocksDocument {
        val updated = synchronized(lock) {
            val normalized = transform(load()).normalized()
            writeLocked(normalized)
            normalized
        }
        notifyChanged()
        return updated
    }

    fun upsert(definition: CustomBlockDefinition): CustomBlocksDocument {
        val normalized = definition.normalized()
        return update { current ->
            val existingIndex = current.blocks.indexOfFirst { it.id == normalized.id }
            val blocks = if (existingIndex >= 0) {
                current.blocks.toMutableList().apply { set(existingIndex, normalized) }
            } else {
                require(current.blocks.size < CustomBlocksContract.MAX_BLOCKS) {
                    "Можно создать не более ${CustomBlocksContract.MAX_BLOCKS} субблоков"
                }
                current.blocks + normalized
            }
            current.copy(blocks = blocks)
        }
    }

    fun delete(blockId: String): CustomBlocksDocument {
        val updated = update { current ->
            current.copy(blocks = current.blocks.filterNot { it.id == blockId })
        }
        deleteBlockFiles(blockId)
        CustomBlockStatusStore.clear(blockId)
        return updated
    }

    fun iconFile(blockId: String): File = File(blockDirectory(blockId), "icon.png")

    fun blockDirectory(blockId: String): File {
        require(runCatching { java.util.UUID.fromString(blockId) }.isSuccess) { "Invalid block ID" }
        return File(rootDirectory, blockId)
    }

    private fun writeLocked(document: CustomBlocksDocument) {
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Не удалось создать каталог пользовательских блоков"
        }
        val stream = atomicFile.startWrite()
        try {
            stream.write(CustomBlockJsonCodec.encode(document).toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun deleteBlockFiles(blockId: String) {
        val directory = runCatching { blockDirectory(blockId) }.getOrNull() ?: return
        directory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        directory.delete()
    }

    private fun notifyChanged() {
        appContext.sendBroadcast(
            Intent(CustomBlocksContract.DEFINITIONS_CHANGED_ACTION).setPackage(appContext.packageName)
        )
    }

    private companion object {
        val lock = Any()
    }
}

object CustomBlockStatusStore {
    private val lock = Any()
    private val states = mutableMapOf<String, CustomBlockRenderState>()
    private val listeners = mutableSetOf<Listener>()

    fun interface Listener {
        fun onCustomBlockStatusChanged(blockId: String)
    }

    fun get(blockId: String): CustomBlockRenderState? = synchronized(lock) { states[blockId] }

    fun put(state: CustomBlockRenderState) {
        val normalized = state.normalized()
        val changed = synchronized(lock) {
            if (normalized.hasSameRenderContent(states[normalized.blockId])) false else {
                states[normalized.blockId] = normalized
                true
            }
        }
        if (changed) notifyListeners(normalized.blockId)
    }

    fun clear(blockId: String) {
        val changed = synchronized(lock) { states.remove(blockId) != null }
        if (changed) notifyListeners(blockId)
    }

    fun register(listener: Listener) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun unregister(listener: Listener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun notifyListeners(blockId: String) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { it.onCustomBlockStatusChanged(blockId) }
    }
}
