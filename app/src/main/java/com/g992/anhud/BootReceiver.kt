package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        UiLogStore.append(LogCategory.SYSTEM, "BOOT_COMPLETED получен")
        val serviceIntent = Intent(context, HudBackgroundService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
