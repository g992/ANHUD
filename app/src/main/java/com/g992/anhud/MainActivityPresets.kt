package com.g992.anhud

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

internal fun MainActivity.setupPresetSelector() {
    savePresetButton.setOnClickListener {
        saveActivePreset()
    }
    saveAsPresetButton.setOnClickListener {
        showSavePresetDialog()
    }
    saveAsPresetButton.isEnabled = true
    saveAsPresetButton.alpha = 1f

    presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            if (isPresetSelectionSyncing) {
                return
            }
            val preset = presetOptions.getOrNull(position) ?: return
            if (preset.id == activePresetId) {
                return
            }
            applyPreset(preset)
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    refreshPresets(keepSelection = false)
}

internal fun MainActivity.refreshPresets(keepSelection: Boolean) {
    presetOptions = PresetManager.loadPresets(this)
    if (presetAdapter == null) {
        presetAdapter = PresetAdapter(this, presetOptions)
        presetSpinner.adapter = presetAdapter
    } else {
        presetAdapter?.updateItems(presetOptions)
    }

    val resolvedId = resolveActivePresetId(keepSelection)
    val selectedIndex = presetOptions.indexOfFirst { it.id == resolvedId }.takeIf { it >= 0 } ?: 0
    isPresetSelectionSyncing = true
    presetSpinner.setSelection(selectedIndex)
    isPresetSelectionSyncing = false
    updatePresetModifiedState()
}

private fun MainActivity.resolveActivePresetId(keepSelection: Boolean): String? {
    var presetId = if (keepSelection) PresetPrefs.activePresetId(this) else null
    if (presetId != null && presetOptions.none { it.id == presetId }) {
        presetId = null
    }
    if (presetId == null) {
        presetId = findMatchingPresetId() ?: PresetManager.SYSTEM_BASE_ID
        PresetPrefs.setActivePresetId(this, presetId)
    }
    activePresetId = presetId
    return presetId
}

private fun MainActivity.findMatchingPresetId(): String? {
    val currentPayload = PrefsJson.buildPayload(this)
    return presetOptions.firstOrNull { preset ->
        val payload = PresetManager.readPresetPayload(this, preset) ?: return@firstOrNull false
        PrefsJson.payloadEquals(payload, currentPayload)
    }?.id
}

private fun MainActivity.applyPreset(preset: PresetManager.Preset) {
    val payload = PresetManager.readPresetPayload(this, preset)
    if (payload == null) {
        showToast(R.string.preset_load_failed)
        return
    }
    isApplyingPreset = true
    val applied = try {
        PrefsJson.applyPayload(this, payload)
    } finally {
        isApplyingPreset = false
    }
    if (!applied) {
        showToast(R.string.preset_load_failed)
        return
    }
    PresetPrefs.setActivePresetId(this, preset.id)
    activePresetId = preset.id
    syncUiFromPrefs()
    syncDisplaySelectionFromPrefs()
    notifyOverlaySettingsChangedFull()
    updatePresetModifiedState()
}

private fun MainActivity.saveActivePreset() {
    val presetId = activePresetId ?: return
    val preset = presetOptions.firstOrNull { it.id == presetId } ?: return
    if (preset.source == PresetManager.Source.SYSTEM) {
        showSavePresetDialog()
        return
    }
    val payload = PrefsJson.buildPayload(this)
    val savedPresetId = preset.file?.let { file ->
        runCatching {
            file.writeText(payload.toString(2), Charsets.UTF_8)
            PresetManager.presetIdForFile(file)
        }.getOrNull()
    } ?: PresetManager.saveUserPreset(this, preset.name, payload)
    if (savedPresetId == null) {
        showToast(R.string.preset_save_failed)
        return
    }
    PresetPrefs.setActivePresetId(this, savedPresetId)
    refreshPresets(keepSelection = true)
    showToast(R.string.preset_save_success)
}

private fun MainActivity.showSavePresetDialog() {
    val input = EditText(this).apply {
        hint = getString(R.string.preset_name_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(getColor(R.color.white))
    }
    val container = FrameLayout(this).apply {
        val horizontalPadding = (resources.displayMetrics.density * 16).roundToInt()
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }
    val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
        .setTitle(R.string.preset_save_title)
        .setView(container)
        .setPositiveButton(R.string.preset_save_button) { _, _ ->
            val name = normalizePresetName(input.text?.toString().orEmpty())
            if (name == null) {
                showToast(R.string.preset_name_invalid)
                return@setPositiveButton
            }
            val payload = PrefsJson.buildPayload(this)
            val presetId = PresetManager.saveUserPreset(this, name, payload)
            if (presetId == null) {
                showToast(R.string.preset_save_failed)
                return@setPositiveButton
            }
            PresetPrefs.setActivePresetId(this, presetId)
            refreshPresets(keepSelection = true)
            showToast(R.string.preset_save_success)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.setOnShowListener {
        val width = (resources.displayMetrics.widthPixels * 0.9f).roundToInt()
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}

private fun MainActivity.normalizePresetName(raw: String): String? {
    val trimmed = raw.trim()
    val baseName = if (trimmed.endsWith(".json", ignoreCase = true)) {
        trimmed.dropLast(5).trim()
    } else {
        trimmed
    }
    if (baseName.isBlank() || baseName == "." || baseName == "..") {
        return null
    }
    val invalidChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    if (invalidChars.containsMatchIn(baseName)) {
        return null
    }
    return baseName
}

internal fun MainActivity.updatePresetModifiedState() {
    val presetId = activePresetId
    var preset = presetOptions.firstOrNull { it.id == presetId }
    if (preset == null) {
        setSavePresetEnabled(false)
        presetAdapter?.setModifiedPresetId(null)
        return
    }
    var payload = PresetManager.readPresetPayload(this, preset)
    if (payload == null) {
        val currentPayload = PrefsJson.buildPayload(this)
        val fallbackPreset = presetOptions.firstOrNull { candidate ->
            val candidatePayload = PresetManager.readPresetPayload(this, candidate) ?: return@firstOrNull false
            PrefsJson.payloadEquals(candidatePayload, currentPayload)
        } ?: presetOptions.firstOrNull { candidate ->
            PresetManager.readPresetPayload(this, candidate) != null
        }
        if (fallbackPreset != null && fallbackPreset.id != preset.id) {
            preset = fallbackPreset
            payload = PresetManager.readPresetPayload(this, fallbackPreset)
            PresetPrefs.setActivePresetId(this, fallbackPreset.id)
            activePresetId = fallbackPreset.id
            val selectedIndex = presetOptions.indexOfFirst { it.id == fallbackPreset.id }
            if (selectedIndex >= 0) {
                isPresetSelectionSyncing = true
                presetSpinner.setSelection(selectedIndex)
                isPresetSelectionSyncing = false
            }
        }
    }
    if (payload == null) {
        setSavePresetEnabled(false)
        presetAdapter?.setModifiedPresetId(null)
        return
    }
    val currentPayload = PrefsJson.buildPayload(this)
    val modified = !PrefsJson.payloadEquals(payload, currentPayload)
    setSavePresetEnabled(modified && preset.source == PresetManager.Source.USER)
    presetAdapter?.setModifiedPresetId(if (modified) preset.id else null)
}

private fun MainActivity.setSavePresetEnabled(enabled: Boolean) {
    savePresetButton.isEnabled = enabled
    savePresetButton.alpha = if (enabled) 1f else 0.4f
}

internal fun MainActivity.syncPresetSelectionFromPrefs() {
    val presetId = PresetPrefs.activePresetId(this) ?: return
    if (presetId == activePresetId) return
    val index = presetOptions.indexOfFirst { it.id == presetId }
    if (index < 0) {
        refreshPresets(keepSelection = true)
        return
    }
    activePresetId = presetId
    if (presetSpinner.selectedItemPosition != index) {
        isPresetSelectionSyncing = true
        presetSpinner.setSelection(index)
        isPresetSelectionSyncing = false
    }
    updatePresetModifiedState()
}

private fun MainActivity.syncDisplaySelectionFromPrefs() {
    if (displayOptions.isEmpty()) return
    val displayId = OverlayPrefs.displayId(this)
    val index = displayOptions.indexOfFirst { it.id == displayId }
    if (index >= 0 && displaySpinner.selectedItemPosition != index) {
        isDisplaySelectionSyncing = true
        displaySpinner.setSelection(index)
        isDisplaySelectionSyncing = false
    }
}

private fun MainActivity.notifyOverlaySettingsChangedFull() {
    val navPos = OverlayPrefs.navPositionDp(this)
    val arrowPos = OverlayPrefs.arrowPositionDp(this)
    val speedPos = OverlayPrefs.speedPositionDp(this)
    val hudSpeedPos = OverlayPrefs.hudSpeedPositionDp(this)
    val roadCameraPos = OverlayPrefs.roadCameraPositionDp(this)
    val trafficLightPos = OverlayPrefs.trafficLightPositionDp(this)
    val speedometerPos = OverlayPrefs.speedometerPositionDp(this)
    val clockPos = OverlayPrefs.clockPositionDp(this)
    val containerPos = OverlayPrefs.containerPositionDp(this)
    val containerSize = OverlayPrefs.containerSizeDp(this)

    notifyOverlaySettingsChanged(
        containerPosition = containerPos,
        containerWidthDp = containerSize.x,
        containerHeightDp = containerSize.y,
        navPosition = navPos,
        navWidthDp = OverlayPrefs.navWidthDp(this),
        arrowPosition = arrowPos,
        speedPosition = speedPos,
        hudSpeedPosition = hudSpeedPos,
        roadCameraPosition = roadCameraPos,
        trafficLightPosition = trafficLightPos,
        speedometerPosition = speedometerPos,
        clockPosition = clockPos,
        navScale = OverlayPrefs.navScale(this),
        navTextScale = OverlayPrefs.navTextScale(this),
        arrowScale = OverlayPrefs.arrowScale(this),
        speedScale = OverlayPrefs.speedScale(this),
        speedTextScale = OverlayPrefs.speedTextScale(this),
        hudSpeedScale = OverlayPrefs.hudSpeedScale(this),
        roadCameraScale = OverlayPrefs.roadCameraScale(this),
        trafficLightScale = OverlayPrefs.trafficLightScale(this),
        speedometerScale = OverlayPrefs.speedometerScale(this),
        clockScale = OverlayPrefs.clockScale(this),
        navAlpha = OverlayPrefs.navAlpha(this),
        arrowAlpha = OverlayPrefs.arrowAlpha(this),
        speedAlpha = OverlayPrefs.speedAlpha(this),
        hudSpeedAlpha = OverlayPrefs.hudSpeedAlpha(this),
        roadCameraAlpha = OverlayPrefs.roadCameraAlpha(this),
        trafficLightAlpha = OverlayPrefs.trafficLightAlpha(this),
        speedometerAlpha = OverlayPrefs.speedometerAlpha(this),
        clockAlpha = OverlayPrefs.clockAlpha(this),
        containerAlpha = OverlayPrefs.containerAlpha(this),
        navEnabled = OverlayPrefs.navEnabled(this),
        arrowEnabled = OverlayPrefs.arrowEnabled(this),
        speedEnabled = OverlayPrefs.speedEnabled(this),
        hudSpeedEnabled = OverlayPrefs.hudSpeedEnabled(this),
        hudSpeedLimitEnabled = OverlayPrefs.hudSpeedLimitEnabled(this),
        hudSpeedLimitAlertEnabled = OverlayPrefs.hudSpeedLimitAlertEnabled(this),
        hudSpeedLimitAlertThreshold = OverlayPrefs.hudSpeedLimitAlertThreshold(this),
        roadCameraEnabled = OverlayPrefs.roadCameraEnabled(this),
        trafficLightEnabled = OverlayPrefs.trafficLightEnabled(this),
        arrowOnlyWhenNoIcon = OverlayPrefs.arrowOnlyWhenNoIcon(this),
        speedLimitAlertEnabled = OverlayPrefs.speedLimitAlertEnabled(this),
        speedLimitAlertThreshold = OverlayPrefs.speedLimitAlertThreshold(this),
        speedometerEnabled = OverlayPrefs.speedometerEnabled(this),
        clockEnabled = OverlayPrefs.clockEnabled(this),
        trafficLightMaxActive = OverlayPrefs.trafficLightMaxActive(this),
        mapEnabled = OverlayPrefs.mapEnabled(this)
    )
}

private fun MainActivity.showToast(messageResId: Int) {
    Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
}


internal class PresetAdapter(
    context: Context,
    private var items: List<PresetManager.Preset>
) : ArrayAdapter<PresetManager.Preset>(context, R.layout.spinner_item, items) {
    private val inflater = LayoutInflater.from(context)
    private var modifiedPresetId: String? = null

    fun updateItems(newItems: List<PresetManager.Preset>) {
        items = newItems
        clear()
        addAll(newItems)
        notifyDataSetChanged()
    }

    fun setModifiedPresetId(presetId: String?) {
        modifiedPresetId = presetId
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): PresetManager.Preset = items[position]

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
        val layoutId = if (dropdown) R.layout.spinner_dropdown_item else R.layout.spinner_item
        val view = convertView ?: inflater.inflate(layoutId, parent, false)
        val label = view.findViewById<TextView>(android.R.id.text1)
        val item = getItem(position)
        val isModified = item.id == modifiedPresetId
        label.text = if (isModified) "${item.name} *" else item.name
        label.setTypeface(label.typeface, if (isModified) Typeface.ITALIC else Typeface.NORMAL)
        return view
    }
}
