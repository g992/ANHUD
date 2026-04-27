package com.g992.anhud

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class EcarxSpeedSensorClient(
    context: Context,
    private val sensorId: Int,
    private val onSpeedMetersPerSecond: (Float) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var carApi: Any? = null
    private var sensorManager: Any? = null
    private var sensorListener: Any? = null
    private var sensorRegistered = false
    private var connectWatcher: Any? = null

    fun start() {
        if (started) return
        started = true
        val car = createCarApi() ?: run {
            onLog("Скорость: ECarX Car API недоступен")
            return
        }
        carApi = car
        connectOrRegister(car)
    }

    fun stop() {
        started = false
        unregisterSensorListener()
        unregisterConnectWatcher()
        invokeNoArg(carApi, "disconnect")
        sensorManager = null
        carApi = null
    }

    fun resubscribe() {
        if (!started) return
        unregisterSensorListener()
        val manager = sensorManager
        if (manager != null) {
            registerSensorListener(manager, reason = "resubscribe")
        } else {
            carApi?.let { attachSensorManager(it, reason = "resubscribe") }
        }
    }

    private fun createCarApi(): Any? {
        return runCatching {
            val carClass = Class.forName(CAR_CLASS_NAME)
            carClass.getMethod("create", Context::class.java).invoke(null, appContext)
        }.getOrNull()
    }

    private fun connectOrRegister(car: Any) {
        val connectable = resolveConnectableInterface(car)
        if (connectable == null) {
            attachSensorManager(car, reason = "direct")
            return
        }
        registerConnectWatcher(connectable)
        if (invokeBoolean(car, "isConnected") == true) {
            attachSensorManager(car, reason = "connected")
            return
        }
        if (!invokeNoArg(car, "connect")) {
            onLog("Скорость: ECarX connect() недоступен")
        }
    }

    private fun resolveConnectableInterface(car: Any): Class<*>? {
        val known = CONNECTABLE_CLASS_NAMES.firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className) }.getOrNull()?.takeIf { it.isInstance(car) }
        }
        if (known != null) return known
        return car.javaClass.allInterfaces().firstOrNull {
            it.simpleName == "IConnectable" || it.name.endsWith(".IConnectable")
        }
    }

    private fun registerConnectWatcher(connectable: Class<*>) {
        val watcherClass = resolveConnectWatcherInterface(connectable) ?: run {
            onLog("Скорость: ECarX connect watcher недоступен")
            return
        }
        val watcher = Proxy.newProxyInstance(
            watcherClass.classLoader,
            arrayOf(watcherClass)
        ) { _, method, _ ->
            when (method.name) {
                "onConnected" -> handler.post {
                    if (started) {
                        onLog("Скорость: ECarX Car API подключен")
                        carApi?.let { attachSensorManager(it, reason = "onConnected") }
                    }
                }
                "onDisconnected" -> handler.post {
                    if (started) {
                        sensorRegistered = false
                        sensorManager = null
                        onLog("Скорость: ECarX Car API отключен")
                    }
                }
            }
            defaultReturnValue(method.returnType)
        }
        connectWatcher = watcher
        val registered = carApi.invokeMatching("register", watcherClass, watcher)
        if (!registered) {
            onLog("Скорость: ECarX connect watcher не зарегистрирован")
        }
    }

    private fun unregisterConnectWatcher() {
        val watcher = connectWatcher ?: return
        val watcherClass = watcher.javaClass.interfaces.firstOrNull() ?: return
        carApi.invokeMatching("unregister", watcherClass, watcher)
        connectWatcher = null
    }

    private fun resolveConnectWatcherInterface(connectable: Class<*>): Class<*>? {
        val nested = connectable.classes.firstOrNull {
            it.simpleName == "IConnectWatcher" || it.simpleName == "ConnectWatcher"
        }
        if (nested != null) return nested
        return CONNECT_WATCHER_CLASS_NAMES.firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className) }.getOrNull()
        }
    }

    private fun attachSensorManager(car: Any, reason: String) {
        if (!started || sensorRegistered) return
        val manager = runCatching {
            car.javaClass.methods.firstOrNull { it.name == "getSensorManager" && it.parameterCount == 0 }
                ?.invoke(car)
        }.getOrNull()
        if (manager == null) {
            onLog("Скорость: ECarX sensor manager недоступен")
            return
        }
        sensorManager = manager
        registerSensorListener(manager, reason)
    }

    private fun registerSensorListener(manager: Any, reason: String) {
        if (!started || sensorRegistered) return
        val listenerClass = resolveSensorListenerInterface() ?: run {
            onLog("Скорость: ECarX ISensorListener недоступен")
            return
        }
        val listener = sensorListener ?: createSensorListener(listenerClass).also {
            sensorListener = it
        }
        val method = findSensorListenerMethod(manager, "registerListener", listenerClass)
        if (method == null) {
            onLog("Скорость: ECarX registerListener недоступен")
            return
        }
        val registered = invokeSensorListenerMethod(method, manager, listener, listenerClass)
        sensorRegistered = registered
        if (registered) {
            onLog("Скорость: ECarX подписка на 0x${sensorId.toString(16).uppercase()} активна ($reason)")
        } else {
            onLog("Скорость: ECarX подписка на 0x${sensorId.toString(16).uppercase()} не удалась")
        }
    }

    private fun unregisterSensorListener() {
        val manager = sensorManager ?: return
        val listener = sensorListener ?: return
        val listenerClass = listener.javaClass.interfaces.firstOrNull() ?: return
        val method = findSensorListenerMethod(manager, "unregisterListener", listenerClass)
            ?: findSensorListenerMethod(manager, "removeListener", listenerClass)
        if (method != null) {
            invokeSensorListenerMethod(method, manager, listener, listenerClass)
        }
        sensorRegistered = false
    }

    private fun resolveSensorListenerInterface(): Class<*>? {
        return SENSOR_LISTENER_CLASS_NAMES.firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className) }.getOrNull()
        }
    }

    private fun createSensorListener(listenerClass: Class<*>): Any {
        return Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            if (method.name == "onSensorValueChanged") {
                val callbackSensorId = args?.getOrNull(0).asIntOrNull()
                val value = args?.getOrNull(1).asFloatOrNull()
                if (callbackSensorId == sensorId && value != null && value.isFinite()) {
                    handler.post {
                        if (started) onSpeedMetersPerSecond(value)
                    }
                }
            }
            defaultReturnValue(method.returnType)
        }
    }

    private fun findSensorListenerMethod(
        manager: Any,
        name: String,
        listenerClass: Class<*>
    ): Method? {
        return manager.javaClass.methods.firstOrNull { method ->
            method.name == name &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.any { it.isAssignableFrom(listenerClass) } &&
                method.parameterTypes.any { it.isIntLike() }
        } ?: manager.javaClass.methods.firstOrNull { method ->
            method.name == name &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first().isAssignableFrom(listenerClass)
        }
    }

    private fun invokeSensorListenerMethod(
        method: Method,
        target: Any,
        listener: Any,
        listenerClass: Class<*>
    ): Boolean {
        val args = method.parameterTypes.map { parameterType ->
            when {
                parameterType.isAssignableFrom(listenerClass) -> listener
                parameterType.isIntLike() -> sensorId
                else -> null
            }
        }.toTypedArray()
        return runCatching {
            method.invoke(target, *args).asSuccessFlag()
        }.getOrDefault(false)
    }

    private fun invokeNoArg(target: Any?, name: String): Boolean {
        target ?: return false
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(target)
            true
        }.getOrDefault(false)
    }

    private fun invokeBoolean(target: Any, name: String): Boolean? {
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(target) as? Boolean
        }.getOrNull()
    }

    private fun Any?.invokeMatching(methodNamePart: String, parameterClass: Class<*>, argument: Any): Boolean {
        val target = this ?: return false
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name.startsWith(methodNamePart, ignoreCase = true) &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first().isAssignableFrom(parameterClass)
        } ?: return false
        return runCatching { method.invoke(target, argument).asSuccessFlag() }.getOrDefault(false)
    }

    private fun Class<*>.allInterfaces(): Set<Class<*>> {
        val result = linkedSetOf<Class<*>>()
        fun collect(type: Class<*>) {
            type.interfaces.forEach { iface ->
                if (result.add(iface)) collect(iface)
            }
            type.superclass?.let(::collect)
        }
        collect(this)
        return result
    }

    private fun Class<*>.isIntLike(): Boolean {
        return this == Int::class.javaPrimitiveType || this == Integer::class.java
    }

    private fun Any?.asIntOrNull(): Int? {
        return when (this) {
            is Int -> this
            is Number -> toInt()
            is String -> toIntOrNull()
            else -> null
        }
    }

    private fun Any?.asFloatOrNull(): Float? {
        return when (this) {
            is Float -> this
            is Double -> toFloat()
            is Number -> toFloat()
            is String -> toFloatOrNull()
            else -> null
        }
    }

    private fun Any?.asSuccessFlag(): Boolean {
        return when (this) {
            null -> true
            is Boolean -> this
            is Number -> toInt() != 0
            else -> true
        }
    }

    private fun defaultReturnValue(returnType: Class<*>): Any? {
        return when (returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Double::class.javaPrimitiveType -> 0.0
            Float::class.javaPrimitiveType -> 0f
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> 0.toChar()
            else -> null
        }
    }

    companion object {
        private const val CAR_CLASS_NAME = "com.ecarx.xui.adaptapi.car.Car"
        private val CONNECTABLE_CLASS_NAMES = listOf(
            "com.ecarx.xui.adaptapi.IConnectable",
            "com.ecarx.xui.adaptapi.car.IConnectable"
        )
        private val CONNECT_WATCHER_CLASS_NAMES = listOf(
            "com.ecarx.xui.adaptapi.IConnectable\$IConnectWatcher",
            "com.ecarx.xui.adaptapi.car.IConnectable\$IConnectWatcher"
        )
        private val SENSOR_LISTENER_CLASS_NAMES = listOf(
            "com.ecarx.xui.adaptapi.car.sensor.ISensor\$ISensorListener"
        )
    }
}
