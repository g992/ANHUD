package com.g992.anhud

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

open class ScaledActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }
}
