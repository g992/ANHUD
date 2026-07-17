package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class CustomDataHub(
    context: Context,
    private val onSourcesChanged: (Set<String>) -> Unit
) : NavigationHudStore.Listener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var dependenciesByBlock = emptyMap<String, Set<CustomSourceKey>>()
    private var blocksBySource = emptyMap<CustomSourceKey, Set<String>>()
    private val intentValues = mutableMapOf<Pair<String, String>, CustomDataValue>()
    private var speedValue: CustomDataValue? = null
    private var sensorValue: CustomDataValue? = null
    private var sensorSubscription: AutoCloseable? = null
    private var receiverRegistered = false
    private var lastRegisteredActions = emptySet<String>()
    private var pendingSpeedBlocks = linkedSetOf<String>()
    private var started = false

    private val speedDispatch = Runnable {
        val blocks = synchronized(lock) {
            pendingSpeedBlocks.toSet().also { pendingSpeedBlocks.clear() }
        }
        if (blocks.isNotEmpty()) onSourcesChanged(blocks)
    }

    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val receivedIntent = intent ?: return
            val action = receivedIntent.action ?: return
            val sourceEntries = synchronized(lock) {
                blocksBySource.keys.filterIsInstance<CustomSourceKey.IntentExtra>()
                    .filter { it.action == action }
            }
            if (sourceEntries.isEmpty()) return
            val now = System.currentTimeMillis()
            val affected = linkedSetOf<String>()
            synchronized(lock) {
                sourceEntries.forEach { source ->
                    val value = IntentExtrasSanitizer.read(receivedIntent, source.extraName)
                    if (value != null) {
                        intentValues[source.action to source.extraName] = CustomDataValue(value, now)
                        affected.addAll(blocksBySource[source].orEmpty())
                    }
                }
            }
            if (affected.isNotEmpty()) onSourcesChanged(affected)
        }
    }

    fun start() {
        if (started) return
        started = true
        NavigationHudStore.registerListener(this)
    }

    fun stop() {
        if (!started) return
        started = false
        NavigationHudStore.unregisterListener(this)
        mainHandler.removeCallbacks(speedDispatch)
        unregisterIntentReceiver()
        sensorSubscription?.close()
        sensorSubscription = null
    }

    fun configure(document: CustomBlocksDocument): Map<String, Throwable> {
        val errors = linkedMapOf<String, Throwable>()
        val dependencies = if (!document.enabled) {
            emptyMap()
        } else {
            document.blocks.filter { it.enabled }.associate { block ->
                block.id to runCatching { CustomScriptPolicy.dependencies(block.script) }
                    .onFailure { errors[block.id] = it }
                    .getOrDefault(emptySet())
            }
        }
        synchronized(lock) {
            dependenciesByBlock = dependencies
            blocksBySource = CustomDependencyIndex.build(dependencies)
            pendingSpeedBlocks.retainAll(dependencies.keys)
        }
        refreshSensorSubscription(blocksBySource.keys.any { it is CustomSourceKey.Sensor })
        refreshIntentReceiver(
            blocksBySource.keys.filterIsInstance<CustomSourceKey.IntentExtra>().mapTo(linkedSetOf()) { it.action }
        )
        return errors
    }

    fun environment(blockId: String, now: Long = System.currentTimeMillis()): CustomScriptEnvironment {
        val dependencies: Set<CustomSourceKey>
        val speed: CustomDataValue?
        val sensor: CustomDataValue?
        val intents: Map<Pair<String, String>, CustomDataValue>
        synchronized(lock) {
            dependencies = dependenciesByBlock[blockId].orEmpty()
            speed = speedValue
            sensor = sensorValue
            intents = intentValues.toMap()
        }
        return CustomScriptEnvironment(
            now = now,
            speed = speed.takeIf { CustomSourceKey.Speed in dependencies },
            sensors = dependencies.filterIsInstance<CustomSourceKey.Sensor>().associate { source ->
                source.sensorId to sensor
            }.mapNotNullValues(),
            intents = dependencies.filterIsInstance<CustomSourceKey.IntentExtra>()
                .groupBy { it.action }
                .mapValues { (_, sources) ->
                    sources.mapNotNull { source ->
                        intents[source.action to source.extraName]?.let { source.extraName to it }
                    }.toMap()
                }
        )
    }

    override fun onStateUpdated(state: NavigationHudState) {
        val changed = synchronized(lock) {
            val current = state.speedKmh
            val receivedAt = state.speedKmhUpdatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            if ((current == null && speedValue == null) ||
                (speedValue?.value == current && speedValue?.receivedAt == receivedAt)
            ) false else {
                speedValue = current?.let { CustomDataValue(it, receivedAt) }
                pendingSpeedBlocks.addAll(blocksBySource[CustomSourceKey.Speed].orEmpty())
                true
            }
        }
        if (changed) {
            mainHandler.removeCallbacks(speedDispatch)
            mainHandler.postDelayed(speedDispatch, SPEED_COALESCE_MS)
        }
    }

    private fun refreshSensorSubscription(required: Boolean) {
        if (!required) {
            sensorSubscription?.close()
            sensorSubscription = null
            return
        }
        if (sensorSubscription != null) return
        sensorSubscription = CarSensorHub.subscribe(
            appContext,
            CustomBlocksContract.SPEED_SENSOR_ID
        ) { snapshot ->
            val changed = synchronized(lock) {
                sensorValue = CustomDataValue(snapshot.value, snapshot.receivedAt)
                pendingSpeedBlocks.addAll(
                    blocksBySource[CustomSourceKey.Sensor(CustomBlocksContract.SPEED_SENSOR_ID)].orEmpty()
                )
                pendingSpeedBlocks.isNotEmpty()
            }
            if (changed) {
                mainHandler.removeCallbacks(speedDispatch)
                mainHandler.postDelayed(speedDispatch, SPEED_COALESCE_MS)
            }
        }
    }

    private fun refreshIntentReceiver(actions: Set<String>) {
        if (actions == lastRegisteredActions) return
        unregisterIntentReceiver()
        lastRegisteredActions = actions
        if (actions.isEmpty()) return
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        ContextCompat.registerReceiver(
            appContext,
            intentReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterIntentReceiver() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(intentReceiver) }
            receiverRegistered = false
        }
        lastRegisteredActions = emptySet()
    }

    private fun <K, V : Any> Map<K, V?>.mapNotNullValues(): Map<K, V> =
        entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    private companion object {
        const val SPEED_COALESCE_MS = 80L
    }
}
