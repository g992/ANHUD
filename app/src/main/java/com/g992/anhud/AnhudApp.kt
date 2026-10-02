package com.g992.anhud

import android.app.Application

class AnhudApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (isCustomScriptSandboxProcess(getProcessName())) {
            return
        }
        MapRenderSettingsStore.initialize(applicationContext)
    }
}

internal fun isCustomScriptSandboxProcess(processName: String): Boolean =
    processName.contains(":custom_script_sandbox")
