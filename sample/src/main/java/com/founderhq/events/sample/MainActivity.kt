package com.founderhq.events.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Events sample"
        setContentView(Button(this).apply {
            text = "Capture signup"
            setOnClickListener {
                (application as SampleApplication).events.capture(
                    "signup.completed",
                    mapOf("source" to "android_sample"),
                )
            }
        })
    }
}
