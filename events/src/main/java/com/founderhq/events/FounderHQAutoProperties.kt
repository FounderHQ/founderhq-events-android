package com.founderhq.events

import android.content.Context
import android.content.res.Configuration
import android.view.View

/** The captured control and its first `$elements` entry are null for screens. */
data class FounderHQAutoPropertiesContext(
    val event: String,
    val screenName: String?,
    val element: View? = null,
    val elementDescription: Map<String, Any?>? = null,
)

/** Adds tap properties to this view and its descendants. Null clears them. */
fun View.setFhqProperties(properties: Map<String, Any?>?) {
    setTag(R.id.fhq_properties, properties?.toMap())
}

internal fun founderHqTapPropertyEntries(view: View): List<Pair<String, Any?>> {
    val entries = mutableListOf<Pair<String, Any?>>()
    var current: View? = view
    while (current != null) {
        val properties = current.getTag(R.id.fhq_properties) as? Map<*, *>
        properties?.forEach { (key, value) ->
            if (key is String) entries += key to value
        }
        current = current.parent as? View
    }
    return entries
}

/** Priority comes from entry order; rejected values never reserve a key or slot. */
internal fun founderHqSanitizeAutoProperties(entries: List<Pair<String, Any?>>): Map<String, Any> {
    val properties = linkedMapOf<String, Any>()
    for ((key, value) in entries) {
        if (key.isEmpty() || key.length > 64 || key.startsWith("$") || key in properties) continue
        val safe = when (value) {
            is String -> founderHqTruncateAutoProperty(value.trim())
            is Boolean -> value
            is Number -> value.takeIf { it.toDouble().isFinite() }
            else -> null
        } ?: continue
        properties[key] = safe
        if (properties.size == 20) break
    }
    return properties
}

internal fun founderHqColorScheme(context: Context): String? = try {
    when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> "dark"
        Configuration.UI_MODE_NIGHT_NO -> "light"
        else -> null
    }
} catch (_: Throwable) {
    null
}

/** Limit UTF-16 length without leaving half of a supplementary character. */
private fun founderHqTruncateAutoProperty(value: String): String {
    if (value.length <= 200) return value
    val end = if (value[199].isHighSurrogate() && value[200].isLowSurrogate()) 199 else 200
    return value.substring(0, end)
}
