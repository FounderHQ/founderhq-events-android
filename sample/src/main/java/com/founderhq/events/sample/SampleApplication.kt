package com.founderhq.events.sample

import android.app.Application
import com.founderhq.events.FounderHQEvents

class SampleApplication : Application() {
    lateinit var events: FounderHQEvents
    override fun onCreate() {
        super.onCreate()
        events = FounderHQEvents(this, "fhq_pk_replace_me")
    }
}
