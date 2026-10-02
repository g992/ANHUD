package com.g992.anhud

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * N155 (DesaySV / Qualcomm 8155 + QNX) stock-cluster navigation backend.
 *
 * Unlike FX11 (which exposes `com.ecarx.xui.adaptapi...DimInteraction` /
 * `com.geely.os...GlyDimInteraction` framework classes), N155 has NO such class. Instead
 * the stock DIM app `com.autolink.diminteraction` registers a system Binder service named
 * "DimNaviService" (AIDL descriptor `ecarx.dimprotocol.custom.service.IDimNaviService`).
 * We drive it directly with hand-built Parcels via IBinder.transact — no OEM SDK needed.
 * The events are forwarded by the DIM app over CabinLan to QNX, which renders them on the
 * stock instrument cluster. This is the sanctioned event path (send data, QNX draws), not
 * a raw QNX socket.
 *
 * Reachability confirmed on N155: the service is registered in ServiceManager, the Stub has
 * no permission check, and SELinux is permissive — a normal app can obtain and call it.
 */
class DesaySvDimNavi private constructor(private val binder: IBinder) {

    fun start() {
        // turn the cluster projection area on, then START -> SUCCESS
        notifyDimProjectScreenSwitch(true)
        notifyNavigationStatus(NAVI_STATUS_START)
        notifyNavigationStatus(NAVI_STATUS_SUCCESS)
    }

    fun stop() {
        notifyNavigationStatus(NAVI_STATUS_END)
        notifyDimProjectScreenSwitch(false)
    }

    fun update(
        turnId: Int,
        streetName: String,
        distanceToManeuverMeters: Int,
        distanceToDestinationMeters: Int,
        etaSeconds: Int
    ) {
        callInt(TRANSACTION_updateNavigationTurnId, turnId)
        callString(TRANSACTION_updateNextGuidancePointName, streetName)
        callInt(TRANSACTION_updateDistanceToNextGuidancePoint, distanceToManeuverMeters)
        callInt(TRANSACTION_updateDistanceToDestination, distanceToDestinationMeters)
        callInt(TRANSACTION_updateETA, etaSeconds)
    }

    fun notifyNavigationStatus(status: Int) = callInt(TRANSACTION_notifyNavigationStatus, status)

    fun notifyDimProjectScreenSwitch(on: Boolean) =
        callInt(TRANSACTION_notifyDimProjectScreenSwitch, if (on) 1 else 0)

    fun getDimProjectScreenStatus(): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_getDimProjectScreenStatus, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            Log.w(TAG, "getDimProjectScreenStatus failed: ${e.message}")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun callInt(code: Int, value: Int) = transactVoid(code) { it.writeInt(value) }
    private fun callString(code: Int, value: String) = transactVoid(code) { it.writeString(value) }

    private inline fun transactVoid(code: Int, writeArgs: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            writeArgs(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            Log.w(TAG, "transact($code) failed: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "DesaySvDimNavi"
        const val NAME = "DimNaviService"
        private const val DESCRIPTOR = "ecarx.dimprotocol.custom.service.IDimNaviService"

        // IDimNaviService transaction codes (from decompiled Stub)
        private const val TRANSACTION_notifyNavigationStatus = 1
        private const val TRANSACTION_updateETA = 2
        private const val TRANSACTION_updateDistanceToDestination = 3
        private const val TRANSACTION_updateDistanceToNextGuidancePoint = 4
        private const val TRANSACTION_updateNextGuidancePointName = 5
        private const val TRANSACTION_updateNavigationTurnId = 6
        // 7 = updateLaneInfo(List<AdaptLaneInfo>), 8 = updateExtensionInfo(Bundle) — not wired yet
        private const val TRANSACTION_notifyDimProjectScreenSwitch = 9
        private const val TRANSACTION_getDimProjectScreenStatus = 10

        // NAVI_STATUS_* constants from IDimNaviService
        const val NAVI_STATUS_SUCCESS = 1
        const val NAVI_STATUS_START = 2
        const val NAVI_STATUS_END = 3

        /** Returns a backend if the N155 DimNaviService binder is available, else null. */
        fun tryCreate(): DesaySvDimNavi? {
            val binder = getServiceBinder(NAME) ?: return null
            return try {
                // sanity: this throws if the descriptor doesn't match / call is denied
                val probe = DesaySvDimNavi(binder)
                probe.getDimProjectScreenStatus() // any transact confirms the link is usable
                Log.d(TAG, "N155 DimNaviService acquired")
                probe
            } catch (e: Exception) {
                Log.w(TAG, "DimNaviService present but not callable: ${e.message}")
                null
            }
        }

        private fun getServiceBinder(name: String): IBinder? {
            return try {
                val sm = Class.forName("android.os.ServiceManager")
                val m = sm.getMethod("getService", String::class.java)
                m.invoke(null, name) as? IBinder
            } catch (e: Exception) {
                // hidden-API restriction or class missing -> not this platform
                Log.d(TAG, "ServiceManager.getService($name) unavailable: ${e.message}")
                null
            }
        }
    }
}
