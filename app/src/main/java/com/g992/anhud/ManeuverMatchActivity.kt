package com.g992.anhud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ManeuverMatchActivity : ScaledActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_maneuver_match)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.matchRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<TextView>(R.id.matchBackButton).setOnClickListener {
            finish()
        }

        val inflater = LayoutInflater.from(this)
        val container = findViewById<LinearLayout>(R.id.maneuverRowContainer)
        val items = loadContextDrawables()
        val basicIcons = loadBasicIcons()
        if (basicIcons.isEmpty()) {
            return
        }
        val spinnerAdapter = BasicIconAdapter(basicIcons)
        val indexById = basicIcons.mapIndexed { index, icon -> icon.id to index }.toMap()
        val defaultIndex = indexById[DEFAULT_BASIC_ICON_ID] ?: 0
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val defaults = loadDefaultMappings()
        val rows = ArrayList<RowBinding>(items.size)
        for ((name, resId) in items) {
            val row = inflater.inflate(R.layout.item_maneuver_match_row, container, false)
            val icon = row.findViewById<ImageView>(R.id.maneuverIcon)
            val label = row.findViewById<TextView>(R.id.maneuverLabel)
            val spinner = row.findViewById<Spinner>(R.id.basicIconSpinner)
            icon.setImageResource(resId)
            icon.contentDescription = name
            label.text = name
            spinner.adapter = spinnerAdapter
            val savedId = prefs.getString(mappingKey(name), null)
            val resolvedId = savedId ?: defaults[name]
            val resolvedIndex = resolvedId?.let { indexById[it] } ?: defaultIndex
            spinner.setSelection(resolvedIndex)
            if (savedId == null && resolvedId != null) {
                prefs.edit().putString(mappingKey(name), resolvedId).apply()
            }
            spinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
                val selected = basicIcons[position].id
                prefs.edit().putString(mappingKey(name), selected).apply()
            }
            rows.add(RowBinding(name, resId, spinner))
            container.addView(row)
        }
    }

    private fun loadContextDrawables(): List<Pair<String, Int>> {
        val result = ArrayList<Pair<String, Int>>()
        val fields = R.drawable::class.java.fields
        for (field in fields) {
            val name = field.name
            if (!name.startsWith("context_")) {
                continue
            }
            val resId = runCatching { field.getInt(null) }.getOrNull() ?: continue
            result.add(name to resId)
        }
        result.sortBy { it.first }
        return result
    }

    private fun loadBasicIcons(): List<BasicIcon> {
        val files = assets.list("basicIcons")
            ?.filter { it.endsWith(".png") || it.endsWith(".webp") }
            .orEmpty()
        val sorted = files.sortedBy {
            it.substringBefore(".").toIntOrNull() ?: Int.MAX_VALUE
        }
        val icons = ArrayList<BasicIcon>(sorted.size)
        for (file in sorted) {
            val bitmap = assets.open("basicIcons/$file").use {
                BitmapFactory.decodeStream(it)
            } ?: continue
            val id = file.substringBefore(".")
            icons.add(BasicIcon(id, file, bitmap))
        }
        return icons
    }

    private fun loadDefaultMappings(): Map<String, String> {
        val defaults = mutableMapOf<String, String>()
        val content = runCatching {
            assets.open(DEFAULTS_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return defaults
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.lastIndex) {
                return@forEach
            }
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                defaults[key] = value
            }
        }
        return defaults
    }

    private fun mappingKey(name: String): String = "mapping_$name"

    private data class BasicIcon(
        val id: String,
        val fileName: String,
        val bitmap: Bitmap
    )

    private inner class BasicIconAdapter(
        private val items: List<BasicIcon>
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): BasicIcon = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createView(position, convertView, parent, false)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createView(position, convertView, parent, true)
        }

        private fun createView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
            dropdown: Boolean
        ): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_basic_icon_spinner, parent, false)
            val icon = view.findViewById<ImageView>(R.id.basicIconImage)
            val label = view.findViewById<TextView>(R.id.basicIconLabel)
            val item = getItem(position)
            icon.setImageBitmap(item.bitmap)
            label.text = item.id
            if (dropdown) {
                label.textSize = 16f
            } else {
                label.textSize = 14f
            }
            return view
        }
    }

    private data class RowBinding(
        val name: String,
        val resId: Int,
        val spinner: Spinner
    )

    private class SimpleItemSelectedListener(
        private val onSelected: (Int) -> Unit
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>,
            view: View?,
            position: Int,
            id: Long
        ) {
            onSelected(position)
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
    }

    companion object {
        private const val DEFAULT_BASIC_ICON_ID = "101"
        private const val PREFS_NAME = "maneuver_match_prefs"
        private const val DEFAULTS_ASSET = "maneuver_match_defaults.properties"
    }
}
