package com.g992.anhud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

object NativeNavigationController {
    private const val TAG = "NativeNavController"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dimInteraction: Any? = null
    private var naviInteraction: Any? = null
    private var n155: DesaySvDimNavi? = null           // N155 (DesaySV DimNaviService) backend
    private var backendName: String = "none"
    private var isNavigationActive = false
    private var dimUnavailableLogged = false

    fun isActive(): Boolean = isNavigationActive

    /** Which stock backend was auto-detected: "gly" (FX11 Geely), "ecarx" (FX11 ecarx), "n155", or "none". */
    fun activeBackend(): String = backendName

    private fun hasBackend(): Boolean = naviInteraction != null || n155 != null

    fun ensureInitialized(context: Context): Boolean {
        if (hasBackend()) {
            return true
        }
        if (tryInitGlyDimInteraction(context)) {
            backendName = "gly"; dimUnavailableLogged = false
            Log.d(TAG, "Stock nav backend: FX11 GlyDimInteraction")
            return true
        }
        if (tryInitEcarxDimInteraction(context)) {
            backendName = "ecarx"; dimUnavailableLogged = false
            Log.d(TAG, "Stock nav backend: FX11 ecarx DimInteraction")
            return true
        }
        DesaySvDimNavi.tryCreate()?.let {
            n155 = it; backendName = "n155"; dimUnavailableLogged = false
            Log.d(TAG, "Stock nav backend: N155 DimNaviService")
            return true
        }
        if (!dimUnavailableLogged) {
            Log.w(TAG, "DIM interaction not available (no FX11 adaptapi/Gly, no N155 DimNaviService)")
            dimUnavailableLogged = true
        }
        return false
    }

    fun startNavigation(context: Context) {
        if (!ensureInitialized(context) || isNavigationActive) {
            return
        }
        n155?.let {
            it.start()
            isNavigationActive = true
            Log.d(TAG, "Native navigation started (n155)")
            return
        }
        invokeMethod("notifyTurnByTurnStarted")
        invokeMethod("notifyNavigationStatus", 2)
        mainHandler.postDelayed({
            invokeMethod("notifyNavigationStatus", 1)
            isNavigationActive = true
            Log.d(TAG, "Native navigation started")
        }, 100)
    }

    fun stopNavigation(context: Context) {
        if (!ensureInitialized(context)) {
            return
        }
        n155?.let {
            it.stop()
            isNavigationActive = false
            Log.d(TAG, "Native navigation stopped (n155)")
            return
        }
        invokeMethod("notifyNavigationStatus", 0)
        isNavigationActive = false
        Log.d(TAG, "Native navigation stopped")
    }

    fun updateNavigation(
        context: Context,
        turnId: Int,
        streetName: String,
        distanceToManeuverMeters: Int,
        distanceToDestinationMeters: Int,
        totalDistanceToDestinationMeters: Int,
        etaSeconds: Int
    ) {
        Log.d(
            TAG,
            "Native nav update: turnId=$turnId street=\"$streetName\" nextDistM=$distanceToManeuverMeters " +
                    "destDistM=$distanceToDestinationMeters totalDestDistM=$totalDistanceToDestinationMeters etaSec=$etaSeconds"
        )
        if (!ensureInitialized(context)) {
            return
        }
        n155?.let {
            it.update(
                turnId = turnId,
                streetName = streetName,
                distanceToManeuverMeters = distanceToManeuverMeters,
                distanceToDestinationMeters = distanceToDestinationMeters,
                etaSeconds = etaSeconds
            )
            return
        }
        val navi = naviInteraction ?: return
        val method = navi.javaClass.methods.firstOrNull {
            it.name == "updateNavigationInfo" && it.parameterTypes.size == 1
        } ?: run {
            Log.w(TAG, "updateNavigationInfo not found")
            return
        }
        val infoClass = method.parameterTypes[0]
        val handler = InvocationHandler { _, methodInvoked, _ ->
            when (methodInvoked.name) {
                "getNavigationStatus" -> 1
                "getNavigationTurnId" -> turnId
                "getNavigationTurnSVG" -> ""
                "getNextGuidancePointName" -> streetName
                "getDistanceToNextGuidancePoint" -> distanceToManeuverMeters.toLong()
                "getDistanceToDestination" -> distanceToDestinationMeters.toLong()
                "getTotalDistanceToDestination" -> totalDistanceToDestinationMeters.toLong()
                "getETA" -> etaSeconds.toLong()
                "getDrivingDirection" -> 0
                "getGuideType" -> 0
                "getCurrentRoadName" -> ""
                "getRoadType" -> 0
                "getSegRemainTime" -> 0L
                "getTrafficLightNum" -> 0
                "getRouteRemainTrafficLightNum" -> 0
                "getCameraType" -> 0
                "getCameraSpeed" -> 0
                "getCameraDistance" -> 0
                "getMuteState" -> 0
                "getDayNightMode" -> 0
                "getLaneInfo" -> null
                "getHighwayExitInfo" -> null
                "getServiceAreaInfo" -> null
                "getRoadCameraInfo" -> null
                "getExtensionInfo" -> null
                else -> defaultReturnValue(methodInvoked.returnType)
            }
        }
        val proxy = Proxy.newProxyInstance(infoClass.classLoader, arrayOf(infoClass), handler)
        try {
            method.invoke(navi, proxy)
        } catch (e: Exception) {
            Log.w(TAG, "updateNavigationInfo failed: ${e.cause?.message ?: e.message}")
        }
        invokeOptionalIntMethod(navi, "updateDistanceToDestination", distanceToDestinationMeters)
        invokeOptionalIntMethod(navi, "updateTotalDistanceToDestination", totalDistanceToDestinationMeters)
    }

    private fun tryInitGlyDimInteraction(context: Context): Boolean {
        return try {
            val glyClass = Class.forName("com.geely.os.diminteraction.GlyDimInteraction")
            val createMethod = glyClass.getMethod("create", Context::class.java)
            dimInteraction = createMethod.invoke(null, context)
            naviInteraction = dimInteraction?.javaClass?.getMethod("getNaviInteraction")?.invoke(dimInteraction)
            naviInteraction != null
        } catch (e: Exception) {
            false
        }
    }

    private fun tryInitEcarxDimInteraction(context: Context): Boolean {
        return try {
            val ecarxClass = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
            val createMethod = ecarxClass.getMethod("create", Context::class.java)
            dimInteraction = createMethod.invoke(null, context)
            naviInteraction = dimInteraction?.javaClass?.getMethod("getNaviInteraction")?.invoke(dimInteraction)
            naviInteraction != null
        } catch (e: Exception) {
            false
        }
    }

    private fun invokeMethod(methodName: String) {
        val navi = naviInteraction ?: return
        try {
            val method = navi.javaClass.getMethod(methodName)
            method.invoke(navi)
        } catch (e: Exception) {
            Log.w(TAG, "$methodName failed: ${e.cause?.message ?: e.message}")
        }
    }

    private fun invokeMethod(methodName: String, intParam: Int) {
        val navi = naviInteraction ?: return
        try {
            val method = navi.javaClass.getMethod(methodName, Int::class.java)
            method.invoke(navi, intParam)
        } catch (e: Exception) {
            Log.w(TAG, "$methodName failed: ${e.cause?.message ?: e.message}")
        }
    }

    private fun invokeOptionalIntMethod(navi: Any, methodName: String, intParam: Int) {
        try {
            val method = navi.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1
            } ?: return
            method.invoke(navi, intParam)
        } catch (e: Exception) {
            Log.w(TAG, "$methodName failed: ${e.cause?.message ?: e.message}")
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
}
