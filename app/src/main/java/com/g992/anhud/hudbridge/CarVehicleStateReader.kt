package com.g992.anhud.hudbridge

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Gear and speed from Android Automotive's CarPropertyManager, through reflection so the app
 * still runs where there is no car service (phones, emulator): everything then reads as unknown.
 * Values arrive by callback and are also polled by [poll]; [onChange] runs on the car callback
 * thread.
 */
class CarVehicleStateReader(
    context: Context,
    private val onChange: () -> Unit
) {
    private val appContext = context.applicationContext
    private var car: Any? = null
    private var propertyManager: Any? = null
    private var callback: Any? = null
    private var gearPropertyId = GEAR_SELECTION
    private var speedReadable = false

    @Volatile
    var gearRaw: Int? = null
        private set

    /** Vehicle speed in m/s from PERF_VEHICLE_SPEED, null when unavailable. */
    @Volatile
    var speedMetersPerSecond: Float? = null
        private set

    val connected: Boolean
        get() = propertyManager != null

    /** Connects to the car service; returns a short status for the log. */
    fun start(): String {
        if (propertyManager != null) return "уже подключено"
        val carClass = try {
            Class.forName(CAR_CLASS)
        } catch (_: Throwable) {
            return "нет android.car (не Android Automotive)"
        }
        val newCar = try {
            carClass.getMethod("createCar", Context::class.java).invoke(null, appContext)
        } catch (e: Throwable) {
            return "Car.createCar: ${rootMessage(e)}"
        } ?: return "сервис автомобиля недоступен"
        val manager = try {
            carClass.getMethod("getCarManager", String::class.java).invoke(newCar, PROPERTY_SERVICE)
        } catch (e: Throwable) {
            disconnect(newCar)
            return "CarPropertyManager: ${rootMessage(e)}"
        } ?: run {
            disconnect(newCar)
            return "CarPropertyManager недоступен"
        }
        car = newCar
        propertyManager = manager
        gearPropertyId = if (isSupported(manager, GEAR_SELECTION) || !isSupported(manager, CURRENT_GEAR)) {
            GEAR_SELECTION
        } else {
            CURRENT_GEAR
        }
        speedReadable = appContext.checkSelfPermission(PERMISSION_CAR_SPEED) == PackageManager.PERMISSION_GRANTED &&
            isSupported(manager, PERF_VEHICLE_SPEED)
        val gearRegistered = registerCallbacks(manager)
        poll()
        val gearName = if (gearPropertyId == GEAR_SELECTION) "GEAR_SELECTION" else "CURRENT_GEAR"
        return buildString {
            append("подключено, ").append(gearName)
            append(if (gearRegistered) " (подписка)" else " (опрос)")
            append(", скорость: ").append(if (speedReadable) "PERF_VEHICLE_SPEED" else "нет доступа")
        }
    }

    fun stop() {
        val manager = propertyManager
        val cb = callback
        if (manager != null && cb != null) {
            try {
                manager.javaClass.getMethod("unregisterCallback", callbackInterface()).invoke(manager, cb)
            } catch (_: Throwable) {
            }
        }
        callback = null
        propertyManager = null
        car?.let(::disconnect)
        car = null
        gearRaw = null
        speedMetersPerSecond = null
    }

    /** Reads the current values directly (backs up callbacks that some VHALs never deliver). */
    fun poll() {
        val manager = propertyManager ?: return
        readInt(manager, gearPropertyId)?.let { gearRaw = it }
        if (speedReadable) {
            readFloat(manager, PERF_VEHICLE_SPEED)?.let { speedMetersPerSecond = it }
        }
    }

    private fun registerCallbacks(manager: Any): Boolean {
        val iface = callbackInterface() ?: return false
        val handler = InvocationHandler { proxy, method, args -> handleCallback(proxy, method, args) }
        val cb = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
        val register = try {
            manager.javaClass.getMethod(
                "registerCallback",
                iface,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            return false
        }
        callback = cb
        val gearOk = invokeRegister(register, manager, cb, gearPropertyId, RATE_ON_CHANGE)
        if (speedReadable) {
            invokeRegister(register, manager, cb, PERF_VEHICLE_SPEED, RATE_SPEED_HZ)
        }
        return gearOk
    }

    private fun invokeRegister(register: Method, manager: Any, cb: Any, propertyId: Int, rate: Float): Boolean {
        return try {
            register.invoke(manager, cb, propertyId, rate) as? Boolean ?: false
        } catch (e: Throwable) {
            Log.w(TAG, "registerCallback 0x${Integer.toHexString(propertyId)}: ${rootMessage(e)}")
            false
        }
    }

    private fun handleCallback(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return when (method.name) {
            "onChangeEvent" -> {
                args?.firstOrNull()?.let(::onPropertyValue)
                null
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "CarVehicleStateReader.Callback"
            else -> null
        }
    }

    private fun onPropertyValue(value: Any) {
        val cls = value.javaClass
        val propertyId = try {
            cls.getMethod("getPropertyId").invoke(value) as? Int
        } catch (_: Throwable) {
            null
        } ?: return
        val status = try {
            cls.getMethod("getStatus").invoke(value) as? Int
        } catch (_: Throwable) {
            null
        }
        if (status != null && status != STATUS_AVAILABLE) return
        val raw = try {
            cls.getMethod("getValue").invoke(value)
        } catch (_: Throwable) {
            null
        }
        when (propertyId) {
            gearPropertyId -> (raw as? Int)?.let { gearRaw = it }
            PERF_VEHICLE_SPEED -> (raw as? Float)?.let { speedMetersPerSecond = it }
            else -> return
        }
        onChange()
    }

    private fun isSupported(manager: Any, propertyId: Int): Boolean {
        return try {
            manager.javaClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType)
                .invoke(manager, propertyId) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun readInt(manager: Any, propertyId: Int): Int? {
        return try {
            manager.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, propertyId, AREA_GLOBAL) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun readFloat(manager: Any, propertyId: Int): Float? {
        return try {
            manager.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, propertyId, AREA_GLOBAL) as? Float
        } catch (_: Throwable) {
            null
        }
    }

    private fun callbackInterface(): Class<*>? {
        return try {
            Class.forName(CALLBACK_CLASS)
        } catch (_: Throwable) {
            null
        }
    }

    private fun disconnect(carObject: Any) {
        try {
            carObject.javaClass.getMethod("disconnect").invoke(carObject)
        } catch (_: Throwable) {
        }
    }

    private fun rootMessage(e: Throwable): String {
        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause.message ?: cause.javaClass.simpleName
    }

    companion object {
        private const val TAG = "CarVehicleStateReader"
        private const val CAR_CLASS = "android.car.Car"
        private const val CALLBACK_CLASS =
            "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"
        private const val PROPERTY_SERVICE = "property"
        const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"

        /** VehiclePropertyIds. */
        const val GEAR_SELECTION = 0x11400400
        const val CURRENT_GEAR = 0x11400401
        const val PERF_VEHICLE_SPEED = 0x11600207

        private const val AREA_GLOBAL = 0
        private const val STATUS_AVAILABLE = 0
        private const val RATE_ON_CHANGE = 0f
        private const val RATE_SPEED_HZ = 5f
    }
}
