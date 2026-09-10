// GENERATED from @founderhq/events-core src/protocol.ts — do not edit.
// Regenerate with: pnpm --filter @founderhq/events-core generate:native
package com.founderhq.events

internal object FounderHQProtocolConstants {
    val RESERVED_EVENT_NAMES: List<String> = listOf(
        "\$identify",
        "\$groupidentify",
        "\$set",
        "\$pageview",
        "\$pageleave",
        "\$session_start",
        "\$screen",
        "\$autocapture",
        "\$rageclick",
        "\$dead_click",
        "\$outbound_click",
        "\$web_vitals",
        "\$application_installed",
        "\$application_updated",
        "\$application_opened",
        "\$application_backgrounded",
        "\$push_notification_opened",
    )

    val CAMPAIGN_PROPERTIES: List<String> = listOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "gclid",
        "gad_source",
        "gclsrc",
        "dclid",
        "gbraid",
        "wbraid",
        "fbclid",
        "msclkid",
        "twclid",
        "li_fat_id",
        "mc_cid",
        "igshid",
        "ttclid",
        "rdt_cid",
        "epik",
        "qclid",
        "sccid",
        "irclid",
        "_kx",
    )

    val ILLEGAL_DISTINCT_IDS: List<String> = listOf(
        "",
        "anonymous",
        "guest",
        "id",
        "undefined",
        "null",
        "none",
        "nil",
        "nan",
        "[object object]",
        "\"undefined\"",
        "\"null\"",
    )

    /** Longest element text an event may carry. */
    const val ELEMENT_TEXT_MAX_LENGTH = 255

    /** How many ancestors of the tapped element travel with it. */
    const val ELEMENT_ANCESTOR_LIMIT = 5

    /** Taps on one element inside the window that make a rage tap. */
    const val RAGE_TAP_COUNT = 3

    /** The rage-tap window, and the quiet time before a second one may fire. */
    const val RAGE_WINDOW_MILLIS = 1000L
}
