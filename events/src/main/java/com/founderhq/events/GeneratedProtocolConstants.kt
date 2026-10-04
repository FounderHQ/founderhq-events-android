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
        "\$push_device_registered",
        "\$push_device_removed",
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

    /** Registers a device for push. A control event: never stored as analytics. */
    const val PUSH_DEVICE_REGISTERED_EVENT = "\$push_device_registered"

    /** Removes a device from a person. A control event too. */
    const val PUSH_DEVICE_REMOVED_EVENT = "\$push_device_removed"

    const val PUSH_NOTIFICATION_OPENED_EVENT = "\$push_notification_opened"

    // The $push_ property keys.
    const val PUSH_PROPERTY_TOKEN = "\$push_token"
    const val PUSH_PROPERTY_PROVIDER = "\$push_provider"
    const val PUSH_PROPERTY_PLATFORM = "\$push_platform"
    const val PUSH_PROPERTY_APP_ID = "\$push_app_id"
    const val PUSH_PROPERTY_ENVIRONMENT = "\$push_environment"
    const val PUSH_PROPERTY_ENABLED = "\$push_enabled"
    const val PUSH_PROPERTY_PERMISSION = "\$push_permission"
    const val PUSH_PROPERTY_MESSAGE_ID = "\$push_message_id"
    const val PUSH_PROPERTY_P256DH = "\$push_p256dh"
    const val PUSH_PROPERTY_AUTH = "\$push_auth"
    const val PUSH_PROPERTY_APP_KEY = "\$push_app_key"

    /** The values of $push_provider. */
    val PUSH_PROVIDERS: List<String> = listOf(
        "fcm",
        "apns",
        "expo",
    )

    /** The values of $push_platform. */
    val PUSH_PLATFORMS: List<String> = listOf(
        "ios",
        "android",
        "web",
    )

    /** The values of $push_environment. */
    val PUSH_ENVIRONMENTS: List<String> = listOf(
        "sandbox",
        "production",
    )

    /** The values of $push_permission. */
    val PUSH_PERMISSIONS: List<String> = listOf(
        "authorized",
        "denied",
        "provisional",
        "not_determined",
    )

    /** The key of a push payload that holds the FounderHQ message id. */
    const val PUSH_PAYLOAD_MESSAGE_ID_KEY = "fhqOutboundMessageId"

    /** The key of a push payload that holds the link to open. */
    const val PUSH_PAYLOAD_LINK_KEY = "fhqLink"

    /** The key of a push payload that holds the image URL. */
    const val PUSH_PAYLOAD_IMAGE_URL_KEY = "fhqImageUrl"
}
