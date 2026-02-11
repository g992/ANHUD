package com.g992.anhud

import android.graphics.Point
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter

internal fun MainActivity.setupDisplaySpinners() {
    val activity = this
    val displays = HudDisplayUtils.availableDisplays(this)
    displayOptions = displays.map { display ->
        val size = HudDisplayUtils.displaySize(this, display)
        DisplayOption(
            display.displayId,
            getString(
                R.string.display_option_label,
                display.displayId,
                display.name,
                size.x,
                size.y
            )
        )
    }
    val labels = displayOptions.map { it.label }
    fun buildAdapter(): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        return adapter
    }
    displaySpinner.adapter = buildAdapter()

    val preferredDisplayId = HudDisplayUtils.preferredDisplayId(this)
    val savedDisplayId = OverlayPrefs.displayId(this)
    val preferredIndex = displayOptions.indexOfFirst { it.id == preferredDisplayId }.takeIf { it >= 0 } ?: 0
    val savedIndex = displayOptions.indexOfFirst { it.id == savedDisplayId }
    val selectedIndex = if (savedIndex >= 0) savedIndex else preferredIndex
    val selectedOption = displayOptions.getOrNull(selectedIndex)
    if (selectedOption != null) {
        displaySpinner.setSelection(selectedIndex)
        displaySpinner.post {
            updateDisplayMetrics(selectedOption.id)
        }
    } else {
        updateDisplayMetrics(preferredDisplayId)
    }

    displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            if (isDisplaySelectionSyncing) {
                return
            }
            val option = displayOptions[position]
            OverlayPrefs.setDisplayId(activity, option.id)
            updateDisplayMetrics(option.id)
            notifyOverlaySettingsChanged()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
        }
    }
}

internal fun MainActivity.updateDisplayMetrics(displayId: Int) {
    val display = HudDisplayUtils.resolveDisplay(this, displayId)
    if (display != null) {
        displaySize = HudDisplayUtils.displaySize(this, display)
        val displayContext = createDisplayContext(display)
        displayDensity = displayContext.resources.displayMetrics.density
    } else {
        displaySize = Point(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        displayDensity = resources.displayMetrics.density
    }
}

internal data class DisplayOption(val id: Int, val label: String)
