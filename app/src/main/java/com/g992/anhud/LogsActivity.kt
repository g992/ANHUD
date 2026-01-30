package com.g992.anhud

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.EnumMap

class LogsActivity : ScaledActivity() {
    private data class LogSection(
        val scrollView: ScrollView,
        val textView: TextView
    )

    private val logSections = EnumMap<LogCategory, LogSection>(LogCategory::class.java)
    private val logListener = object : UiLogStore.Listener {
        override fun onLogsUpdated(category: LogCategory, lines: List<String>) {
            runOnUiThread {
                val section = logSections[category] ?: return@runOnUiThread
                section.textView.text = lines.joinToString("\n")
                section.scrollView.post {
                    section.scrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_logs)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.logsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<android.widget.Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
        logSections[LogCategory.NAVIGATION] = LogSection(
            findViewById(R.id.navScroll),
            findViewById(R.id.navLogs)
        )
        logSections[LogCategory.SENSORS] = LogSection(
            findViewById(R.id.sensorScroll),
            findViewById(R.id.sensorLogs)
        )
        logSections[LogCategory.SYSTEM] = LogSection(
            findViewById(R.id.systemScroll),
            findViewById(R.id.systemLogs)
        )
        UiLogStore.registerListener(logListener)
    }

    override fun onDestroy() {
        UiLogStore.unregisterListener(logListener)
        super.onDestroy()
    }
}
