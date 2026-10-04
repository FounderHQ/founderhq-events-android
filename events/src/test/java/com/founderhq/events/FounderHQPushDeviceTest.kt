package com.founderhq.events

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class FounderHQPushDeviceTest {
    @Test
    fun registersATokenWithEveryPropertyTheAppGave() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(
            FCM_TOKEN,
            appId = " com.acme.app ",
            enabled = true,
            permission = FounderHQPushPermission.DENIED,
        )
        client.flush()

        val registered = harness.transport.named("\$push_device_registered").single()
        val properties = registered.getJSONObject("properties")
        assertEquals(FCM_TOKEN, properties.getString("\$push_token"))
        assertEquals("fcm", properties.getString("\$push_provider"))
        assertEquals("android", properties.getString("\$push_platform"))
        assertEquals("com.acme.app", properties.getString("\$push_app_id"))
        assertEquals(true, properties.getBoolean("\$push_enabled"))
        assertEquals("denied", properties.getString("\$push_permission"))
        assertFalse(properties.has("\$push_environment"))
        // Device details ride the automatic properties.
        assertEquals("android", properties.getString("\$platform"))
        assertEquals("2.1.0", properties.getString("\$app_version"))
        assertTrue(properties.has("\$device_id"))
        assertEquals("person_a", registered.getString("distinct_id"))
        client.close()
    }

    @Test
    @Config(sdk = [24])
    fun readsWhetherNotificationsAreOnWithoutAPrompt() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(APNS_TOKEN.uppercase(), FounderHQPushProvider.APNS,
            environment = FounderHQPushEnvironment.SANDBOX)
        client.flush()

        val properties = harness.transport.named("\$push_device_registered").single()
            .getJSONObject("properties")
        // Robolectric's notification manager reports notifications as on.
        assertEquals("authorized", properties.getString("\$push_permission"))
        // An APNs token goes out as lowercase hex.
        assertEquals(APNS_TOKEN, properties.getString("\$push_token"))
        assertEquals("apns", properties.getString("\$push_provider"))
        assertEquals("sandbox", properties.getString("\$push_environment"))
        assertFalse(properties.has("\$push_enabled"))
        assertFalse(properties.has("\$push_app_id"))
        client.close()
    }

    @Test
    fun storesTheTokenForAGuestAndSendsNothingUntilAPersonIsIdentified() {
        val harness = PushHarness()
        val first = harness.client()
        first.registerPushToken(FCM_TOKEN, appId = "com.acme.app")
        first.setPushEnabled(true)
        first.close()
        assertTrue(harness.transport.push().isEmpty())
        assertEquals(
            JSONObject()
                .put("token", FCM_TOKEN).put("provider", "fcm")
                .put("appId", "com.acme.app").put("enabled", true).toString(),
            first.persistedState().getJSONObject("push").toString(),
        )

        // The documented integration registers on every launch. A guest still
        // gets no device: not on a start, and not from the call.
        val second = harness.client()
        second.registerPushToken(FCM_TOKEN)
        second.flush()
        assertTrue(harness.transport.push().isEmpty())

        second.identify("person_a")
        second.close()
        assertEquals(
            listOf("\$push_device_registered=person_a"),
            harness.transport.push().map { "${it.getString("event")}=${it.getString("distinct_id")}" },
        )
    }

    @Test
    fun appliesTheServersTokenRules() {
        for (case in PUSH_TOKEN_CASES) {
            val result = founderHqNormalizePushToken(case.token, case.provider)
            if (case.stored == null) {
                assertTrue(case.name, result is FounderHQPushTokenResult.Invalid)
                val reason = (result as FounderHQPushTokenResult.Invalid).reason
                assertTrue(case.name, case.provider.wireValue in reason)
                if (case.token.isNotBlank()) assertFalse(case.name, case.token.trim() in reason)
            } else {
                assertEquals(case.name, FounderHQPushTokenResult.Valid(case.stored), result)
            }
        }
    }

    @Test
    fun aRefusedTokenIsNotStoredNotSentAndTheLogSaysWhy() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        val refused = PUSH_TOKEN_CASES.filter { it.stored == null }
        for (case in refused) client.registerPushToken(case.token, case.provider)
        client.flush()

        assertTrue(harness.transport.push().isEmpty())
        assertFalse(client.persistedState().has("push"))
        val warnings = ShadowLog.getLogs().mapNotNull { it.msg }
            .filter { "registerPushToken ignored the token" in it }
        assertEquals(refused.size, warnings.size)
        refused.zip(warnings).forEach { (case, warning) ->
            assertTrue(case.name, case.provider.wireValue in warning)
            if (case.token.isNotBlank()) assertFalse(case.name, case.token.trim() in warning)
        }
        client.close()
    }

    @Test
    fun theEnumsCarryTheGeneratedProtocolValues() {
        assertEquals(
            FounderHQProtocolConstants.PUSH_PROVIDERS.sorted(),
            FounderHQPushProvider.values().map { it.wireValue }.sorted(),
        )
        assertEquals(
            FounderHQProtocolConstants.PUSH_ENVIRONMENTS.sorted(),
            FounderHQPushEnvironment.values().map { it.wireValue }.sorted(),
        )
        assertEquals(
            FounderHQProtocolConstants.PUSH_PERMISSIONS.sorted(),
            FounderHQPushPermission.values().map { it.wireValue }.sorted(),
        )
        assertTrue("android" in FounderHQProtocolConstants.PUSH_PLATFORMS)
        assertEquals("fhqOutboundMessageId", FounderHQProtocolConstants.PUSH_PAYLOAD_MESSAGE_ID_KEY)
        assertEquals("fhqLink", FounderHQProtocolConstants.PUSH_PAYLOAD_LINK_KEY)
    }

    @Test
    fun registersTheStoredTokenAgainOnEveryStart() {
        val harness = PushHarness()
        val first = harness.client()
        first.identify("person_a")
        first.registerPushToken(EXPO_TOKEN, FounderHQPushProvider.EXPO, appId = "@acme/app")
        first.close()
        assertEquals(1, harness.transport.named("\$push_device_registered").size)

        for (launch in 2..3) {
            val next = harness.client()
            next.readyForCapture()
            next.close()
            val registrations = harness.transport.named("\$push_device_registered")
            // An unchanged registration is sent too: it moves "last seen".
            assertEquals(launch, registrations.size)
            assertEquals("person_a", registrations.last().getString("distinct_id"))
            val properties = registrations.last().getJSONObject("properties")
            assertEquals(EXPO_TOKEN, properties.getString("\$push_token"))
            assertEquals("expo", properties.getString("\$push_provider"))
            assertEquals("@acme/app", properties.getString("\$push_app_id"))
        }
    }

    @Test
    fun replacesARefreshedToken() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.registerPushToken("$FCM_TOKEN-2")
        client.flush()
        assertEquals(
            listOf(
                "\$push_device_registered=$FCM_TOKEN",
                "\$push_device_removed=$FCM_TOKEN",
                "\$push_device_registered=$FCM_TOKEN-2",
            ),
            harness.transport.push().map {
                "${it.getString("event")}=${it.getJSONObject("properties").getString("\$push_token")}"
            },
        )
        assertFalse(client.persistedState().getJSONObject("push").has("pendingRemovals"))
        client.close()
    }

    @Test
    fun registersTheDeviceForThePersonWhoSignsIn() {
        val harness = PushHarness()
        val client = harness.client()
        client.registerPushToken(FCM_TOKEN)
        client.identify("person_a")
        // The same person again changes nothing, so nothing is sent.
        client.identify("person_a")
        client.identify("person_b")
        client.flush()
        assertEquals(
            listOf(
                "\$identify=person_a",
                "\$push_device_registered=person_a",
                "\$identify=person_a",
                "\$identify=person_b",
                "\$push_device_registered=person_b",
            ),
            harness.transport.summary { it.getString("distinct_id") },
        )
        client.close()
    }

    @Test
    fun resetRemovesTheDeviceFromThePersonWhoSignsOutBeforeTheIdentityRotates() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.flush()
        client.capture("left.in.the.queue")
        client.reset()
        client.flush()

        val removed = harness.transport.named("\$push_device_removed").single()
        assertEquals("person_a", removed.getString("distinct_id"))
        assertEquals(FCM_TOKEN, removed.getJSONObject("properties").getString("\$push_token"))
        assertEquals("fcm", removed.getJSONObject("properties").getString("\$push_provider"))
        // A reset still drops every other queued event, as it always has.
        assertTrue(harness.transport.named("left.in.the.queue").isEmpty())
        assertNotEquals("person_a", client.getDistinctId())

        // A second reset has nothing left to remove.
        client.reset()
        client.close()
        assertEquals(1, harness.transport.named("\$push_device_removed").size)

        // The token stays on the device, but the guest gets no registration:
        // not now, not on the next start, and not when the app registers again.
        val before = harness.transport.named("\$push_device_registered").size
        val relaunched = harness.client()
        relaunched.registerPushToken(FCM_TOKEN)
        relaunched.flush()
        assertEquals(before, harness.transport.named("\$push_device_registered").size)
        assertEquals(
            JSONObject().put("token", FCM_TOKEN).put("provider", "fcm").toString(),
            relaunched.persistedState().getJSONObject("push").toString(),
        )

        // The next person to sign in gets the device.
        relaunched.identify("person_b")
        relaunched.close()
        val registrations = harness.transport.named("\$push_device_registered")
        assertEquals(before + 1, registrations.size)
        assertEquals("person_b", registrations.last().getString("distinct_id"))
    }

    @Test
    fun keepsARemovalUntilTheServerAcceptsItAndSendsItFirstOnTheNextStart() {
        val harness = PushHarness()
        val first = harness.client()
        first.identify("person_a")
        first.registerPushToken(FCM_TOKEN)
        first.flush()
        harness.transport.events.clear()

        // The person signs out with no network, and the app is closed.
        harness.transport.online = false
        first.reset()
        first.close()
        assertTrue(harness.transport.events.isEmpty())
        val pending = first.persistedState().getJSONObject("push").getJSONArray("pendingRemovals")
        assertEquals(1, pending.length())
        assertEquals(FCM_TOKEN, pending.getJSONObject(0).getString("token"))
        assertEquals("fcm", pending.getJSONObject(0).getString("provider"))
        assertEquals("person_a", pending.getJSONObject(0).getString("distinctId"))
        val uuid = pending.getJSONObject(0).getString("uuid")
        val signedOutAt = harness.nowMillis
        assertEquals(signedOutAt, pending.getJSONObject(0).getLong("removedAt"))

        // Two days later the queued copy is past its 24 hours. The list is not.
        harness.nowMillis += 2 * 24 * 60 * 60 * 1000L
        harness.transport.online = true
        val second = harness.client()
        second.capture("guest.event")
        second.identify("person_b")
        second.flush()

        val removal = harness.transport.events.first()
        assertEquals("\$push_device_removed", removal.getString("event"))
        assertEquals(uuid, removal.getString("uuid"))
        assertEquals("person_a", removal.getString("distinct_id"))
        assertEquals(FCM_TOKEN, removal.getJSONObject("properties").getString("\$push_token"))
        // The time the person signed out, not the time of this send: the
        // server leaves alone a device that registered again after it.
        assertEquals(isoTimestamp(signedOutAt), removal.getString("timestamp"))
        assertEquals(
            listOf("\$push_device_removed=person_a", "\$push_device_registered=person_b"),
            harness.transport.push().map { "${it.getString("event")}=${it.getString("distinct_id")}" },
        )
        // Accepted: it is not kept, and it is not sent again.
        assertFalse(second.persistedState().getJSONObject("push").has("pendingRemovals"))
        second.close()
        val third = harness.client()
        third.close()
        assertEquals(1, harness.transport.named("\$push_device_removed").size)
    }

    @Test
    fun keepsARemovalTheServerAskedToRetryAndAtMostTenOfThem() {
        val harness = PushHarness()
        harness.transport.result = "retry"
        val client = harness.client()
        for (index in 0 until 12) {
            client.identify("person_$index")
            client.registerPushToken(FCM_TOKEN)
            client.reset()
            client.flush()
        }
        val pending = client.persistedState().getJSONObject("push").getJSONArray("pendingRemovals")
        assertEquals(
            (2 until 12).map { "person_$it" },
            (0 until pending.length()).map { pending.getJSONObject(it).getString("distinctId") },
        )
        client.close()

        harness.transport.result = "ok"
        val relaunched = harness.client()
        relaunched.flush()
        assertEquals(
            (2 until 12).map { "person_$it" },
            harness.transport.named("\$push_device_removed").map { it.getString("distinct_id") },
        )
        assertFalse(relaunched.persistedState().getJSONObject("push").has("pendingRemovals"))
        relaunched.close()
    }

    @Test
    fun theSwitchTravelsWithTheRegistrationAndIsOnlyStoredBeforeOne() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.setPushEnabled(false)
        client.flush()
        assertTrue(harness.transport.push().isEmpty())
        assertEquals(
            JSONObject().put("enabled", false).toString(),
            client.persistedState().getJSONObject("push").toString(),
        )

        client.registerPushToken(FCM_TOKEN)
        client.setPushEnabled(true)
        client.flush()
        assertEquals(
            listOf(false, true),
            harness.transport.named("\$push_device_registered")
                .map { it.getJSONObject("properties").getBoolean("\$push_enabled") },
        )
        client.close()
    }

    @Test
    fun doesNotHandOnePersonsSwitchToTheNextPerson() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN, enabled = false)
        client.flush()
        client.reset()
        assertFalse(client.persistedState().getJSONObject("push").has("enabled"))

        client.identify("person_b")
        client.flush()
        val registrations = harness.transport.named("\$push_device_registered")
        assertEquals(listOf("person_a", "person_b"), registrations.map { it.getString("distinct_id") })
        assertEquals(false, registrations[0].getJSONObject("properties").getBoolean("\$push_enabled"))
        // Left out, so the server default applies: push is on.
        assertFalse(registrations[1].getJSONObject("properties").has("\$push_enabled"))
        client.close()
    }

    @Test
    fun unregisterRemovesTheDeviceAndForgetsTheToken() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.unregisterPushToken()
        client.close()
        val removed = harness.transport.named("\$push_device_removed").single()
        assertEquals("person_a", removed.getString("distinct_id"))

        val relaunched = harness.client()
        relaunched.close()
        assertEquals(1, harness.transport.named("\$push_device_registered").size)
        assertFalse(relaunched.persistedState().has("push"))
    }

    @Test
    fun optOutHoldsRegistrationsBackButStillSendsARemoval() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.flush()
        harness.transport.events.clear()

        client.optOut()
        client.setPushEnabled(false)
        client.registerPushToken(FCM_TOKEN)
        client.flush()
        assertTrue(harness.transport.events.isEmpty())

        // A registration held back while opted out goes with the opt-in.
        client.optIn()
        client.flush()
        assertEquals(
            listOf("\$push_device_registered=false"),
            harness.transport.summary {
                it.getJSONObject("properties").getBoolean("\$push_enabled").toString()
            },
        )
        harness.transport.events.clear()

        client.optOut()
        client.reset()
        client.flush()
        assertEquals(
            listOf("\$push_device_removed=person_a"),
            harness.transport.summary { it.getString("distinct_id") },
        )
        client.close()
    }

    @Test
    fun aQueuedRemovalSurvivesAnOptOut() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.flush()
        harness.transport.events.clear()
        harness.transport.online = false
        client.unregisterPushToken()
        client.capture("dropped.by.opt.out")
        client.optOut()
        harness.transport.online = true
        client.flush()
        assertEquals(
            listOf("\$push_device_removed=person_a"),
            harness.transport.summary { it.getString("distinct_id") },
        )
        assertFalse(client.persistedState().has("push"))
        client.close()
    }

    @Test
    fun pushDeviceEventsSkipBeforeSendAndTheTokenStaysOutOfTheLog() {
        val seen = mutableListOf<String>()
        val harness = PushHarness()
        val client = harness.client(debug = true, beforeSend = { event ->
            seen += event.getString("event")
            if (event.getString("event").startsWith("\$push_device")) null else event
        })
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.registerPushToken("$FCM_TOKEN broken")
        client.capture("custom")
        client.flush()
        client.reset()
        client.flush()
        assertEquals(1, harness.transport.named("\$push_device_registered").size)
        assertEquals(1, harness.transport.named("\$push_device_removed").size)
        assertEquals(listOf("\$identify", "custom"), seen)
        assertTrue(ShadowLog.getLogs().none { FCM_TOKEN in (it.msg ?: "") })
        client.close()
    }

    @Test
    fun aColdStartWithAStoredTokenStillStartsANewSessionOnTheNextRealEvent() {
        val harness = PushHarness()
        val first = harness.client(captureSessions = true)
        first.identify("person_a")
        first.registerPushToken(FCM_TOKEN)
        first.close()
        val before = first.persistedState()
        harness.transport.events.clear()

        // A cold start two hours later: the stored session has expired.
        harness.nowMillis += 2 * 60 * 60 * 1000L
        val second = harness.client(captureSessions = true)
        second.flush()
        // The start registers the device and that is all it does: no session
        // rotates, and nothing counts as activity.
        assertEquals(
            listOf("\$push_device_registered=${before.getString("sessionId")}"),
            harness.transport.summary { it.getString("session_id") },
        )
        assertEquals(before.getString("sessionId"), second.persistedState().getString("sessionId"))
        assertEquals(before.getLong("lastActivityAt"), second.persistedState().getLong("lastActivityAt"))

        second.capture("real.event")
        second.flush()
        val (sessionStart, real) = harness.transport.events.drop(1)
        assertEquals("\$session_start", sessionStart.getString("event"))
        assertEquals("real.event", real.getString("event"))
        assertNotEquals(before.getString("sessionId"), sessionStart.getString("session_id"))
        assertEquals(sessionStart.getString("session_id"), real.getString("session_id"))
        second.close()
    }

    @Test
    fun aPushDeviceEventDoesNotKeepASessionAlive() {
        val harness = PushHarness()
        val client = harness.client(captureSessions = true)
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.capture("first.event")
        val session = client.persistedState().getString("sessionId")

        // Twenty minutes of nothing, a push switch, then twenty more minutes.
        harness.nowMillis += 20 * 60 * 1000L
        client.setPushEnabled(true)
        harness.nowMillis += 20 * 60 * 1000L
        client.capture("second.event")
        client.flush()

        assertEquals(session, harness.transport.named("first.event").single().getString("session_id"))
        assertEquals(
            listOf(session, session),
            harness.transport.named("\$push_device_registered").map { it.getString("session_id") },
        )
        // Forty minutes passed since the person last did something.
        assertEquals(2, harness.transport.named("\$session_start").size)
        val second = harness.transport.named("second.event").single().getString("session_id")
        assertNotEquals(session, second)
        assertEquals(second, harness.transport.named("\$session_start").last().getString("session_id"))
        client.close()
    }

    @Test
    fun anOpenCarriesTheMessageIdAndHandsBackTheLink() {
        val harness = PushHarness()
        val client = harness.client()
        val intent = Intent().putExtras(Bundle().apply {
            putString("fhqOutboundMessageId", "msg_123")
            putString("fhqLink", "acme://orders/42")
            putString("secret", "never sent")
        })
        val link = client.capturePushNotificationOpened(intent, mapOf("campaign" to "shipping"))
        val fromData = client.capturePushNotificationOpened(
            FounderHQPushPayload.from(mapOf("fhqOutboundMessageId" to "msg_456", "body" to "never sent")),
        )
        val linkOnly = client.capturePushNotificationOpened(
            Intent().putExtras(Bundle().apply { putString("fhqLink", "acme://home") }),
        )
        client.flush()

        assertEquals("acme://orders/42", link)
        assertNull(fromData)
        assertEquals("acme://home", linkOnly)
        val opened = harness.transport.named("\$push_notification_opened")
        assertEquals(3, opened.size)
        assertEquals("msg_123", opened[0].getJSONObject("properties").getString("\$push_message_id"))
        assertEquals("shipping", opened[0].getJSONObject("properties").getString("campaign"))
        assertEquals("msg_456", opened[1].getJSONObject("properties").getString("\$push_message_id"))
        assertFalse(opened[2].getJSONObject("properties").has("\$push_message_id"))
        val wire = opened.joinToString { it.toString() }
        assertFalse("never sent" in wire)
        assertFalse("orders/42" in wire)
        assertEquals(
            FounderHQPushPayload(null, null),
            FounderHQPushPayload.from(mapOf("fhqOutboundMessageId" to 7, "fhqLink" to "  ")),
        )
        client.close()
    }

    @Test
    fun anIntentThatIsNotAFounderHQNotificationReportsNoOpen() {
        val harness = PushHarness()
        val client = harness.client()
        // A launcher tap, a deep link, another notification, a rotation.
        assertNull(client.capturePushNotificationOpened(null as Intent?))
        assertNull(client.capturePushNotificationOpened(Intent(Intent.ACTION_MAIN)))
        assertNull(client.capturePushNotificationOpened(
            Intent().putExtras(Bundle().apply { putString("campaign", "spring") }),
            mapOf("campaign" to "spring"),
        ))
        assertNull(client.capturePushNotificationOpened(FounderHQPushPayload.from(emptyMap<String, String>())))
        client.flush()
        assertTrue(harness.transport.named("\$push_notification_opened").isEmpty())

        // The properties-only call is the app's own statement and still reports.
        client.capturePushNotificationOpened(mapOf("campaign" to "spring"))
        client.flush()
        assertEquals(1, harness.transport.named("\$push_notification_opened").size)
        client.close()
    }

    @Test
    fun aClosedClientSendsNothingMore() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN)
        client.flush()
        harness.transport.events.clear()
        harness.transport.online = false
        client.reset()
        client.close()

        // The removal is still in this client's queue, and the network is back.
        harness.transport.online = true
        assertFalse(client.flush())
        assertFalse(client.flushOnSchedule())
        assertTrue(harness.transport.events.isEmpty())
    }

    @Test
    fun aPersonWhoSignsInAgainKeepsTheDeviceARemovalStillWaitedFor() {
        val harness = PushHarness()
        val first = harness.client()
        first.identify("person_a")
        first.registerPushToken(FCM_TOKEN)
        first.flush()
        harness.transport.events.clear()

        // Signs out with no network, then signs in again.
        harness.transport.online = false
        first.reset()
        assertEquals(
            1,
            first.persistedState().getJSONObject("push").getJSONArray("pendingRemovals").length(),
        )
        harness.nowMillis += 60_000
        first.identify("person_a")
        assertFalse(first.persistedState().getJSONObject("push").has("pendingRemovals"))
        first.close()

        harness.transport.online = true
        val second = harness.client()
        second.close()
        assertTrue(harness.transport.named("\$push_device_removed").isEmpty())
        assertEquals(
            setOf("\$push_device_registered=person_a"),
            harness.transport.push().map { "${it.getString("event")}=${it.getString("distinct_id")}" }.toSet(),
        )
    }

    @Test
    fun anOptedOutPersonWhoSignsInAgainKeepsTheDeviceToo() {
        val harness = PushHarness()
        val first = harness.client()
        first.identify("person_a")
        first.registerPushToken(FCM_TOKEN)
        first.flush()
        harness.transport.events.clear()

        harness.transport.online = false
        first.optOut()
        first.reset()
        assertEquals(
            1,
            first.persistedState().getJSONObject("push").getJSONArray("pendingRemovals").length(),
        )
        // Opted out, so the identify itself is not recorded.
        first.identify("person_a")
        assertFalse(first.persistedState().getJSONObject("push").has("pendingRemovals"))
        first.close()

        harness.transport.online = true
        val second = harness.client()
        second.close()
        assertTrue(harness.transport.events.isEmpty())

        // Another person's sign-in leaves the removal alone.
        val third = harness.client()
        third.optIn()
        third.identify("person_c")
        third.registerPushToken(FCM_TOKEN)
        third.flush()
        harness.transport.events.clear()
        harness.transport.online = false
        third.optOut()
        third.reset()
        third.identify("person_d")
        third.close()
        harness.transport.online = true
        val fourth = harness.client()
        fourth.close()
        assertEquals(
            listOf("\$push_device_removed=person_c"),
            harness.transport.summary { it.getString("distinct_id") },
        )
    }

    @Test
    fun aFullQueueKeepsARemoval() {
        val harness = PushHarness()
        val first = harness.client(maxQueueSize = 3)
        first.identify("person_a")
        first.registerPushToken(FCM_TOKEN)
        first.flush()
        harness.transport.events.clear()
        harness.transport.online = false
        first.reset()
        first.close()

        val second = harness.client(maxQueueSize = 3)
        repeat(5) { second.capture("event.$it") }
        harness.transport.online = true
        second.flush()
        // The removal is the oldest event, and it is not the one that goes.
        assertEquals(
            listOf("\$push_device_removed", "event.3", "event.4"),
            harness.transport.events.toList().map { it.getString("event") },
        )
        second.close()
    }

    @Test
    fun aCallThatChangesNothingSendsNoSecondRegistration() {
        val harness = PushHarness()
        val first = harness.client()
        first.identify("person_a")
        first.registerPushToken(FCM_TOKEN, appId = "com.acme.app")
        first.registerPushToken(FCM_TOKEN)
        first.close()
        assertEquals(1, harness.transport.named("\$push_device_registered").size)

        // The documented call on every launch: the start already registered.
        val second = harness.client()
        second.registerPushToken(FCM_TOKEN)
        second.flush()
        assertEquals(2, harness.transport.named("\$push_device_registered").size)

        // A change is sent.
        second.registerPushToken(FCM_TOKEN, permission = FounderHQPushPermission.DENIED)
        second.flush()
        val registrations = harness.transport.named("\$push_device_registered")
        assertEquals(3, registrations.size)
        assertEquals("denied", registrations.last().getJSONObject("properties").getString("\$push_permission"))

        // So is the same token after a removal.
        second.unregisterPushToken()
        second.registerPushToken(FCM_TOKEN, permission = FounderHQPushPermission.DENIED)
        second.close()
        assertEquals(4, harness.transport.named("\$push_device_registered").size)
    }

    @Test
    fun aNewTokenKeepsTheAppIdAndTheEnvironmentOfTheSameProvider() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(
            APNS_TOKEN,
            FounderHQPushProvider.APNS,
            appId = "com.acme.app",
            environment = FounderHQPushEnvironment.SANDBOX,
        )
        // A token refresh passes only the token.
        client.registerPushToken(HEX_64, FounderHQPushProvider.APNS)
        client.flush()
        val refreshed = harness.transport.named("\$push_device_registered").last()
            .getJSONObject("properties")
        assertEquals(HEX_64, refreshed.getString("\$push_token"))
        assertEquals("com.acme.app", refreshed.getString("\$push_app_id"))
        assertEquals("sandbox", refreshed.getString("\$push_environment"))

        // A value the app gives replaces the stored one.
        client.registerPushToken(
            HEX_64,
            FounderHQPushProvider.APNS,
            environment = FounderHQPushEnvironment.PRODUCTION,
        )
        // Another provider starts clean.
        client.registerPushToken(FCM_TOKEN)
        client.flush()
        val (production, fcm) = harness.transport.named("\$push_device_registered").takeLast(2)
            .map { it.getJSONObject("properties") }
        assertEquals("production", production.getString("\$push_environment"))
        assertEquals("com.acme.app", production.getString("\$push_app_id"))
        assertFalse(fcm.has("\$push_app_id"))
        assertFalse(fcm.has("\$push_environment"))
        client.close()
    }

    @Test
    fun identifyingAnotherPersonWithoutAResetClearsTheSwitch() {
        val harness = PushHarness()
        val client = harness.client()
        client.identify("person_a")
        client.registerPushToken(FCM_TOKEN, enabled = false)
        client.identify("person_b")
        client.flush()
        assertFalse(client.persistedState().getJSONObject("push").has("enabled"))
        val registrations = harness.transport.named("\$push_device_registered")
        assertEquals(listOf("person_a", "person_b"), registrations.map { it.getString("distinct_id") })
        assertEquals(false, registrations[0].getJSONObject("properties").getBoolean("\$push_enabled"))
        assertFalse(registrations[1].getJSONObject("properties").has("\$push_enabled"))
        client.close()
    }

    @Test
    @Config(sdk = [24])
    fun extrasThatCannotBeReadReportNoOpenAndDoNotThrow() {
        // An intent from another app with a Parcelable this app does not know.
        // Each read gets a new bundle: the first read is the one that throws.
        fun unreadable(): Bundle {
            val parcel = Parcel.obtain()
            Bundle().apply {
                putString("fhqOutboundMessageId", "msg_123")
                putString("fhqLink", "acme://orders/42")
                putParcelable("foreign", ForeignParcelable())
            }.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return checkNotNull(parcel.readBundle(object : ClassLoader() {
                override fun loadClass(name: String): Class<*> = throw ClassNotFoundException(name)
            }))
        }
        var thrown: Throwable? = null
        try {
            unreadable().getString("fhqOutboundMessageId")
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue("the fixture must be a bundle that throws", thrown is android.os.BadParcelableException)

        assertEquals(FounderHQPushPayload(null, null), FounderHQPushPayload.from(unreadable()))
        val intent = Intent().replaceExtras(unreadable())
        assertEquals(FounderHQPushPayload(null, null), FounderHQPushPayload.from(intent))
        val harness = PushHarness()
        val client = harness.client()
        assertNull(client.capturePushNotificationOpened(Intent().replaceExtras(unreadable())))
        client.flush()
        assertTrue(harness.transport.named("\$push_notification_opened").isEmpty())
        client.close()
    }

    @Test
    fun aStartFromRecentsIsNotASecondOpen() {
        val harness = PushHarness()
        val client = harness.client()
        val extras = Bundle().apply {
            putString("fhqOutboundMessageId", "msg_123")
            putString("fhqLink", "acme://orders/42")
        }
        assertEquals(
            "acme://orders/42",
            client.capturePushNotificationOpened(Intent().putExtras(extras)),
        )
        // The system starts the task again with the same intent and this flag.
        val fromRecents = Intent().putExtras(extras).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
        )
        assertNull(client.capturePushNotificationOpened(fromRecents))
        client.flush()
        assertEquals(1, harness.transport.named("\$push_notification_opened").size)
        client.close()
    }

    @Test
    fun readsAStateAnEarlierBuildOfThisBranchWrote() {
        val harness = PushHarness()
        val first = harness.client()
        first.identify("person_a")
        first.close()
        // The earlier shape: an `attached` flag and no `pendingRemovals`.
        val state = first.persistedState().put(
            "push",
            JSONObject().put("token", FCM_TOKEN).put("provider", "fcm")
                .put("enabled", false).put("attached", true),
        )
        harness.storage.putString("state_v2", state.toString())

        val second = harness.client()
        second.close()
        val properties = harness.transport.named("\$push_device_registered").single()
            .getJSONObject("properties")
        assertEquals(FCM_TOKEN, properties.getString("\$push_token"))
        assertEquals(false, properties.getBoolean("\$push_enabled"))
        assertEquals(
            JSONObject().put("token", FCM_TOKEN).put("provider", "fcm").put("enabled", false).toString(),
            second.persistedState().getJSONObject("push").toString(),
        )
    }
}

private const val FCM_TOKEN = "fcm-registration-token:APA91b-example"
private const val EXPO_TOKEN = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]"
private val APNS_TOKEN = "a1b2c3d4e5f60718293a4b5c6d7e8f90".repeat(2)

/** Stands in for a class that only another app has. */
private class ForeignParcelable : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ForeignParcelable> {
            override fun createFromParcel(source: Parcel) = ForeignParcelable()
            override fun newArray(size: Int) = arrayOfNulls<ForeignParcelable>(size)
        }
    }
}

private class PushTokenCase(
    val name: String,
    val provider: FounderHQPushProvider,
    val token: String,
    /** What the SDK keeps and sends; null means refused. */
    val stored: String?,
)

private val HEX_64 = "0123456789abcdef".repeat(4)

/**
 * The server's token rules as cases. The canonical rules are
 * `normalizePushToken` in apps/web/src/lib/comms/push-devices.ts. The same
 * table is in the iOS suite (PushDeviceTests.swift) and the React Native
 * suite (push.test.ts): change the three together with the server.
 */
private val PUSH_TOKEN_CASES = listOf(
    PushTokenCase("apns 64 hex", FounderHQPushProvider.APNS, HEX_64, HEX_64),
    PushTokenCase("apns uppercase hex is stored lowercase", FounderHQPushProvider.APNS, HEX_64.uppercase(), HEX_64),
    PushTokenCase("apns 200 hex", FounderHQPushProvider.APNS, "ab".repeat(100), "ab".repeat(100)),
    PushTokenCase("apns is trimmed", FounderHQPushProvider.APNS, "  $HEX_64\n", HEX_64),
    PushTokenCase("apns 63 hex", FounderHQPushProvider.APNS, HEX_64.drop(1), null),
    PushTokenCase("apns 201 hex", FounderHQPushProvider.APNS, "a".repeat(201), null),
    PushTokenCase("apns with a letter that is not hex", FounderHQPushProvider.APNS, "g" + HEX_64.drop(1), null),
    PushTokenCase("apns given a Firebase token", FounderHQPushProvider.APNS, "fcm-registration-token:APA91b-example", null),
    PushTokenCase("expo ExponentPushToken", FounderHQPushProvider.EXPO, "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]", "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]"),
    PushTokenCase("expo ExpoPushToken", FounderHQPushProvider.EXPO, "ExpoPushToken[abc-123_XYZ]", "ExpoPushToken[abc-123_XYZ]"),
    PushTokenCase("expo with empty brackets", FounderHQPushProvider.EXPO, "ExponentPushToken[]", null),
    PushTokenCase("expo with no closing bracket", FounderHQPushProvider.EXPO, "ExponentPushToken[abcdef", null),
    PushTokenCase("expo with a bracket inside", FounderHQPushProvider.EXPO, "ExponentPushToken[ab[c]d]", null),
    PushTokenCase("expo with a space inside", FounderHQPushProvider.EXPO, "ExponentPushToken[ab cd]", null),
    PushTokenCase("expo with text after the bracket", FounderHQPushProvider.EXPO, "ExponentPushToken[abcd]x", null),
    PushTokenCase("expo with another prefix", FounderHQPushProvider.EXPO, "PushToken[abcdefgh]", null),
    PushTokenCase("expo given bare hex", FounderHQPushProvider.EXPO, HEX_64, null),
    PushTokenCase("fcm 8 characters", FounderHQPushProvider.FCM, "abcd1234", "abcd1234"),
    PushTokenCase("fcm 4096 characters", FounderHQPushProvider.FCM, "f".repeat(4096), "f".repeat(4096)),
    PushTokenCase("fcm keeps its case and punctuation", FounderHQPushProvider.FCM, "dQw4:APA91b-Example_Token", "dQw4:APA91b-Example_Token"),
    PushTokenCase("fcm is trimmed", FounderHQPushProvider.FCM, " abcd1234 ", "abcd1234"),
    PushTokenCase("fcm 7 characters", FounderHQPushProvider.FCM, "abcd123", null),
    PushTokenCase("fcm 4097 characters", FounderHQPushProvider.FCM, "f".repeat(4097), null),
    PushTokenCase("fcm with a space inside", FounderHQPushProvider.FCM, "abcd 1234", null),
    PushTokenCase("fcm with a tab inside", FounderHQPushProvider.FCM, "abcd\t1234", null),
    PushTokenCase("fcm with a line break inside", FounderHQPushProvider.FCM, "abcd\n1234", null),
    PushTokenCase("fcm empty", FounderHQPushProvider.FCM, "", null),
    // Whitespace is what the server's JavaScript calls whitespace.
    PushTokenCase("fcm with a no-break space inside", FounderHQPushProvider.FCM, "abcd\u00A01234", null),
    PushTokenCase("fcm with U+FEFF inside", FounderHQPushProvider.FCM, "abcd\uFEFF1234", null),
    PushTokenCase("fcm with U+FEFF at the ends is trimmed", FounderHQPushProvider.FCM, "\uFEFFabcd1234\uFEFF", "abcd1234"),
    PushTokenCase("fcm 7 characters and U+FEFF", FounderHQPushProvider.FCM, "abcd123\uFEFF", null),
    PushTokenCase("fcm with U+001C inside is not whitespace", FounderHQPushProvider.FCM, "abcd\u001C1234", "abcd\u001C1234"),
    PushTokenCase("fcm with U+001F at the end is not trimmed", FounderHQPushProvider.FCM, "abcd123\u001F", "abcd123\u001F"),
    PushTokenCase("expo with U+FEFF inside", FounderHQPushProvider.EXPO, "ExponentPushToken[ab\uFEFFcd]", null),
    PushTokenCase("apns with U+FEFF at the start is trimmed", FounderHQPushProvider.APNS, "\uFEFF" + HEX_64, HEX_64),
)

/** One storage, one transport, and one clock, shared by every client a test starts. */
private class PushHarness {
    val storage = PushStorage()
    val transport = PushTransport()
    @Volatile var nowMillis = 1_786_694_000_000
    private val uuids = PushUuidProvider()

    fun client(
        debug: Boolean = false,
        captureSessions: Boolean = false,
        maxQueueSize: Int = 1000,
        beforeSend: ((JSONObject) -> JSONObject?)? = null,
    ) = FounderHQEvents(
        RuntimeEnvironment.getApplication(),
        "fhq_pk_push",
        FounderHQEventsConfig(
            flushAt = 1_000,
            flushIntervalSeconds = 0,
            captureLifecycle = false,
            captureScreens = false,
            captureSessions = captureSessions,
            captureInstallUpdates = false,
            remoteConfig = false,
            maxQueueSize = maxQueueSize,
            debug = debug,
            beforeSend = beforeSend,
        ),
        FounderHQEventsDependencies(
            clock = FounderHQClock { nowMillis },
            uuid = uuids,
            storage = storage,
            transport = transport,
            platformFacts = FounderHQPlatformFactsProvider { mapOf("\$app_version" to "2.1.0") },
        ),
    )
}

private class PushUuidProvider : FounderHQUuidProvider {
    private var counter = 0
    @Synchronized
    override fun uuid(): String = "00000000-0000-4000-8000-%012d".format(++counter)
    override fun uuidV7(nowMillis: Long): String = founderHqUuidV7(nowMillis)
}

private class PushStorage : FounderHQStorage {
    private val values = mutableMapOf<String, String>()
    @Synchronized
    override fun getString(key: String): String? = values[key]
    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Answers every batch while online and keeps each accepted event in the order it left. */
private class PushTransport : FounderHQTransport {
    val events: MutableList<JSONObject> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var online = true
    /** What ingest answers for every event: "ok" or "retry". */
    @Volatile var result = "ok"

    fun named(name: String): List<JSONObject> =
        events.toList().filter { it.getString("event") == name }

    fun push(): List<JSONObject> =
        events.toList().filter { it.getString("event").startsWith("\$push_device") }

    fun summary(field: (JSONObject) -> String): List<String> =
        events.toList().map { "${it.getString("event")}=${field(it)}" }

    override fun send(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): FounderHQTransportResponse {
        if (!online) throw IOException("transport is down")
        val batch = JSONObject(body).getJSONArray("batch")
        val results = JSONObject()
        for (index in 0 until batch.length()) {
            val event = batch.getJSONObject(index)
            if (result == "ok") events += event
            results.put(event.getString("uuid"), JSONObject().put("result", result))
        }
        return FounderHQTransportResponse(202, JSONObject().put("results", results).toString())
    }
}
