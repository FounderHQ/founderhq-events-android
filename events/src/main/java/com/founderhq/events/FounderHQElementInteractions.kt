package com.founderhq.events

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import java.util.Locale

/**
 * Element interaction capture, and the privacy rules that govern it.
 *
 * The SDK reads a touched view for semantic facts: the view class, the
 * resource entry name the developer wrote in the layout, the content
 * description, and the position of the view in the hierarchy.
 *
 * It also reads the label of a control, and only of a control. A `Button` and
 * its subclasses (a `MaterialButton`, a `Switch`, a `CheckBox`, a
 * `RadioButton`) report their own text, and a Material `TabLayout` reports the
 * label of the selected tab. Every other view reports no text at all: a plain
 * `TextView` is a label the app wrote for the user, not a control the user
 * pressed, so it stays out of the event.
 *
 * The SDK never reads an `EditText`, its contents, or its hint. It never reads
 * a descendant's text in place of the view's own, never reads tooltips or view
 * tags other than the opt-out marker, and never reads touch coordinates.
 * Coordinates arrive with the touch, resolve the view, and are dropped.
 *
 * Text that does get captured is whitespace-normalised, scrubbed of tokens
 * that look like a credit card number or a social security number, and
 * truncated at [MAX_CAPTURED_TEXT_LENGTH] characters.
 *
 * A view (or any of its parents) tagged `fhq-no-capture` is skipped entirely.
 */

/** `android:tag="fhq-no-capture"` opts a view and its children out of capture. */
internal const val FOUNDERHQ_NO_CAPTURE_TAG = "fhq-no-capture"

private const val MAX_ELEMENTS = FounderHQProtocolConstants.ELEMENT_ANCESTOR_LIMIT
private const val MAX_PATH_SEGMENTS = 8
private const val MAX_TARGET_SEARCH_DEPTH = 12
private const val MAX_TEXT_LENGTH = 200

/** Captured control text is truncated here, the same length the web SDK uses. */
internal const val MAX_CAPTURED_TEXT_LENGTH =
    FounderHQProtocolConstants.ELEMENT_TEXT_MAX_LENGTH

/** Material is not a dependency of this SDK, so its tab bar is matched by name. */
private const val TAB_LAYOUT_CLASS = "com.google.android.material.tabs.TabLayout"

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * A run of digits that separators may break up, so "4242 4242 4242 4242" and
 * "123-45-6789" are examined as one candidate rather than as loose fragments.
 */
private val SEPARATED_DIGIT_RUN = Regex("(?<![0-9])[0-9][0-9 -]{7,20}[0-9](?![0-9])")

/** Card numbers of the major networks, once separators are removed. */
private val CREDIT_CARD_NUMBER = Regex(
    "^(?:4[0-9]{12}(?:[0-9]{3})?" +
        "|5[1-5][0-9]{14}" +
        "|6(?:011|5[0-9]{2})[0-9]{12}" +
        "|3[47][0-9]{13}" +
        "|3(?:0[0-5]|[68][0-9])[0-9]{11}" +
        "|(?:2131|1800|35[0-9]{3})[0-9]{11})$",
)

/** A US social security number, with or without its dashes. */
private val SOCIAL_SECURITY_NUMBER = Regex("^[0-9]{3}-?[0-9]{2}-?[0-9]{4}$")

/**
 * Forwards every window callback to the app and watches touches on the way
 * through. Android exposes no other hook for taps the app handles itself, so
 * this is the standard place to observe them.
 */
internal class FounderHQWindowCallback(
    val delegate: Window.Callback,
    private val window: Window,
    private val observer: (Window, MotionEvent) -> Unit,
) : Window.Callback by delegate {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        try {
            observer(window, event)
        } catch (_: Throwable) {
            // Analytics never breaks a touch the user made.
        }
        return delegate.dispatchTouchEvent(event)
    }
}

/**
 * Finds the deepest visible view under a point, where the point is relative to
 * [root]. A window callback reports touches in window coordinates, which are
 * the decor view's own coordinates, so no screen offset is involved.
 */
internal fun founderHqTouchedView(root: View, x: Float, y: Float): View? {
    if (root.visibility != View.VISIBLE) return null
    if (x < 0f || y < 0f || x > root.width || y > root.height) return null
    if (root is ViewGroup) {
        // Later children draw on top, so they win the touch.
        for (index in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(index)
            val childX = x + root.scrollX - child.left - child.translationX
            val childY = y + root.scrollY - child.top - child.translationY
            founderHqTouchedView(child, childX, childY)?.let { return it }
        }
    }
    return root
}

/**
 * Walks up from the touched view to the control that owns the tap. Returns
 * null when the tap landed on plain layout, so `$autocapture` stays a record
 * of interactions rather than of every touch on the screen.
 */
internal fun founderHqInteractiveTarget(view: View): View? {
    var current: View? = view
    var depth = 0
    while (current != null && depth < MAX_TARGET_SEARCH_DEPTH) {
        if (founderHqIsInteractive(current)) return current
        current = current.parent as? View
        depth++
    }
    return null
}

private fun founderHqIsInteractive(view: View): Boolean =
    view.isClickable || view.isLongClickable || view is android.widget.Checkable

/**
 * True when the touch landed on a field, or inside one.
 *
 * A field is where a person types, and a tap on it is the start of typing
 * rather than a press of a control. The browser, iOS and React Native SDKs all
 * record nothing for it, so this one does the same. An `EditText` is clickable
 * by default, so without this check it would report itself as a control.
 */
internal fun founderHqTouchedEnteredValue(view: View): Boolean {
    var current: View? = view
    var depth = 0
    while (current != null && depth < MAX_TARGET_SEARCH_DEPTH) {
        if (current is EditText) return true
        current = current.parent as? View
        depth++
    }
    return false
}

/** True when the view, or anything above it, carries the opt-out tag. */
internal fun founderHqCaptureBlocked(view: View): Boolean {
    var current: View? = view
    var depth = 0
    while (current != null && depth < MAX_TARGET_SEARCH_DEPTH) {
        if (current.tag as? String == FOUNDERHQ_NO_CAPTURE_TAG) return true
        current = current.parent as? View
        depth++
    }
    return false
}

/** The target and its parents, nearest first, as semantic facts only. */
internal fun founderHqElementMetadata(view: View): List<Map<String, Any?>> {
    val elements = mutableListOf<Map<String, Any?>>()
    var current: View? = view
    while (current != null && elements.size < MAX_ELEMENTS) {
        elements += founderHqViewMetadata(current)
        current = current.parent as? View
    }
    return elements
}

private fun founderHqViewMetadata(view: View): Map<String, Any?> = buildMap {
    put("view_class", founderHqViewClass(view))
    founderHqViewId(view)?.let { put("view_id", it) }
    view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
        put("content_description", it.take(MAX_TEXT_LENGTH))
    }
    founderHqCapturedText(view)?.let { put("text", it) }
}

/**
 * The label of a control, or null for everything else.
 *
 * A control announces what it does, and reading it is what makes an
 * `$autocapture` event legible in a report. A label, a paragraph, or a field
 * is content the user reads or wrote, and none of it belongs in analytics, so
 * this deliberately refuses every view that is not a control.
 */
internal fun founderHqCapturedText(view: View): String? {
    // Stated in code because it is the rule that matters most: a field's
    // contents and its hint never leave the device. (An EditText is a TextView
    // and never a Button, so the check below can only ever agree with the
    // allow-list; it is here so the rule cannot be lost in a later edit.)
    if (view is EditText) return null
    if (founderHqCaptureBlocked(view)) return null
    val raw = when {
        // Covers MaterialButton, and CompoundButton with it: Switch, CheckBox
        // and RadioButton all extend Button.
        view is Button -> view.text
        else -> founderHqSelectedTabText(view)
    } ?: return null
    return founderHqSafeText(raw)
}

/**
 * Normalises whitespace, drops tokens that look like a payment card or a
 * social security number, and truncates. Returns null when nothing is left.
 */
internal fun founderHqSafeText(raw: CharSequence?): String? {
    val normalized = raw?.toString()?.replace(WHITESPACE_RUN, " ")?.trim() ?: return null
    if (normalized.isEmpty()) return null
    val scrubbed = SEPARATED_DIGIT_RUN
        .replace(normalized) { match -> if (founderHqLooksSensitive(match.value)) " " else match.value }
        .replace(WHITESPACE_RUN, " ")
        .trim()
    if (scrubbed.isEmpty()) return null
    return scrubbed.take(MAX_CAPTURED_TEXT_LENGTH)
}

private fun founderHqLooksSensitive(candidate: String): Boolean {
    val digits = candidate.replace(" ", "").replace("-", "")
    return CREDIT_CARD_NUMBER.matches(digits) || SOCIAL_SECURITY_NUMBER.matches(digits)
}

/**
 * Reads the selected tab's label from a Material `TabLayout`, which is the
 * Android answer to the segmented control other platforms capture. Material is
 * not a dependency here, so the class is matched by name and read by
 * reflection; an app without Material never reaches the reflective call.
 */
private fun founderHqSelectedTabText(view: View): CharSequence? {
    if (!founderHqIsNamedClass(view, TAB_LAYOUT_CLASS)) return null
    return try {
        val type = view.javaClass
        val position = type.getMethod("getSelectedTabPosition").invoke(view) as? Int ?: return null
        // -1 while no tab is selected.
        if (position < 0) return null
        val tab = type
            .getMethod("getTabAt", Int::class.javaPrimitiveType)
            .invoke(view, position) ?: return null
        tab.javaClass.getMethod("getText").invoke(tab) as? CharSequence
    } catch (_: Throwable) {
        // A Material version that renamed these is a reason to capture nothing,
        // never a reason to break the app's touch handling.
        null
    }
}

private fun founderHqIsNamedClass(view: View, name: String): Boolean {
    var type: Class<*>? = view.javaClass
    while (type != null) {
        if (type.name == name) return true
        type = type.superclass
    }
    return false
}

/** Hierarchy path from the outermost recorded parent down to the target. */
internal fun founderHqElementPath(view: View): String {
    val segments = mutableListOf<String>()
    var current: View? = view
    while (current != null && segments.size < MAX_PATH_SEGMENTS) {
        val id = founderHqViewId(current)
        segments += founderHqViewClass(current) + if (id != null) "#$id" else ""
        current = current.parent as? View
    }
    return segments.asReversed().joinToString("/")
}

/**
 * Identifies one target across taps, so rage detection can tell repeated taps
 * on one control from taps that wander across the screen. The child index
 * separates identical siblings that carry no resource id.
 */
internal fun founderHqTargetKey(view: View): String {
    val parent = view.parent as? ViewGroup
    val index = parent?.indexOfChild(view) ?: -1
    return founderHqElementPath(view) + "@" + index
}

private fun founderHqViewClass(view: View): String =
    view.javaClass.simpleName.takeIf { it.isNotEmpty() } ?: view.javaClass.name

private fun founderHqViewId(view: View): String? {
    val id = view.id
    if (id == View.NO_ID) return null
    return try {
        view.resources?.getResourceEntryName(id)
    } catch (_: Exception) {
        // Runtime generated ids have no name, and that is not an error.
        null
    }
}

/**
 * Keeps the exact hostnames from `tracingHeaders` and drops everything else.
 * A protocol, path, port, or wildcard would look like it works and quietly
 * match nothing, so an entry that carries one is refused out loud.
 */
internal fun founderHqNormalizeTracingHosts(
    entries: List<String>?,
    onRejected: (String) -> Unit = {},
): Set<String> {
    if (entries.isNullOrEmpty()) return emptySet()
    val hosts = LinkedHashSet<String>()
    for (entry in entries) {
        val host = entry.trim().lowercase(Locale.ROOT)
        val malformed = host.isEmpty() || host.any {
            it == '/' || it == ':' || it == '*' || it == '?' || it == '@' || it.isWhitespace()
        }
        if (malformed) {
            onRejected(entry)
            continue
        }
        hosts += host
    }
    return hosts
}

/** Reads the hostname out of a configured host, with or without a scheme. */
internal fun founderHqHostOf(value: String?): String? {
    val trimmed = value?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (trimmed.isEmpty()) return null
    val authority = trimmed.substringAfter("://", trimmed)
        .substringBefore('/')
        .substringBefore('?')
        .substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }
    return host.takeIf { it.isNotEmpty() }
}
