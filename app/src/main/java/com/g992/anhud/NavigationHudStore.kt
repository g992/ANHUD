package com.g992.anhud

import android.graphics.Bitmap

data class NavigationHudState(
    val primaryText: String = "",
    val secondaryText: String = "",
    val speedKmh: Int? = null,
    val speedLimit: String = "",
    val arrival: String = "",
    val distance: String = "",
    val time: String = "",
    val trafficLight: String = "",
    val trafficCountdown: String = "",
    val maneuverBitmap: Bitmap? = null,
    val source: String = "",
    val routeActive: Boolean? = null,
    val lastUpdated: Long = 0L,
    val lastAction: String = "",
    val rawNextText: String = "",
    val rawNextStreet: String = "",
    val rawSpeedLimit: String = "",
    val rawArrival: String = "",
    val rawDistance: String = "",
    val rawTime: String = "",
    val rawTrafficLight: String = "",
    val rawTrafficCountdown: String = "",
    val rawTitle: String = "",
    val rawText: String = "",
    val rawSubtext: String = "",
    val distanceUnit: String = ""
) {
    fun isEmpty(): Boolean {
        return primaryText.isBlank() &&
            secondaryText.isBlank() &&
            speedKmh == null &&
            speedLimit.isBlank() &&
            arrival.isBlank() &&
            distance.isBlank() &&
            time.isBlank() &&
            trafficLight.isBlank() &&
            trafficCountdown.isBlank() &&
            maneuverBitmap == null
    }
}

object NavigationHudStore {
    private val lock = Any()
    private var state = NavigationHudState()
    private val listeners = mutableSetOf<Listener>()

    interface Listener {
        fun onStateUpdated(state: NavigationHudState)
    }

    fun update(updater: (NavigationHudState) -> NavigationHudState) {
        val updated: NavigationHudState
        synchronized(lock) {
            updated = updater(state)
            state = updated
        }
        notifyListeners(updated)
    }

    fun snapshot(): NavigationHudState {
        synchronized(lock) {
            return state
        }
    }

    fun reset(lastAction: String, timestamp: Long = System.currentTimeMillis()) {
        update { current ->
            current.copy(
                primaryText = "",
                secondaryText = "",
                speedKmh = current.speedKmh,
                speedLimit = "",
                arrival = "",
                distance = "",
                time = "",
                trafficLight = "",
                trafficCountdown = "",
                maneuverBitmap = null,
                source = "",
                routeActive = false,
                lastUpdated = timestamp,
                lastAction = lastAction,
                rawNextText = "",
                rawNextStreet = "",
                rawSpeedLimit = "",
                rawArrival = "",
                rawDistance = "",
                rawTime = "",
                rawTrafficLight = "",
                rawTrafficCountdown = "",
                rawTitle = "",
                rawText = "",
                rawSubtext = "",
                distanceUnit = ""
            )
        }
    }

    fun registerListener(listener: Listener) {
        synchronized(lock) {
            listeners.add(listener)
        }
        listener.onStateUpdated(snapshot())
    }

    fun unregisterListener(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners(state: NavigationHudState) {
        val snapshot: List<Listener>
        synchronized(lock) {
            snapshot = listeners.toList()
        }
        for (listener in snapshot) {
            listener.onStateUpdated(state)
        }
    }
}
