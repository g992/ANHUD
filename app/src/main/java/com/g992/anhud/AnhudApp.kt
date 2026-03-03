package com.g992.anhud

import android.app.Application

class AnhudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PerformanceDebugMonitor.start(this)
        // MapKit integration is temporarily disabled to reduce app size.
    }
}
