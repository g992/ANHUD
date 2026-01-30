package com.g992.anhud

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

class SensorsActivity : ScaledActivity() {
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private val logListener = object : UiLogStore.Listener {
        override fun onLogsUpdated(category: LogCategory, lines: List<String>) {
            if (category != LogCategory.SENSORS) {
                return
            }
            runOnUiThread {
                logText.text = lines.joinToString("\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensors)
        logScroll = findViewById(R.id.logScroll)
        logText = findViewById(R.id.logText)
        UiLogStore.registerListener(logListener)
    }

    override fun onDestroy() {
        UiLogStore.unregisterListener(logListener)
        super.onDestroy()
    }
}
