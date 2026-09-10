package com.founderhq.events

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.founderhq.events.test.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * A screen that carries text the SDK must never read, next to a control whose
 * label the SDK must record. Every string here is upper case so a widget that
 * transforms its own label (a Button does) cannot hide a leak, or a mismatch,
 * from a substring search.
 */
const val FHQ_TEST_BUTTON_LABEL = "BUTTONLABELMUSTAPPEAR"
const val FHQ_TEST_FIELD_TEXT = "SECRETPASSPHRASEMUSTNOTAPPEAR"
const val FHQ_TEST_FIELD_HINT = "FIELDHINTMUSTNOTAPPEAR"
const val FHQ_TEST_LABEL_TEXT = "PLAINLABELMUSTNOTAPPEAR"
const val FHQ_TEST_BUTTON_DESCRIPTION = "Start checkout"

class ElementCaptureTestActivity : Activity() {
    lateinit var column: LinearLayout
        private set
    lateinit var button: Button
        private set
    lateinit var field: EditText
        private set
    lateinit var plainView: View
        private set

    /** Proves the wrapped window callback still delivers the tap to the app. */
    val buttonClicks = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        column = LinearLayout(this).apply {
            id = R.id.fhq_root_column
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        column.addView(
            TextView(this).apply {
                text = FHQ_TEST_LABEL_TEXT
                height = 220
            },
        )

        button = Button(this).apply {
            id = R.id.fhq_checkout_button
            text = FHQ_TEST_BUTTON_LABEL
            contentDescription = FHQ_TEST_BUTTON_DESCRIPTION
            height = 260
            setOnClickListener { buttonClicks.incrementAndGet() }
        }
        column.addView(button)

        field = EditText(this).apply {
            id = R.id.fhq_secret_field
            hint = FHQ_TEST_FIELD_HINT
            setText(FHQ_TEST_FIELD_TEXT)
            height = 260
            // A soft keyboard would reflow the screen under the test's taps.
            showSoftInputOnFocus = false
        }
        column.addView(field)

        plainView = View(this).apply {
            id = R.id.fhq_plain_view
            setBackgroundColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                400,
            )
        }
        column.addView(plainView)

        setContentView(column)
    }
}
