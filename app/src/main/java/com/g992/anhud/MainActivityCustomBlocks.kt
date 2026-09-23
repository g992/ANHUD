package com.g992.anhud

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal fun MainActivity.setupCustomBlocksUi() {
    CustomBlockStatusStore.register(customBlockStatusListener)
    customBlocksGlobalSwitch.setOnCheckedChangeListener { _, checked ->
        if (isSyncingUi) return@setOnCheckedChangeListener
        val current = customBlockRepository.load()
        if (!checked) {
            customBlockRepository.update { it.copy(enabled = false) }
            refreshCustomBlocksUi()
            return@setOnCheckedChangeListener
        }
        val enabledBlocks = current.blocks.filter { it.enabled }
        validateCustomBlocks(enabledBlocks) { result ->
            result.onSuccess {
                customBlockRepository.update { it.copy(enabled = true) }
                if (OverlayPrefs.isEnabled(this) && android.provider.Settings.canDrawOverlays(this)) {
                    ContextCompat.startForegroundService(this, android.content.Intent(this, HudBackgroundService::class.java))
                }
            }.onFailure { error ->
                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
            }
            refreshCustomBlocksUi()
        }
    }
    customBlocksAddButton.setOnClickListener {
        val document = customBlockRepository.load()
        if (document.blocks.size >= CustomBlocksContract.MAX_BLOCKS) {
            Toast.makeText(
                this,
                "Можно создать не более ${CustomBlocksContract.MAX_BLOCKS} субблоков",
                Toast.LENGTH_LONG
            ).show()
        } else {
            showCustomBlockEditor(null)
        }
    }
    refreshCustomBlocksUi()
}

internal fun MainActivity.refreshCustomBlocksUi() {
    val document = customBlockRepository.load()
    customBlocksCard.visibility = if (document.menuVisible) View.VISIBLE else View.GONE
    isSyncingUi = true
    customBlocksGlobalSwitch.isChecked = document.enabled
    isSyncingUi = false
    customBlocksList.removeAllViews()
    if (document.blocks.isEmpty()) {
        customBlocksList.addView(TextView(this).apply {
            text = getString(R.string.custom_blocks_empty)
            setTextColor(Color.LTGRAY)
            setPadding(0, customDp(8), 0, customDp(8))
        })
        return
    }
    document.blocks.forEach { block -> customBlocksList.addView(createCustomBlockRow(block)) }
}

private fun MainActivity.createCustomBlockRow(block: CustomBlockDefinition): View {
    val status = CustomBlockStatusStore.get(block.id)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(customDp(12), customDp(10), customDp(12), customDp(10))
        background = GradientDrawable().apply {
            setColor(Color.argb(70, 255, 255, 255))
            cornerRadius = customDp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = customDp(8) }

        addView(LinearLayout(this@createCustomBlockRow).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@createCustomBlockRow).apply {
                text = block.name
                setTextColor(Color.WHITE)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SwitchCompat(this@createCustomBlockRow).apply {
                isChecked = block.enabled
                setOnCheckedChangeListener { button, checked ->
                    if (checked == block.enabled) return@setOnCheckedChangeListener
                    button.isEnabled = false
                    if (!checked) {
                        customBlockRepository.upsert(block.copy(enabled = false))
                        refreshCustomBlocksUi()
                    } else {
                        validateCustomBlocks(listOf(block.copy(enabled = true))) { result ->
                            result.onSuccess {
                                customBlockRepository.upsert(block.copy(enabled = true))
                            }.onFailure { error ->
                                Toast.makeText(this@createCustomBlockRow, error.message, Toast.LENGTH_LONG).show()
                            }
                            refreshCustomBlocksUi()
                        }
                    }
                }
            })
        })

        if (status?.error != null) {
            addView(TextView(this@createCustomBlockRow).apply {
                text = getString(R.string.custom_block_error_prefix, status.error)
                setTextColor(Color.rgb(255, 120, 120))
                textSize = 12f
            })
        } else if (!status?.text.isNullOrBlank()) {
            addView(TextView(this@createCustomBlockRow).apply {
                text = status?.text
                setTextColor(Color.LTGRAY)
                textSize = 12f
            })
        }

        addView(LinearLayout(this@createCustomBlockRow).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
            addView(compactButton(getString(R.string.custom_block_edit)) { showCustomBlockEditor(block) })
            addView(compactButton(getString(R.string.custom_block_position)) { showCustomBlockPositionEditor(block) })
            addView(compactButton(getString(R.string.custom_block_delete)) {
                AlertDialog.Builder(this@createCustomBlockRow, R.style.ThemeOverlay_ANHUD_Dialog)
                    .setMessage(getString(R.string.custom_block_delete_confirm, block.name))
                    .setNegativeButton(R.string.custom_block_cancel, null)
                    .setPositiveButton(R.string.custom_block_delete) { _, _ ->
                        customBlockRepository.delete(block.id)
                        refreshCustomBlocksUi()
                    }
                    .show()
            })
        })
    }
}

private fun MainActivity.compactButton(label: String, action: () -> Unit): Button = Button(this).apply {
    text = label
    textSize = 11f
    minWidth = 0
    minimumWidth = 0
    setPadding(customDp(8), 0, customDp(8), 0)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        customDp(42)
    )
    setOnClickListener { action() }
}

private fun MainActivity.showCustomBlockEditor(existing: CustomBlockDefinition?) {
    val runtime = CustomScriptRuntime(applicationContext)
    var iconBase64 = existing?.iconBase64.orEmpty()
    var xDp = existing?.xDp ?: 16f
    var yDp = existing?.yDp ?: 16f
    var scale = existing?.scale ?: 1f
    var alpha = existing?.alpha ?: 1f
    val blockId = existing?.id ?: CustomBlocksContract.newBlockId()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(customDp(20), customDp(8), customDp(20), customDp(8))
    }
    val nameInput = EditText(this).apply {
        hint = getString(R.string.custom_block_name)
        setSingleLine(true)
        setText(existing?.name.orEmpty())
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
    }
    val iconButton = Button(this).apply { text = getString(R.string.custom_block_choose_png) }
    val iconStatus = TextView(this).apply {
        setTextColor(Color.LTGRAY)
        text = if (iconBase64.isBlank()) getString(R.string.custom_block_png_required) else "PNG сохранён в настройках"
    }
    val scriptInput = EditText(this).apply {
        hint = getString(R.string.custom_block_script)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setText(existing?.script ?: CUSTOM_BLOCK_EXAMPLES.first())
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
    }
    val examples = SpinnerWithLabel(this, getString(R.string.custom_block_examples), CUSTOM_BLOCK_EXAMPLES)
    val checkButton = Button(this).apply { text = getString(R.string.custom_block_check) }
    val resultText = TextView(this).apply {
        setTextColor(Color.LTGRAY)
        text = existing?.let { CustomBlockStatusStore.get(it.id)?.error }
            ?.let { getString(R.string.custom_block_error_prefix, it) }.orEmpty()
    }
    val preview = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            customDp(88)
        ).apply { topMargin = customDp(8) }
        setPadding(customDp(8), customDp(8), customDp(8), customDp(8))
    }
    val enabledSwitch = SwitchCompat(this).apply {
        text = getString(R.string.custom_block_enabled)
        isChecked = existing?.enabled ?: false
        setTextColor(Color.WHITE)
    }
    val positionButton = Button(this).apply {
        text = getString(R.string.custom_block_position)
    }
    var saveButton: Button? = null

    content.addView(nameInput)
    content.addView(iconButton)
    content.addView(iconStatus)
    content.addView(scriptInput)
    content.addView(examples.label)
    content.addView(examples.spinner)
    content.addView(checkButton)
    content.addView(resultText)
    content.addView(preview)
    content.addView(enabledSwitch)
    content.addView(positionButton)

    fun draft(): CustomBlockDefinition = CustomBlockDefinition(
        id = blockId,
        name = nameInput.text.toString(),
        enabled = enabledSwitch.isChecked,
        script = scriptInput.text.toString(),
        iconBase64 = iconBase64,
        xDp = xDp,
        yDp = yDp,
        scale = scale,
        alpha = alpha
    )

    fun runCheck(afterSuccess: ((CustomBlockDefinition) -> Unit)? = null) {
        val definition = if (afterSuccess != null) runCatching { draft().normalized() }.getOrElse { error ->
            resultText.setTextColor(Color.rgb(255, 120, 120))
            resultText.text = getString(R.string.custom_block_error_prefix, error.message)
            return
        } else null
        val script = runCatching {
            definition?.script ?: CustomScriptPolicy.requireValidShape(scriptInput.text.toString())
        }.getOrElse { error ->
            resultText.setTextColor(Color.rgb(255, 120, 120))
            resultText.text = getString(R.string.custom_block_error_prefix, error.message)
            return
        }
        checkButton.isEnabled = false
        saveButton?.isEnabled = false
        resultText.setTextColor(Color.LTGRAY)
        resultText.text = getString(R.string.custom_block_checking)
        runtime.evaluate(script, previewEnvironment(script)) { result ->
            checkButton.isEnabled = true
            saveButton?.isEnabled = true
            result.onSuccess { evaluation ->
                resultText.setTextColor(Color.rgb(120, 255, 150))
                val rendered = evaluation.text.ifBlank { "скрыт" }
                resultText.text = getString(R.string.custom_block_check_ok, rendered)
                if (definition != null || iconBase64.isNotBlank()) {
                    val previewDefinition = definition ?: runCatching { draft().copy(enabled = true).normalized() }.getOrNull()
                    if (previewDefinition != null) {
                        preview.removeAllViews()
                        CustomBlockViewFactory.create(this, preview).bindPreview(
                            previewDefinition,
                            evaluation.text.ifBlank { "Preview" },
                            visible = true
                        )
                    }
                }
                if (definition != null) afterSuccess?.invoke(definition)
            }.onFailure { error ->
                resultText.setTextColor(Color.rgb(255, 120, 120))
                resultText.text = getString(R.string.custom_block_error_prefix, error.message)
            }
        }
    }

    examples.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (position > 0) scriptInput.setText(CUSTOM_BLOCK_EXAMPLES[position - 1])
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    iconButton.setOnClickListener {
        pickCustomBlockIcon { uri ->
            if (uri == null) return@pickCustomBlockIcon
            runCatching { CustomBlockIconStore.importPng(this, uri) }
                .onSuccess { imported ->
                    iconBase64 = imported.base64
                    iconStatus.text = "PNG: ${imported.width}×${imported.height}, ${imported.byteCount / 1024} КБ"
                }
                .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        }
    }
    checkButton.setOnClickListener { runCheck() }
    positionButton.setOnClickListener {
        val definition = runCatching { draft().normalized() }.getOrElse {
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        showDynamicCustomBlockPositionEditor(definition) { updated ->
            xDp = updated.xDp
            yDp = updated.yDp
            scale = updated.scale
            alpha = updated.alpha
        }
    }

    val scroll = ScrollView(this).apply {
        isFillViewport = false
        addView(content)
    }
    val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
        .setTitle(if (existing == null) R.string.custom_block_editor_new else R.string.custom_block_editor_edit)
        .setView(scroll)
        .setNegativeButton(R.string.custom_block_cancel, null)
        .setPositiveButton(R.string.custom_block_save, null)
        .create()
    dialog.setOnShowListener {
        fitAnhudDialogToScreen(dialog)
        saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        saveButton?.setOnClickListener {
            runCheck { definition ->
                runCatching {
                    customBlockRepository.upsert(definition)
                    CustomBlockIconStore.materialize(customBlockRepository, definition)
                }.onSuccess {
                    refreshCustomBlocksUi()
                    dialog.dismiss()
                }.onFailure { error ->
                    resultText.setTextColor(Color.rgb(255, 120, 120))
                    resultText.text = getString(R.string.custom_block_error_prefix, error.message)
                }
            }
        }
    }
    dialog.setOnDismissListener { runtime.close() }
    dialog.show()
}

private fun MainActivity.validateCustomBlocks(
    blocks: List<CustomBlockDefinition>,
    callback: (Result<Unit>) -> Unit
) {
    if (blocks.isEmpty()) {
        callback(Result.success(Unit))
        return
    }
    val runtime = CustomScriptRuntime(applicationContext)
    fun validateAt(index: Int) {
        if (index >= blocks.size) {
            runtime.close()
            callback(Result.success(Unit))
            return
        }
        val block = blocks[index]
        runtime.evaluate(block.script, previewEnvironment(block.script)) { result ->
            result.onSuccess { validateAt(index + 1) }.onFailure { error ->
                runtime.close()
                callback(Result.failure(IllegalArgumentException("${block.name}: ${error.message}")))
            }
        }
    }
    validateAt(0)
}

private fun previewEnvironment(script: String): CustomScriptEnvironment {
    val now = System.currentTimeMillis()
    val dependencies = CustomScriptPolicy.dependencies(script)
    val sensors = dependencies.filterIsInstance<CustomSourceKey.Sensor>().associate { source ->
        source.sensorId to CustomDataValue(20f, now)
    }
    val intents = dependencies.filterIsInstance<CustomSourceKey.IntentExtra>()
        .groupBy { it.action }
        .mapValues { (_, sources) ->
            sources.associate { source -> source.extraName to CustomDataValue(21.5, now) }
        }
    return CustomScriptEnvironment(
        now = now,
        speed = CustomDataValue(80, now),
        sensors = sensors,
        intents = intents
    )
}

private fun MainActivity.showCustomBlockPositionEditor(block: CustomBlockDefinition) {
    showDynamicCustomBlockPositionEditor(block) { updated ->
        customBlockRepository.upsert(updated)
        refreshCustomBlocksUi()
    }
}

private fun MainActivity.showDynamicCustomBlockPositionEditor(
    block: CustomBlockDefinition,
    onSave: (CustomBlockDefinition) -> Unit
) {
    showDynamicHudBlockPositionEditor(
        DynamicHudBlockPositionConfig(
            blockId = block.id,
            title = getString(R.string.custom_block_position_title, block.name),
            xDp = block.xDp,
            yDp = block.yDp,
            scale = block.scale,
            alpha = block.alpha,
            containerSizeDp = OverlayPrefs.containerSizeDp(this),
            previewFactory = { parent ->
                CustomBlockViewFactory.create(this, parent).apply {
                    bindPreview(
                        block,
                        CustomBlockStatusStore.get(block.id)?.text?.takeIf { it.isNotBlank() } ?: "80 км/ч"
                    )
                }.root
            }
        )
    ) { result ->
        onSave(
            block.copy(
                xDp = result.xDp,
                yDp = result.yDp,
                scale = result.scale,
                alpha = result.alpha
            )
        )
    }
}

private data class SpinnerWithLabel(
    val label: TextView,
    val spinner: android.widget.Spinner
) {
    constructor(activity: MainActivity, labelText: String, examples: List<String>) : this(
        TextView(activity).apply {
            text = labelText
            setTextColor(Color.LTGRAY)
            setPadding(0, activity.customDp(8), 0, 0)
        },
        android.widget.Spinner(activity).apply {
            val values = listOf(activity.getString(R.string.custom_block_choose_example)) + examples
            adapter = object : ArrayAdapter<String>(
                activity,
                android.R.layout.simple_spinner_item,
                values
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    styleSpinnerText(super.getView(position, convertView, parent), dropdown = false)

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    styleSpinnerText(super.getDropDownView(position, convertView, parent), dropdown = true)

                private fun styleSpinnerText(view: View, dropdown: Boolean): View =
                    (view as TextView).apply {
                        setTextColor(if (dropdown) Color.WHITE else Color.LTGRAY)
                        setPadding(
                            activity.customDp(12),
                            activity.customDp(10),
                            activity.customDp(12),
                            activity.customDp(10)
                        )
                        if (dropdown) {
                            setBackgroundColor(ContextCompat.getColor(activity, R.color.dialog_background))
                        }
                    }
            }.apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
    )
}

private fun MainActivity.customDp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

private val CUSTOM_BLOCK_EXAMPLES = listOf(
    "car.speed().show()",
    "car.readSensor(1055232).mpsToKmh().format(\"{value} км/ч\", 0).show()",
    "car.readSensor(1055232).format(\"{value} м/с\", 1).hideIfStale(5000).show()",
    "intents.read(\"com.example.CAR_DATA\", \"temperature\").number().format(\"{value} °C\", 1).ttl(5000).show()",
    "car.speed().format(v => v > 120 ? `⚠ ${'$'}{v} км/ч` : `${'$'}{v} км/ч`).show()"
)
