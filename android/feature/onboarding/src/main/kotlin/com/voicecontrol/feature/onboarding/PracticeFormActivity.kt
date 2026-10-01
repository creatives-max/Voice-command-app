package com.voicecontrol.feature.onboarding

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.voicecontrol.core.accessibility.AccessibilityBridge
import com.voicecontrol.core.accessibility.AccessibilityStatus

/**
 * A plain form made of standard Android views, so VoiceControl's accessibility service reads and fills it
 * exactly like a form in any other app. Values typed here are never stored.
 */
class PracticeFormActivity : Activity() {

    private lateinit var fields: List<EditText>
    private lateinit var serviceHint: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(javaClass.name == AccessibilityBridge.PRACTICE_FORM_ACTIVITY)
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.vc_practice_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.vc_practice_intro)
            setPadding(0, dp(8), 0, dp(16))
        })
        serviceHint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = getString(R.string.vc_practice_service_off) })
            addView(Button(context).apply {
                text = getString(R.string.vc_practice_open_settings)
                setOnClickListener { startActivity(AccessibilityStatus.settingsIntent()) }
            })
        }
        column.addView(serviceHint)
        fields = listOf(
            field(column, R.id.practice_name, R.string.vc_practice_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            field(column, R.id.practice_phone, R.string.vc_practice_phone, InputType.TYPE_CLASS_PHONE),
            field(column, R.id.practice_email, R.string.vc_practice_email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            field(column, R.id.practice_city, R.string.vc_practice_city, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS),
        )
        column.addView(Button(this).apply {
            id = R.id.practice_submit
            text = getString(R.string.vc_practice_submit)
            setOnClickListener { finishPractice() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        setContentView(ScrollView(this).apply { addView(column, MATCH_PARENT, WRAP_CONTENT) })
    }

    override fun onResume() {
        super.onResume()
        serviceHint.visibility = if (AccessibilityStatus.isEnabledInSettings(this)) View.GONE else View.VISIBLE
    }

    private fun field(parent: LinearLayout, viewId: Int, label: Int, type: Int): EditText {
        val labelView = TextView(this).apply {
            text = getString(label)
            setPadding(0, dp(12), 0, dp(4))
        }
        val input = EditText(this).apply {
            id = viewId
            hint = getString(label)
            inputType = type
            setSingleLine(true)
        }
        labelView.labelFor = viewId
        parent.addView(labelView)
        parent.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return input
    }

    private fun finishPractice() {
        val filled = PracticeForm.filledCount(fields.map { it.text?.toString() })
        AlertDialog.Builder(this)
            .setTitle(R.string.vc_practice_done_title)
            .setMessage(getString(R.string.vc_practice_done_body, filled))
            .setPositiveButton(R.string.vc_practice_done_ok) { _, _ -> finish() }
            .show()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

/** Practice form rules (no values are kept). */
object PracticeForm {
    fun filledCount(values: List<String?>): Int = values.count { !it.isNullOrBlank() }
}
