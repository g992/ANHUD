package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class CustomBlockCoordinator(
    context: Context,
    private val onRenderStatesChanged: (CustomBlocksDocument, Map<String, CustomBlockRenderState>) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val repository = CustomBlockRepository(appContext)
    private val runtime = CustomScriptRuntime(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val dataHub = CustomDataHub(appContext, ::evaluateBlocks)
    private val generations = mutableMapOf<String, Long>()
    private val refreshRunnables = mutableMapOf<String, Runnable>()
    private var document = CustomBlocksDocument()
    private var states = emptyMap<String, CustomBlockRenderState>()
    private var started = false

    private val definitionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reload()
        }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            appContext,
            definitionsReceiver,
            IntentFilter(CustomBlocksContract.DEFINITIONS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        dataHub.start()
        reload()
    }

    fun reload() {
        document = repository.load()
        cancelRemovedRefreshes()
        val dependencyErrors = dataHub.configure(document)
        states = states.filterKeys { blockId -> document.blocks.any { it.id == blockId } }
        document.blocks.forEach { block ->
            when {
                !document.enabled || !block.enabled -> setState(
                    CustomBlockRenderState(blockId = block.id, visible = false)
                )
                dependencyErrors[block.id] != null -> setError(block, dependencyErrors.getValue(block.id))
            }
        }
        publish()
        if (document.enabled) {
            evaluateBlocks(
                document.blocks.filter { it.enabled && dependencyErrors[it.id] == null }
                    .mapTo(linkedSetOf()) { it.id }
            )
        }
    }

    override fun close() {
        if (!started) return
        started = false
        runCatching { appContext.unregisterReceiver(definitionsReceiver) }
        refreshRunnables.values.forEach(handler::removeCallbacks)
        refreshRunnables.clear()
        dataHub.stop()
        runtime.close()
    }

    private fun evaluateBlocks(blockIds: Set<String>) {
        if (!document.enabled) return
        blockIds.forEach { blockId ->
            val block = document.blocks.firstOrNull { it.id == blockId && it.enabled } ?: return@forEach
            evaluate(block)
        }
    }

    private fun evaluate(block: CustomBlockDefinition) {
        refreshRunnables.remove(block.id)?.let(handler::removeCallbacks)
        val generation = (generations[block.id] ?: 0L) + 1L
        generations[block.id] = generation
        runtime.evaluate(block.script, dataHub.environment(block.id)) { result ->
            if (!started || generations[block.id] != generation) return@evaluate
            val currentBlock = document.blocks.firstOrNull { it.id == block.id }
            if (currentBlock == null || !document.enabled || !currentBlock.enabled) return@evaluate
            var changed = false
            result.onSuccess { evaluation ->
                changed = setState(
                    CustomBlockRenderState(
                        blockId = block.id,
                        visible = evaluation.visible,
                        text = evaluation.text,
                        renderedAt = System.currentTimeMillis()
                    )
                )
                scheduleRefresh(block.id, evaluation.nextRefreshAt)
            }.onFailure { error -> changed = setError(block, error) }
            if (changed) publish()
        }
    }

    private fun scheduleRefresh(blockId: String, nextRefreshAt: Long?) {
        val target = nextRefreshAt ?: return
        val runnable = Runnable { evaluateBlocks(setOf(blockId)) }
        refreshRunnables[blockId] = runnable
        handler.postDelayed(runnable, (target - System.currentTimeMillis()).coerceAtLeast(1L))
    }

    private fun setError(block: CustomBlockDefinition, error: Throwable): Boolean {
        return setState(
            CustomBlockRenderState(
                blockId = block.id,
                visible = false,
                error = error.message ?: error::class.java.simpleName,
                renderedAt = System.currentTimeMillis()
            )
        )
    }

    private fun setState(state: CustomBlockRenderState): Boolean {
        val normalized = state.normalized()
        if (normalized.hasSameRenderContent(states[normalized.blockId])) return false
        states = states + (normalized.blockId to normalized)
        CustomBlockStatusStore.put(normalized)
        return true
    }

    private fun publish() {
        onRenderStatesChanged(document, states)
    }

    private fun cancelRemovedRefreshes() {
        val existingIds = document.blocks.mapTo(hashSetOf()) { it.id }
        refreshRunnables.keys.filterNot(existingIds::contains).forEach { blockId ->
            refreshRunnables.remove(blockId)?.let(handler::removeCallbacks)
            generations.remove(blockId)
        }
    }
}
