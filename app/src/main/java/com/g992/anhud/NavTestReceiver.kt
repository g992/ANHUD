package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only injector for the stock-cluster navigation path (NativeNavigationController),
 * so the N155 DimNaviService backend (and FX11 adaptapi/Gly) can be exercised from adb
 * WITHOUT the real navigator. It drives the same code path the app uses in production.
 *
 * Examples (adb):
 *   adb shell am broadcast -n com.g992.anhud/.NavTestReceiver -a com.g992.anhud.NAV_TEST --es cmd backend
 *   adb shell am broadcast -n com.g992.anhud/.NavTestReceiver -a com.g992.anhud.NAV_TEST --es cmd start
 *   adb shell am broadcast -n com.g992.anhud/.NavTestReceiver -a com.g992.anhud.NAV_TEST --es cmd update \
 *       --ei turn_id 3 --es street "Test Street" --ei dist_next 300 --ei dist_dest 5000 --ei eta 600
 *   adb shell am broadcast -n com.g992.anhud/.NavTestReceiver -a com.g992.anhud.NAV_TEST --es cmd stop
 */
class NavTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NAV_TEST) return
        val app = context.applicationContext
        when (intent.getStringExtra("cmd")?.lowercase()) {
            "backend" -> {
                val ok = NativeNavigationController.ensureInitialized(app)
                Log.i(TAG, "detected=$ok backend=${NativeNavigationController.activeBackend()}")
            }
            "start" -> {
                NativeNavigationController.startNavigation(app)
                Log.i(TAG, "start -> backend=${NativeNavigationController.activeBackend()} active=${NativeNavigationController.isActive()}")
            }
            "update" -> {
                NativeNavigationController.startNavigation(app)
                NativeNavigationController.updateNavigation(
                    context = app,
                    turnId = intent.getIntExtra("turn_id", 3),
                    streetName = intent.getStringExtra("street") ?: "Test Street",
                    distanceToManeuverMeters = intent.getIntExtra("dist_next", 300),
                    distanceToDestinationMeters = intent.getIntExtra("dist_dest", 5000),
                    totalDistanceToDestinationMeters = intent.getIntExtra("dist_dest", 5000),
                    etaSeconds = intent.getIntExtra("eta", 600)
                )
                Log.i(TAG, "update sent -> backend=${NativeNavigationController.activeBackend()}")
            }
            "stop" -> {
                NativeNavigationController.stopNavigation(app)
                Log.i(TAG, "stop -> active=${NativeNavigationController.isActive()}")
            }
            else -> Log.w(TAG, "unknown cmd; use backend|start|update|stop")
        }
    }

    companion object {
        private const val TAG = "NavTestReceiver"
        const val ACTION_NAV_TEST = "com.g992.anhud.NAV_TEST"
    }
}
