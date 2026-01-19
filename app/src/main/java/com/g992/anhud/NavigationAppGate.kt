package com.g992.anhud

import android.content.Context

object NavigationAppGate {
    @Volatile
    private var allowUpdates = true
    @Volatile
    private var lastPackage = ""

    fun onTargetChanged(packageName: String) {
        allowUpdates = true
        lastPackage = packageName
    }

    fun onAppOpened(packageName: String) {
        allowUpdates = true
        lastPackage = packageName
    }

    fun onAppClosed(packageName: String) {
        allowUpdates = false
        lastPackage = packageName
    }

    fun shouldAllow(context: Context): Boolean {
        val selected = OverlayPrefs.navAppPackage(context)
        if (selected.isBlank()) {
            return true
        }
        return allowUpdates
    }

    fun debugPackage(): String = lastPackage
}
