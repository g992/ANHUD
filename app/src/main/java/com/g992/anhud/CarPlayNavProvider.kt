package com.g992.anhud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Autolink client. Protocol recovered from GeeGeek-0.2.0-beta1.apk, SHA256
 * 96cac58f68298767aba8ad2e8ec67fbb9eb75e1a99501fb4c7e1e1411634e591.
 * Checks/enables featureRouteGuidance through local root ADB before binding, then
 * grants READ_LOGS for the in-process CP-SRV stream.
 * Binder calls run on a worker; callbacks reach the HUD on the main thread.
 */
internal class CarPlayPatchRetryException : Exception()

internal class CarPlayNavProvider(
    private val context: Context,
    private val patchRetryDelayMs: Long = 10_000L,
    private val openLog: () -> CarPlayLogStream = { CarPlayLogReader.openLogcat() },
    private val applyPatch: (CarPlayPatchOptions, () -> Boolean) -> Unit = { options, active ->
        if (CarPlayPatchState.apply(context, options, active)) throw CarPlayPatchRetryException()
        if (active()) CarPlayLogAccess.ensure(context, CarPlayLocalAdb())
    }
) : NavDataProvider {
    private val main = Handler(android.os.Looper.getMainLooper())
    private val thread = HandlerThread(TAG)
    private lateinit var worker: Handler
    @Volatile private var running = false
    private var bound = false
    private var service: IBinder? = null
    private var callbacks: Pair<Binder, Binder>? = null
    private var transactions = Transactions()
    @Volatile private var connectionVersion = 0
    private val patchPending = AtomicBoolean(false)
    private var patchAttempts = 0
    private val patchRetry = Runnable { runPatch(resetAttempts = false) }
    @Volatile private var routeAvailable = false
    private var latestRoute: CarPlayRouteData? = null // Main thread only.
    @Volatile private var turnSince = 0L
    private var preciseDistance: CarPlayLogDistance? = null
    private var appliedUpdate: ProviderNavUpdate? = null // Main thread only.
    // Log thread only: recent lines replay into the tracker when a new turn moves turnSince.
    private val logLines = ArrayDeque<String>()
    private val logTracker = CarPlayLogDistance.Tracker()
    private var trackerSince = 0L
    private var sentDistance: CarPlayLogDistance? = null
    private val logs = CarPlayLogReader(isActive = { running && routeAvailable }, receive = { line ->
        onLogLine(line)
    }, open = openLog)
    private fun onLogLine(line: String) {
        logLines.addLast(line)
        if (logLines.size > LOG_REPLAY_LINES) logLines.removeFirst()
        val since = turnSince
        if (since != trackerSince) {
            trackerSince = since
            sentDistance = null
            logTracker.reset()
            logLines.forEach { logTracker.feed(it, since) }
        } else {
            logTracker.feed(line, since)
        }
        val sample = logTracker.sample() ?: return
        val sent = sentDistance
        // Forward changes at once; refresh an unchanged value only to keep it from expiring.
        if (sent != null && sent.copy(timestamp = sample.timestamp) == sample &&
            sample.timestamp - sent.timestamp < LOG_REFRESH_MS) return
        sentDistance = sample
        publish {
            val route = latestRoute ?: return@publish
            val now = System.currentTimeMillis()
            if (sample.matches(route, turnSince, now)) {
                preciseDistance = sample
                renderRoute(route)
            }
        }
    }

    private val expireDistance = Runnable {
        latestRoute?.let { renderRoute(it) }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        worker.post { if (running) reconnect("сервис завершил работу") }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            worker.post { if (running) subscribe(binder) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            worker.post { if (running) reconnect("связь потеряна") }
        }

        override fun onBindingDied(name: ComponentName) {
            worker.post { if (running) reconnect("привязка потеряна") }
        }

        override fun onNullBinding(name: ComponentName) {
            worker.post { if (running) reconnect("пустой Binder") }
        }
    }

    private val retry = Runnable { if (running && !bound) bind() }
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val binder = service ?: return
            try {
                val status = transact(binder, transactions.session) { it.readInt() }
                if (status != 2) endRoute("сессия не активна ($status)")
                worker.postDelayed(this, POLL_MS)
            } catch (e: Exception) {
                reconnect("ошибка сессии: ${e.message}")
            }
        }
    }

    override fun start() {
        if (running) return
        running = true
        thread.start()
        worker = Handler(thread.looper)
        logs.start()
        requestPatch()
    }

    /** Selecting CarPlay persists the startup trigger; retry uses the same worker and path. */
    fun requestPatch() = runPatch(resetAttempts = true)

    private fun runPatch(resetAttempts: Boolean) {
        if (!running || !patchPending.compareAndSet(false, true)) return
        worker.post {
            try {
                if (!running) return@post
                worker.removeCallbacks(patchRetry)
                if (resetAttempts) patchAttempts = 0
                patchAttempts++
                connectionVersion++
                worker.removeCallbacks(poll)
                worker.removeCallbacks(retry)
                disconnect(unsubscribe = true)
                endRoute("активация передачи маршрута")
                try {
                    applyPatch(CarPlayPatchPrefs.options(context)) { running }
                } catch (_: CarPlayPatchRetryException) {
                    if (running && patchAttempts < 3) {
                        CarPlayPatchState.awaitingRetry()
                        worker.postDelayed(patchRetry, patchRetryDelayMs)
                    }
                } catch (e: Exception) {
                    log("ошибка активации: ${e.message}")
                }
                if (running) bind()
            } finally {
                patchPending.set(false)
            }
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        routeAvailable = false
        logs.stop()
        main.removeCallbacks(expireDistance)
        connectionVersion++
        worker.post {
            worker.removeCallbacksAndMessages(null)
            disconnect(unsubscribe = true)
            thread.quitSafely()
        }
    }

    private fun bind() {
        if (!running) return
        try {
            bound = context.bindService(
                Intent(ACTION_SERVICE).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE
            )
            if (!bound) {
                log("системный сервис не найден; ожидание")
                worker.postDelayed(retry, RETRY_MS)
            }
        } catch (e: Exception) {
            log("подключение недоступно: ${e.message}")
            worker.postDelayed(retry, RETRY_MS)
        }
    }

    private fun subscribe(binder: IBinder) {
        try {
            check(binder.interfaceDescriptor == SERVICE_DESCRIPTOR) {
                "неподдерживаемый интерфейс ${binder.interfaceDescriptor}"
            }
            service = binder
            val version = ++connectionVersion
            transactions = resolveTransactions()
            val sessionCallback = callback(SERVICE_CALLBACK_DESCRIPTOR) { code, data ->
                when (code) {
                    1 -> if (data.readInt() == 0) publish(version) { endRouteNow("отключён") }
                    2 -> {
                        val status = data.readInt()
                        if (status != 2) publish(version) { endRouteNow("сессия $status") }
                    }
                    8 -> log("ошибка сервиса ${data.readInt()}: ${data.readString()}")
                    else -> return@callback false
                }
                true
            }
            val routeCallback = callback(ROUTE_CALLBACK_DESCRIPTOR) { code, data ->
                if (code != 1) return@callback false
                val route = if (data.readInt() == 0) null else CarPlayRouteData(
                    data.readInt(), data.readString().orEmpty(), data.readInt(),
                    data.readString().orEmpty(), data.readString().orEmpty(), data.readInt(),
                    data.readLong(), data.readInt(), data.readInt(), data.readInt(), data.readInt()
                )
                publish(version) {
                    if (route?.hasRoute != true) {
                        endRouteNow("маршрут завершён (${route?.routeState})")
                    } else {
                        acceptRoute(route)
                    }
                }
                true
            }
            callbacks = sessionCallback to routeCallback
            binder.linkToDeath(deathRecipient, 0)
            register(binder, transactions.add, sessionCallback)
            // Read session before route subscription so an initial route callback isn't cleared.
            val status = transact(binder, transactions.session) { it.readInt() }
            if (status != 2) endRoute("сессия не активна ($status)")
            register(binder, transactions.routeStart, routeCallback)
            log("подписка подключена; ожидание маршрута")
            worker.removeCallbacks(poll)
            worker.postDelayed(poll, POLL_MS)
        } catch (e: Exception) {
            reconnect("ошибка подписки: ${e.message}")
        }
    }

    private fun callback(descriptor: String, receive: (Int, Parcel) -> Boolean): Binder =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(descriptor)
                    return true
                }
                return try {
                    data.enforceInterface(descriptor)
                    if (!receive(code, data)) return false
                    reply?.writeNoException()
                    true
                } catch (e: Exception) {
                    log("ошибка callback: ${e.message}")
                    false
                }
            }
        }

    private fun register(binder: IBinder, code: Int, callback: Binder) {
        transact(binder, code, { it.writeStrongBinder(callback) }) { Unit }
    }

    private fun <T> transact(
        binder: IBinder,
        code: Int,
        write: (Parcel) -> Unit = {},
        read: (Parcel) -> T
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "транзакция $code не поддерживается" }
            if (reply.dataAvail() > 0) reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun reconnect(reason: String) {
        connectionVersion++
        log(reason)
        endRoute(reason)
        worker.removeCallbacks(poll)
        disconnect(unsubscribe = true)
        worker.removeCallbacks(retry)
        if (running) worker.postDelayed(retry, RETRY_MS)
    }

    private fun disconnect(unsubscribe: Boolean) {
        val binder = service
        val pair = callbacks
        if (unsubscribe && binder != null && binder.isBinderAlive && pair != null) {
            runCatching { register(binder, transactions.routeStop, pair.second) }
            runCatching { register(binder, transactions.remove, pair.first) }
        }
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        service = null
        callbacks = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun publish(version: Int = connectionVersion, update: () -> Unit) {
        main.post {
            if (running && version == connectionVersion &&
                NavDataSourcePrefs.source(context) == NavDataSource.CARPLAY) update()
        }
    }

    private fun endRoute(reason: String) = publish { endRouteNow(reason) }

    private fun acceptRoute(route: CarPlayRouteData) {
        val old = latestRoute
        val oldMeters = old?.let { ProviderNavFormat.distanceMeters(it.update().maneuverDistance) }
        val newMeters = ProviderNavFormat.distanceMeters(route.update().maneuverDistance)
        val differentTurn = old == null || old.maneuver != route.maneuver || old.road != route.road
        val distanceJump = oldMeters != null && newMeters != null && newMeters > oldMeters * 1.5 + 100
        if (differentTurn || distanceJump) {
            // CP-SRV may precede the corresponding Binder callback by a few milliseconds.
            turnSince = System.currentTimeMillis() - if (differentTurn) 1000L else 0L
            preciseDistance = null
        }
        latestRoute = route
        routeAvailable = true
        renderRoute(route)
    }

    private fun renderRoute(route: CarPlayRouteData) {
        if (!running || NavDataSourcePrefs.source(context) != NavDataSource.CARPLAY) return
        val now = System.currentTimeMillis()
        val sample = preciseDistance?.takeIf { it.matches(route, turnSince, now) }
        preciseDistance = sample
        val update = route.update().let {
            if (sample == null) it else it.copy(
                maneuverDistance = CarPlayRouteData.displayDistance(sample.distance, sample.units)
            )
        }
        // CarPlay repeats identical route callbacks ~17 times/s; redraw the HUD only on change.
        val hud = NavigationHudStore.snapshot()
        if (update != appliedUpdate || hud.source != NavDataSource.CARPLAY.storeSource || hud.routeActive != true) {
            appliedUpdate = update
            NavigationReceiver.applyProviderUpdate(context, NavDataSource.CARPLAY, ACTION_ROUTE_UPDATE, update, false)
        }
        main.removeCallbacks(expireDistance)
        if (sample != null) main.postDelayed(expireDistance,
            (sample.timestamp + CarPlayLogDistance.MAX_AGE_MS - now + 1).coerceAtLeast(1))
    }

    private fun endRouteNow(reason: String) {
        routeAvailable = false
        latestRoute = null
        preciseDistance = null
        appliedUpdate = null
        main.removeCallbacks(expireDistance)
        NavigationReceiver.endProviderNavigation(context, NavDataSource.CARPLAY, "carplay: $reason")
    }

    private fun resolveTransactions(): Transactions = try {
        val loader = context.createPackageContext(
            PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        ).classLoader
        val stub = Class.forName("$SERVICE_DESCRIPTOR\$Stub", false, loader)
        fun code(name: String) = stub.getDeclaredField("TRANSACTION_$name").apply {
            isAccessible = true
        }.getInt(null)
        Transactions(
            code("addCarPlayServiceCallback"), code("removeCarPlayServiceCallback"),
            code("startRouteGuidanceUpdates"), code("stopRouteGuidanceUpdates"),
            code("getCarPlaySessionStatus")
        )
    } catch (e: Exception) {
        log("используется контракт GeeGeek: add=1, remove=2, route=7/8, session=17")
        Transactions()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        UiLogStore.append(LogCategory.NAVIGATION, "carplay: $message")
    }

    private data class Transactions(
        val add: Int = 1, val remove: Int = 2, val routeStart: Int = 7,
        val routeStop: Int = 8, val session: Int = 17
    )

    companion object {
        private const val TAG = "CarPlayNavProvider"
        internal const val PACKAGE = "com.autolink.carplay"
        internal const val ACTION_SERVICE = "com.autolink.carplay.action.CarPlayService"
        internal const val SERVICE_DESCRIPTOR = "com.autolink.carplay.common.aidl.ICarPlayService"
        internal const val SERVICE_CALLBACK_DESCRIPTOR = "com.autolink.carplay.common.aidl.ICarPlayServiceCallback"
        internal const val ROUTE_CALLBACK_DESCRIPTOR = "com.autolink.carplay.common.aidl.IRouteGuidanceUpdateCallback"
        private const val ACTION_ROUTE_UPDATE = "carplay.ROUTE_GUIDANCE"
        private const val RETRY_MS = 5000L
        private const val POLL_MS = 2500L
        private const val LOG_REPLAY_LINES = 100
        private const val LOG_REFRESH_MS = 1000L
    }
}
