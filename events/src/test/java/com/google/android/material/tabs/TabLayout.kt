package com.google.android.material.tabs

import android.content.Context
import android.widget.LinearLayout

/**
 * Stands in for the Material tab bar, which this SDK deliberately does not
 * depend on.
 *
 * The SDK finds the real class by name and reads it by reflection, so a class
 * with the same name and the same two methods exercises exactly the path a
 * Material app takes. Nothing in `main` refers to this file.
 */
class TabLayout(context: Context) : LinearLayout(context) {
    class Tab(private val label: CharSequence?) {
        fun getText(): CharSequence? = label
    }

    private val tabs = mutableListOf<Tab>()

    /** -1 while nothing is selected, exactly as Material reports it. */
    var selectedTabPosition: Int = -1

    fun addTab(label: CharSequence?) {
        tabs += Tab(label)
    }

    fun getTabAt(position: Int): Tab? = tabs.getOrNull(position)
}
