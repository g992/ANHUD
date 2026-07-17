package com.g992.anhud

import android.content.Context

object CustomCarSensorRegistry {
    fun requireSupported(sensorId: Int) {
        require(sensorId == CustomBlocksContract.SPEED_SENSOR_ID) {
            "Unsupported sensor ID: $sensorId"
        }
    }
}

data class CarSensorSnapshot(
    val value: Float,
    val receivedAt: Long
)

/**
 * Process-wide owner of the real ECarX sensor subscription. The current integration exposes only
 * sensor 1055232 and its callback value is metres per second.
 */
object CarSensorHub {
    private val lock = Any()
    private val listeners = linkedMapOf<Long, (CarSensorSnapshot) -> Unit>()
    private var nextListenerId = 1L
    private var client: EcarxSpeedSensorClient? = null
    private var latest: CarSensorSnapshot? = null

    fun subscribe(
        context: Context,
        sensorId: Int,
        listener: (CarSensorSnapshot) -> Unit
    ): AutoCloseable {
        CustomCarSensorRegistry.requireSupported(sensorId)
        val listenerId: Long
        val snapshot: CarSensorSnapshot?
        synchronized(lock) {
            listenerId = nextListenerId++
            listeners[listenerId] = listener
            snapshot = latest
            if (client == null) {
                client = EcarxSpeedSensorClient(
                    context = context.applicationContext,
                    sensorId = CustomBlocksContract.SPEED_SENSOR_ID,
                    onSpeedMetersPerSecond = ::onSpeedMetersPerSecond,
                    onLog = { message -> UiLogStore.append(LogCategory.SENSORS, message) }
                ).also { it.start() }
            }
        }
        snapshot?.let(listener)
        return AutoCloseable { unsubscribe(listenerId) }
    }

    fun snapshot(sensorId: Int): CarSensorSnapshot? {
        CustomCarSensorRegistry.requireSupported(sensorId)
        return synchronized(lock) { latest }
    }

    fun resubscribe(sensorId: Int) {
        CustomCarSensorRegistry.requireSupported(sensorId)
        synchronized(lock) { client }?.resubscribe()
    }

    private fun onSpeedMetersPerSecond(value: Float) {
        if (!value.isFinite()) return
        val snapshot = CarSensorSnapshot(value, System.currentTimeMillis())
        val callbacks = synchronized(lock) {
            latest = snapshot
            listeners.values.toList()
        }
        callbacks.forEach { callback -> callback(snapshot) }
    }

    private fun unsubscribe(listenerId: Long) {
        val clientToStop = synchronized(lock) {
            listeners.remove(listenerId)
            if (listeners.isEmpty()) client.also { client = null } else null
        }
        clientToStop?.stop()
    }
}
