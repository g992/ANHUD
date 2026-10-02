package com.g992.anhud

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import kotlin.math.roundToInt

class SensorDataService : Service() {
    private var lastVehicleSpeedKmh: Int? = null
    private var lastVehicleSpeedChangedElapsedMs: Long = 0L
    private var carProxyBinder: IBinder? = null
    private var carProxyConnection: ServiceConnection? = null
    private var lastTurnSignalRaw: Int? = null
    private var speedSensorSubscription: AutoCloseable? = null

    private val staleSpeedHandler = Handler(Looper.getMainLooper())
    private val staleSpeedRunnable = object : Runnable {
        override fun run() {
            resubscribeVehicleSpeedIfNeeded()
            staleSpeedHandler.postDelayed(this, SPEED_WATCHDOG_INTERVAL_MS)
        }
    }
    private val turnSignalPollRunnable = object : Runnable {
        override fun run() {
            pollTurnSignalFromCarProxy()
            staleSpeedHandler.postDelayed(this, TURN_SIGNAL_POLL_INTERVAL_MS)
        }
    }
    private val carProxyReconnectRunnable = Runnable { bindTurnSignalCarProxy() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UiLogStore.append(LogCategory.SENSORS, "Сервис создан")
        staleSpeedHandler.postDelayed(staleSpeedRunnable, SPEED_WATCHDOG_INTERVAL_MS)
        initTurnSignalIntegration()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UiLogStore.append(LogCategory.SENSORS, "Сервис запущен")
        if (speedSensorSubscription == null) {
            speedSensorSubscription = CarSensorHub.subscribe(
                context = applicationContext,
                sensorId = CustomBlocksContract.SPEED_SENSOR_ID
            ) { snapshot -> handleVehicleSpeed(snapshot.value) }
        }
        subscribeToTurnSignalSensors()
        return START_STICKY
    }

    override fun onDestroy() {
        UiLogStore.append(LogCategory.SENSORS, "Сервис остановлен")
        staleSpeedHandler.removeCallbacks(staleSpeedRunnable)
        staleSpeedHandler.removeCallbacks(turnSignalPollRunnable)
        staleSpeedHandler.removeCallbacks(carProxyReconnectRunnable)
        speedSensorSubscription?.close()
        speedSensorSubscription = null
        unbindTurnSignalCarProxy()
        clearTurnSignalState()
        resetVehicleSpeedWatchdog()
        super.onDestroy()
    }

    private fun initTurnSignalIntegration() {
        lastTurnSignalRaw = null
        clearTurnSignalState()
    }

    private fun subscribeToTurnSignalSensors() {
        bindTurnSignalCarProxy()
        staleSpeedHandler.removeCallbacks(turnSignalPollRunnable)
        staleSpeedHandler.postDelayed(turnSignalPollRunnable, TURN_SIGNAL_BIND_GRACE_MS)
    }

    private fun clearTurnSignalState() {
        lastTurnSignalRaw = null
        NavigationHudStore.update { current ->
            if (!current.turnSignalLeft && !current.turnSignalRight && !current.turnSignalHazard) {
                current
            } else {
                current.copy(
                    turnSignalLeft = false,
                    turnSignalRight = false,
                    turnSignalHazard = false
                )
            }
        }
    }

    private fun handleVehicleSpeed(speedMetersPerSecond: Float) {
        val rawSpeed = (speedMetersPerSecond * MS_TO_KMH).roundToInt()
        val speedKmh = rawSpeed.coerceAtLeast(0)
        markVehicleSpeedObserved(speedKmh)
        NavigationHudStore.update { current ->
            current.copy(speedKmh = speedKmh, speedKmhUpdatedAt = System.currentTimeMillis())
        }
        UiLogStore.append(
            LogCategory.SENSORS,
            "Скорость: $speedKmh км/ч (ecarx)"
        )
    }

    private fun publishTurnSignalState(state: TurnSignalResolvedState) {
        var changed = false
        NavigationHudStore.update { current ->
            if (
                current.turnSignalLeft == state.left &&
                current.turnSignalRight == state.right &&
                current.turnSignalHazard == state.hazard
            ) {
                current
            } else {
                changed = true
                current.copy(
                    turnSignalLeft = state.left,
                    turnSignalRight = state.right,
                    turnSignalHazard = state.hazard
                )
            }
        }
        if (changed) {
            UiLogStore.append(
                LogCategory.SENSORS,
                "Поворотники: L=${if (state.left) 1 else 0} R=${if (state.right) 1 else 0} A=${if (state.hazard) 1 else 0} (${state.source})"
            )
        }
    }

    private fun bindTurnSignalCarProxy() {
        if (carProxyConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                carProxyBinder = service
                if (service == null) {
                    UiLogStore.append(LogCategory.SENSORS, "Поворотники: CarProxy подключен без binder")
                    scheduleTurnSignalReconnect()
                    return
                }
                val subscribed = subscribeTurnSignalProperty()
                UiLogStore.append(
                    LogCategory.SENSORS,
                    if (subscribed) {
                        "Поворотники: CarProxy подключен, подписка на $PROP_TURN_SIGNAL активна"
                    } else {
                        "Поворотники: CarProxy подключен, но подписка на $PROP_TURN_SIGNAL не удалась"
                    }
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleCarProxyDisconnect("service disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                handleCarProxyDisconnect("binding died")
            }

            override fun onNullBinding(name: ComponentName?) {
                handleCarProxyDisconnect("null binding")
            }
        }
        val intent = Intent().setComponent(ComponentName(CAR_PROXY_PACKAGE, CAR_PROXY_SERVICE))
        val bound = runCatching { bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (bound) {
            carProxyConnection = connection
            UiLogStore.append(LogCategory.SENSORS, "Поворотники: запрос bind к CarProxy отправлен")
        } else {
            UiLogStore.append(LogCategory.SENSORS, "Поворотники: bindService к CarProxy вернул false")
            scheduleTurnSignalReconnect()
        }
    }

    private fun unbindTurnSignalCarProxy() {
        carProxyConnection?.let { connection ->
            runCatching { unbindService(connection) }
        }
        carProxyBinder = null
        carProxyConnection = null
    }

    private fun handleCarProxyDisconnect(reason: String) {
        carProxyBinder = null
        carProxyConnection = null
        UiLogStore.append(LogCategory.SENSORS, "Поворотники: CarProxy отключен ($reason)")
        scheduleTurnSignalReconnect()
    }

    private fun scheduleTurnSignalReconnect() {
        staleSpeedHandler.removeCallbacks(carProxyReconnectRunnable)
        staleSpeedHandler.postDelayed(carProxyReconnectRunnable, TURN_SIGNAL_RECONNECT_INTERVAL_MS)
    }

    private fun subscribeTurnSignalProperty(): Boolean {
        return transactCarProxyInt(TX_ADD_EVENT, PROP_TURN_SIGNAL) != null
    }

    private fun readTurnSignalFromCarProxy(): Int? {
        return transactCarProxyInt(TX_GET_INT, PROP_TURN_SIGNAL)
    }

    private fun transactCarProxyInt(code: Int, propertyId: Int): Int? {
        val binder = carProxyBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CAR_PROXY_DESCRIPTOR)
            data.writeInt(propertyId)
            val success = binder.transact(code, data, reply, 0)
            if (!success) return null
            reply.readException()
            reply.readInt()
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun pollTurnSignalFromCarProxy() {
        val raw = readTurnSignalFromCarProxy() ?: return
        if (raw == lastTurnSignalRaw && raw !in 0..3) return
        lastTurnSignalRaw = raw
        val state = decodeCarProxyTurnSignal(raw) ?: run {
            UiLogStore.append(LogCategory.SENSORS, "Поворотники: CarProxy raw=$raw проигнорирован")
            return
        }
        publishTurnSignalState(state)
    }

    private fun markVehicleSpeedObserved(speedKmh: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastVehicleSpeedKmh != speedKmh) {
            lastVehicleSpeedKmh = speedKmh
            lastVehicleSpeedChangedElapsedMs = now
            return
        }
        if (lastVehicleSpeedChangedElapsedMs <= 0L) {
            lastVehicleSpeedChangedElapsedMs = now
        }
    }

    private fun resetVehicleSpeedWatchdog() {
        lastVehicleSpeedKmh = null
        lastVehicleSpeedChangedElapsedMs = 0L
    }

    private fun resubscribeVehicleSpeedIfNeeded() {
        val timeoutSeconds = OverlayPrefs.speedometerFreezeTimeout(this)
        if (timeoutSeconds <= 0) return
        val speedKmh = lastVehicleSpeedKmh ?: return
        val lastChanged = lastVehicleSpeedChangedElapsedMs
        if (lastChanged <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - lastChanged
        if (elapsed < timeoutSeconds * MILLIS_PER_SECOND_LONG) return
        UiLogStore.append(
            LogCategory.SENSORS,
            "Скорость не менялась $timeoutSeconds c (ecarx=$speedKmh), переподписка на датчик"
        )
        CarSensorHub.resubscribe(CustomBlocksContract.SPEED_SENSOR_ID)
        lastVehicleSpeedChangedElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun readIntExtraAllowZero(intent: Intent, key: String): Int? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(key)) return null
        @Suppress("DEPRECATION")
        return when (val raw = extras.get(key)) {
            is Int -> raw
            is Long -> raw.toInt()
            is Float -> raw.toInt()
            is Double -> raw.toInt()
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            is Boolean -> if (raw) 1 else 0
            else -> raw?.toString()?.toIntOrNull()
        }
    }

    private fun decodeCarProxyTurnSignal(raw: Int): TurnSignalResolvedState? {
        if (raw < 0 || raw == TURN_SIGNAL_INVALID_VALUE) return null
        val left = raw == 1 || raw == 3
        val right = raw == 2 || raw == 3
        val hazard = raw == 3
        if (raw !in 0..3) return null
        return TurnSignalResolvedState(
            left = left,
            right = right,
            hazard = hazard,
            source = "carproxy:$PROP_TURN_SIGNAL raw:$raw"
        )
    }

    private data class TurnSignalResolvedState(
        val left: Boolean,
        val right: Boolean,
        val hazard: Boolean,
        val source: String
    )

    companion object {
        private const val CAR_PROXY_PACKAGE = "com.autolink.carproxyservice"
        private const val CAR_PROXY_SERVICE = "com.autolink.carproxyservice.CarProxyService"
        private const val CAR_PROXY_DESCRIPTOR = "com.autolink.adapterbinder.ICarProxyService"
        private const val TX_ADD_EVENT = 3
        private const val TX_GET_INT = 5
        private const val PROP_TURN_SIGNAL = 557848166
        private const val TURN_SIGNAL_INVALID_VALUE = 255
        private const val TURN_SIGNAL_BIND_GRACE_MS = 500L
        private const val TURN_SIGNAL_POLL_INTERVAL_MS = 150L
        private const val TURN_SIGNAL_RECONNECT_INTERVAL_MS = 1500L
        private const val MS_TO_KMH = 3.6f
        private const val SPEED_WATCHDOG_INTERVAL_MS = 1000L
        private const val MILLIS_PER_SECOND_LONG = 1000L
    }
}
