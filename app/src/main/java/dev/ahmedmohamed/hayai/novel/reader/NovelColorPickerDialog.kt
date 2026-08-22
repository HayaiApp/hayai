package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.slider.Slider
import eu.kanade.tachiyomi.R

internal object NovelColorPickerDialog {
    fun show(
        context: Context,
        @StringRes titleRes: Int,
        initialColor: Int,
        defaultColor: Int,
        onConfirm: (Int) -> Unit,
        onDefault: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()

        val preview = View(context).apply {
            minimumHeight = 64.dp()
        }
        val hexInput = EditText(context).apply {
            hint = context.getString(R.string.hayai_novel_reader_color_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setSingleLine(true)
        }
        val sliders = IntArray(3)
        val valueLabels = arrayOfNulls<TextView>(3)
        val sliderViews = arrayOfNulls<Slider>(3)
        var synchronizing = false

        fun resolvedColor(): Int = Color.rgb(sliders[0], sliders[1], sliders[2])
        fun updatePreview() {
            preview.backgroundTintList = ColorStateList.valueOf(resolvedColor())
        }
        fun updateFromColor(color: Int, updateHex: Boolean) {
            synchronizing = true
            sliders[0] = Color.red(color)
            sliders[1] = Color.green(color)
            sliders[2] = Color.blue(color)
            sliders.indices.forEach { index ->
                sliderViews[index]?.value = sliders[index].toFloat()
                valueLabels[index]?.text = sliders[index].toString()
            }
            if (updateHex) hexInput.setText(NovelThemeColors.formatRgb(color))
            updatePreview()
            synchronizing = false
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 8.dp(), 24.dp(), 0)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64.dp()).apply { bottomMargin = 12.dp() })
            addView(hexInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val labels = intArrayOf(R.string.hayai_novel_reader_red, R.string.hayai_novel_reader_green, R.string.hayai_novel_reader_blue)
        labels.indices.forEach { index ->
            val valueLabel = TextView(context).apply { gravity = Gravity.END }
            valueLabels[index] = valueLabel
            content.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply { setText(labels[index]) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(valueLabel)
                },
            )
            content.addView(
                Slider(context).apply {
                    valueFrom = 0f
                    valueTo = 255f
                    stepSize = 1f
                    addOnChangeListener { _, value, fromUser ->
                        if (!synchronizing && fromUser) {
                            sliders[index] = value.toInt()
                            valueLabel.text = sliders[index].toString()
                            updatePreview()
                            synchronizing = true
                            hexInput.setText(NovelThemeColors.formatRgb(resolvedColor()))
                            synchronizing = false
                        }
                    }
                    sliderViews[index] = this
                },
            )
        }

        val initial = initialColor.takeUnless { it == 0 } ?: defaultColor
        updateFromColor(initial, updateHex = true)
        hexInput.doAfterTextChanged { editable ->
            if (!synchronizing) {
                NovelThemeColors.parseRgb(editable?.toString().orEmpty())?.let { color ->
                    hexInput.error = null
                    updateFromColor(color, updateHex = false)
                }
            }
        }

        val dialog =
            AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.hayai_novel_reader_use_theme_default, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = NovelThemeColors.parseRgb(hexInput.text?.toString().orEmpty())
                if (parsed == null) {
                    hexInput.error = context.getString(R.string.hayai_novel_reader_color_error)
                } else {
                    onConfirm(parsed)
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                onDefault()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
