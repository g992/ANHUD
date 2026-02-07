package com.g992.anhud

import android.graphics.Bitmap

data class TrafficLightInfo(
    val id: Int,
    val color: String,
    val countdownText: String,
    val arrowBitmap: Bitmap?,
    val arrowDirection: String,
    val lastUpdated: Long,
    val expiresAt: Long
)

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
    val maneuverType: String = "",
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
    val distanceUnit: String = "",
    val nativeTurnId: Int? = null,
    val hudSpeedHasCamera: Boolean = false,
    val hudSpeedHasGps: Boolean = false,
    val hudSpeedDistanceMeters: Int? = null,
    val hudSpeedCamType: Int? = null,
    val hudSpeedCamFlag: Int? = null,
    val hudSpeedLimit1: Int? = null,
    val hudSpeedUpdatedAt: Long = 0L,
    val roadCameraId: String? = null,
    val roadCameraDistance: String? = null,
    val roadCameraIcon: Bitmap? = null,
    val trafficLightColor: String = "",
    val trafficLightCountdown: String = "",
    val trafficLights: List<TrafficLightInfo> = emptyList()
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
            maneuverBitmap == null &&
            maneuverType.isBlank() &&
            !hudSpeedHasCamera &&
            !hudSpeedHasGps &&
            trafficLights.isEmpty()
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

    fun reset(
        lastAction: String,
        timestamp: Long = System.currentTimeMillis(),
        preserveSpeedLimit: Boolean = false,
        preserveRoadCamera: Boolean = false,
        preserveHudSpeed: Boolean = false
    ) {
        android.util.Log.d("NavigationHudStore", "reset() called: action=$lastAction")
        update { current ->
            android.util.Log.d("NavigationHudStore", "Clearing all navigation data")
            current.copy(
                primaryText = "",
                secondaryText = "",
                speedKmh = null,
                speedLimit = if (preserveSpeedLimit) current.speedLimit else "",
                arrival = "",
                distance = "",
                time = "",
                trafficLight = "",
                trafficCountdown = "",
                maneuverBitmap = null,
                maneuverType = "",
                source = "",
                routeActive = false,
                lastUpdated = timestamp,
                lastAction = lastAction,
                rawNextText = "",
                rawNextStreet = "",
                rawSpeedLimit = if (preserveSpeedLimit) current.rawSpeedLimit else "",
                rawArrival = "",
                rawDistance = "",
                rawTime = "",
                rawTrafficLight = "",
                rawTrafficCountdown = "",
                rawTitle = "",
                rawText = "",
                rawSubtext = "",
                distanceUnit = "",
                nativeTurnId = null,
                hudSpeedHasCamera = if (preserveHudSpeed) current.hudSpeedHasCamera else false,
                hudSpeedHasGps = if (preserveHudSpeed) current.hudSpeedHasGps else false,
                hudSpeedDistanceMeters = if (preserveHudSpeed) current.hudSpeedDistanceMeters else null,
                hudSpeedCamType = if (preserveHudSpeed) current.hudSpeedCamType else null,
                hudSpeedCamFlag = if (preserveHudSpeed) current.hudSpeedCamFlag else null,
                hudSpeedLimit1 = if (preserveHudSpeed) current.hudSpeedLimit1 else null,
                hudSpeedUpdatedAt = if (preserveHudSpeed) current.hudSpeedUpdatedAt else 0L,
                roadCameraId = if (preserveRoadCamera) current.roadCameraId else null,
                roadCameraDistance = if (preserveRoadCamera) current.roadCameraDistance else null,
                roadCameraIcon = if (preserveRoadCamera) current.roadCameraIcon else null,
                trafficLightColor = "",
                trafficLightCountdown = "",
                trafficLights = emptyList()
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
