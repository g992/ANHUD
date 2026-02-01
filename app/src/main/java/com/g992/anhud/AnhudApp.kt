package com.g992.anhud

import android.app.Application
import com.yandex.mapkit.MapKitFactory

class AnhudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
        MapKitFactory.initialize(this)
    }
}
