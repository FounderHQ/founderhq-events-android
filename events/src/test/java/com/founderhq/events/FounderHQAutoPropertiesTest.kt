package com.founderhq.events

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FounderHQAutoPropertiesTest {
    @Test
    fun screensReceiveContextAndExplicitPropertiesWin() {
        val contexts = mutableListOf<FounderHQAutoPropertiesContext>()
        client(autoProperties = {
            contexts += it
            mapOf("section" to "callback", "extra" to "  yes  ", "\$screen_name" to "wrong")
        }).use { events ->
            events.register(mapOf("section" to "registered"))
            events.screen("Pricing", mapOf("section" to "explicit"))
            val props = queued(events).single().getJSONObject("properties")
            assertEquals("Pricing", props.getString("\$screen_name"))
            assertEquals("explicit", props.getString("section"))
            assertEquals("yes", props.getString("extra"))
            assertEquals(FounderHQAutoPropertiesContext("\$screen", "Pricing"), contexts.single())
            events.screen("Checkout")
            assertEquals("callback", queued(events).last().getJSONObject("properties").getString("section"))
        }
    }

    @Test
    fun activityScreensReceiveTheSameHook() {
        val contexts = mutableListOf<FounderHQAutoPropertiesContext>()
        client(autoProperties = { contexts += it; null }).use { events ->
            val controller = Robolectric.buildActivity(Activity::class.java).create()
            try {
                controller.get().title = "Library"
                events.onActivityResumed(controller.get())
                assertEquals(FounderHQAutoPropertiesContext("\$screen", "Library"), contexts.single())
                assertEquals("\$screen", queued(events).single().getString("event"))
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun lifecycleSessionIdentityPushAndCustomCaptureNeverCallAutoProperties() {
        val contexts = mutableListOf<FounderHQAutoPropertiesContext>()
        client(autoProperties = { contexts += it; emptyMap() }).use { events ->
            events.recordLifecycle("active")
            events.capture("\$session_start")
            events.identify("user_42")
            events.capturePushNotificationOpened()
            events.capture("custom")
            // Reserved names passed by the app are still its own capture calls.
            events.capture("\$screen")
            events.capture("\$autocapture")
            assertTrue(contexts.isEmpty())
            assertEquals(7, queued(events).size)
        }
    }

    @Test
    fun windowTapsAndRageClicksReceiveTheControlAndNearestProperties() {
        val contexts = mutableListOf<FounderHQAutoPropertiesContext>()
        client(autoProperties = {
            contexts += it
            linkedMapOf("product_id" to "callback", "callback_only" to true)
        }, beforeSend = { event ->
            if (event.getString("event") != "\$screen") {
                assertEquals("child", event.getJSONObject("properties").getString("product_id"))
            }
            event
        }).use { events ->
            val controller = Robolectric.buildActivity(Activity::class.java).create()
            try {
                val activity = controller.get()
                val root = FrameLayout(activity).apply {
                    setFhqProperties(mapOf("product_id" to "root", "ancestor" to "root"))
                }
                val control = FrameLayout(activity).apply {
                    isClickable = true
                    contentDescription = "Buy"
                    setFhqProperties(mapOf("product_id" to "control"))
                }
                val child = TextView(activity).apply {
                    setFhqProperties(mapOf("product_id" to "child"))
                }
                root.addView(control, FrameLayout.LayoutParams(200, 200))
                control.addView(child, FrameLayout.LayoutParams(200, 200))
                activity.setContentView(root)
                events.onActivityResumed(activity)
                events.screen("Product")
                val decor = activity.window.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, 400, 800)
                // The child's place in the decor's own coordinates, which is
                // what a window callback reports; getLocationInWindow gives 0,0
                // here, which is the action bar.
                val bounds = android.graphics.Rect()
                child.getDrawingRect(bounds)
                (decor as android.view.ViewGroup).offsetDescendantRectToMyCoords(child, bounds)
                val origin = intArrayOf(bounds.left, bounds.top)
                repeat(3) {
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val touch = MotionEvent.obtain(0, 0, action, origin[0] + 10f, origin[1] + 10f, 0)
                        activity.window.callback.dispatchTouchEvent(touch)
                        touch.recycle()
                    }
                }
                val taps = awaitQueue(events, 6).filter { it.getString("event") != "\$screen" }
                assertEquals(listOf("\$autocapture", "\$autocapture", "\$autocapture", "\$rageclick"),
                    taps.map { it.getString("event") })
                val tapContexts = contexts.filter { it.event != "\$screen" }
                assertEquals(taps.size, tapContexts.size)
                taps.zip(tapContexts).forEach { (event, context) ->
                    assertEquals(event.getString("event"), context.event)
                    assertEquals("Product", context.screenName)
                    assertSame(control, context.element)
                    val props = event.getJSONObject("properties")
                    assertEquals("child", props.getString("product_id"))
                    assertEquals("root", props.getString("ancestor"))
                    assertTrue(props.getBoolean("callback_only"))
                    assertFalse(props.has("\$prefers_color_scheme"))
                    val description = props.getJSONArray("\$elements").getJSONObject(0)
                    val expectedDescription = checkNotNull(context.elementDescription)
                    assertEquals(expectedDescription.keys, description.keys().asSequence().toSet())
                    expectedDescription.forEach { (key, value) -> assertEquals(value, description.get(key)) }
                }
            } finally {
                controller.destroy()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun tapBeforeAScreenHasNullScreenContext() {
        var context: FounderHQAutoPropertiesContext? = null
        client(autoProperties = { context = it; null }).use { events ->
            val view = View(RuntimeEnvironment.getApplication()).apply { isClickable = true }
            events.recordElementInteraction(view)
            awaitQueue(events, 1)
            assertNull(context!!.screenName)
            assertSame(view, context!!.element)
        }
    }

    @Test
    fun allAncestorsContributeAndNoCaptureStillSuppressesTheSubtree() {
        val application = RuntimeEnvironment.getApplication()
        val root = FrameLayout(application).apply { setFhqProperties(mapOf("root" to true)) }
        var parent = root
        repeat(20) {
            val child = FrameLayout(application)
            parent.addView(child)
            parent = child
        }
        val view = View(application).apply {
            isClickable = true
            setFhqProperties(mapOf("nearest" to true))
        }
        parent.addView(view)
        assertEquals(mapOf("nearest" to true, "root" to true),
            founderHqSanitizeAutoProperties(founderHqTapPropertyEntries(view)))
        view.setFhqProperties(null)
        assertEquals(mapOf("root" to true), founderHqSanitizeAutoProperties(founderHqTapPropertyEntries(view)))
        root.tag = "fhq-no-capture"
        assertTrue(founderHqCaptureBlocked(view))
        var calls = 0
        client(autoProperties = { calls++; null }).use { events ->
            events.recordElementInteraction(view)
            assertEquals(0, calls)
            assertTrue(queued(events).isEmpty())
        }
    }

    @Test
    fun autoPropertyTruncationKeepsSurrogatePairsWhole() {
        for ((input, expected) in listOf(
            "a".repeat(199) + "😀tail" to "a".repeat(199),
            "a".repeat(198) + "😀tail" to ("a".repeat(198) + "😀"),
            "😀".repeat(101) to "😀".repeat(100),
        )) {
            assertEquals(expected, founderHqSanitizeAutoProperties(listOf("text" to input))["text"])
        }
    }

    @Test
    fun screenAutoPropertiesRunsOutsideLockAndBeforeSendRunsInsideOnCallerThread() {
        lateinit var events: FounderHQEvents
        val caller = Thread.currentThread()
        var autoUnderLock: Boolean? = null
        var sendUnderLock: Boolean? = null
        var autoThread: Thread? = null
        var sendThread: Thread? = null
        fun sdkLock(): Any = FounderHQEvents::class.java.getDeclaredField("lock").apply {
            isAccessible = true
        }.get(events)
        events = client(autoProperties = {
            autoUnderLock = Thread.holdsLock(sdkLock())
            autoThread = Thread.currentThread()
            null
        }, beforeSend = {
            sendUnderLock = Thread.holdsLock(sdkLock())
            sendThread = Thread.currentThread()
            it
        })
        events.use {
            events.screen("Home")
            assertEquals(false, autoUnderLock)
            assertEquals(true, sendUnderLock)
            assertSame(caller, autoThread)
            assertSame(caller, sendThread)
            assertEquals(1, queued(events).size)
        }
    }

    @Test(timeout = 10_000)
    fun tapAutoPropertiesDoesNotWaitForTheSdkLockAndReadsOptOutSnapshot() {
        val hookEntered = CountDownLatch(1)
        val releaseHook = CountDownLatch(1)
        var autoCalls = 0
        val caller = Thread.currentThread()
        var tapHookThread: Thread? = null
        client(autoProperties = {
            assertSame(caller, Thread.currentThread())
            autoCalls++
            null
        }, beforeSend = {
            tapHookThread = Thread.currentThread()
            hookEntered.countDown()
            check(releaseHook.await(5, TimeUnit.SECONDS))
            it
        }).use { events ->
            val view = View(RuntimeEnvironment.getApplication()).apply { isClickable = true }
            try {
                events.recordElementInteraction(view)
                assertTrue(hookEntered.await(5, TimeUnit.SECONDS))
                // The first tap's beforeSend still holds the SDK lock on its executor.
                events.recordElementInteraction(view)
                assertEquals(2, autoCalls)
                assertTrue(tapHookThread !== caller)
            } finally {
                releaseHook.countDown()
            }
            awaitQueue(events, 2)
            events.optOut()
            events.recordElementInteraction(view)
            assertEquals(2, autoCalls)
            events.optIn()
            events.recordElementInteraction(view)
            assertTrue(autoCalls > 2)
            awaitQueue(events, 1)
        }
    }

    @Test
    fun sanitizerKeepsOnlyValidScalarValuesAndTheFirstValidValueForEachKey() {
        val result = founderHqSanitizeAutoProperties(listOf(
            "" to "empty key", "a".repeat(65) to "long key", "\$reserved" to 1,
            "null" to null, "list" to listOf(1), "map" to mapOf("x" to 1),
            "nan" to Double.NaN, "infinity" to Double.POSITIVE_INFINITY,
            "negative_infinity" to Float.NEGATIVE_INFINITY,
            "a".repeat(64) to "valid", "unchanged-key" to "  trimmed  ",
            "long" to ("  " + "x".repeat(201) + "  "), "empty" to "  ",
            "number" to 4.5, "boolean" to false,
            "fallback" to null, "fallback" to "first valid", "fallback" to "later",
        ))
        assertEquals(mapOf(
            "a".repeat(64) to "valid", "unchanged-key" to "trimmed",
            "long" to "x".repeat(200), "empty" to "", "number" to 4.5,
            "boolean" to false, "fallback" to "first valid",
        ), result)
    }

    @Test
    fun twentyKeyBudgetPrioritizesNearestDeclarativeValuesBeforeCallback() {
        val application = RuntimeEnvironment.getApplication()
        val root = FrameLayout(application).apply {
            setFhqProperties((1..25).associate { "parent_$it" to it } + mapOf("clash" to "parent"))
        }
        val view = View(application).apply {
            isClickable = true
            setFhqProperties(linkedMapOf("clash" to "nearest", "invalid" to null))
        }
        root.addView(view)
        client(autoProperties = { (1..25).associate { "callback_$it" to it } }).use { events ->
            events.recordElementInteraction(view)
            val props = awaitQueue(events, 1).single().getJSONObject("properties")
            val extras = props.keys().asSequence().filterNot { it.startsWith("$") }.toList()
            assertEquals(20, extras.size)
            assertEquals("nearest", props.getString("clash"))
            assertEquals(19, props.getInt("parent_19"))
            assertFalse(props.has("parent_20"))
            assertFalse(props.has("callback_1"))
            root.setFhqProperties((1..18).associate { "parent_$it" to it })
            events.recordElementInteraction(view)
            val mixed = awaitQueue(events, 2).last().getJSONObject("properties")
            assertEquals(1, mixed.getInt("callback_1"))
            assertFalse(mixed.has("callback_2"))
            assertEquals(20, mixed.keys().asSequence().count { !it.startsWith("$") })
        }
    }

    @Test
    fun invalidNearestValuesFallThroughToAncestorsThenCallback() {
        val application = RuntimeEnvironment.getApplication()
        val root = FrameLayout(application).apply {
            setFhqProperties(mapOf("parent_wins" to "parent", "callback_wins" to null))
        }
        val view = View(application).apply {
            isClickable = true
            setFhqProperties(mapOf("parent_wins" to null, "callback_wins" to listOf(1)))
        }
        root.addView(view)
        client(autoProperties = { mapOf("parent_wins" to "callback", "callback_wins" to true) }).use { events ->
            events.recordElementInteraction(view)
            val props = awaitQueue(events, 1).single().getJSONObject("properties")
            assertEquals("parent", props.getString("parent_wins"))
            assertTrue(props.getBoolean("callback_wins"))
        }
    }

    @Test
    fun consentAndRemoteConfigGatesSkipHooksButKeepTheCurrentScreen() {
        val contexts = mutableListOf<FounderHQAutoPropertiesContext>()
        client(autoProperties = { contexts += it; mapOf("extra" to true) }).use { events ->
            val view = View(RuntimeEnvironment.getApplication()).apply { isClickable = true }
            events.optOut()
            events.screen("Private")
            events.recordElementInteraction(view)
            assertTrue(contexts.isEmpty())
            events.optIn()
            events.applyRemoteConfig(mapOf("capture_screens" to false))
            events.screen("Current")
            assertTrue(contexts.isEmpty())
            events.recordElementInteraction(view)
            assertEquals("Current", contexts.single().screenName)
            assertTrue(awaitQueue(events, 1).single().getJSONObject("properties").getBoolean("extra"))
            events.applyRemoteConfig(mapOf("capture_screens" to true))
            events.screen("Enabled")
            assertEquals("Enabled", contexts.last().screenName)
        }
    }

    @Test
    fun explicitScreenAndCustomPropertiesDoNotUseTheExtraPropertyLimits() {
        client(autoProperties = { (1..30).associate { "extra_$it" to it } }).use { events ->
            val explicit = (1..30).associate { "explicit_$it" to listOf(it) }
            events.screen("Large", explicit)
            val props = queued(events).single().getJSONObject("properties")
            assertEquals(20, props.keys().asSequence().count { it.startsWith("extra_") })
            assertEquals(30, props.keys().asSequence().count { it.startsWith("explicit_") })
            events.capture("custom", explicit)
            assertEquals(30, queued(events).last().getJSONObject("properties")
                .keys().asSequence().count { it.startsWith("explicit_") })
        }
    }

    @Test
    fun throwingAutoPropertiesWarnsOncePerClientAndStillCaptures() {
        ShadowLog.clear()
        repeat(2) {
            client(autoProperties = { error("broken callback") }).use { events ->
                events.screen("First")
                events.screen("Second")
                val view = View(RuntimeEnvironment.getApplication()).apply {
                    isClickable = true
                    setFhqProperties(mapOf("declarative" to true))
                }
                events.recordElementInteraction(view)
                assertTrue(awaitQueue(events, 3).last().getJSONObject("properties").getBoolean("declarative"))
            }
        }
        val warnings = ShadowLog.getLogsForTag(FounderHQEvents.SDK_NAME)
            .filter { it.type == Log.WARN && it.msg.contains("autoProperties threw") }
        assertEquals(2, warnings.size)
    }

    @Test
    fun beforeSendSeesFinishedEventsOfEveryKindAndCanChangeThem() {
        val seen = mutableListOf<String>()
        client(autoProperties = { mapOf("section" to "callback") }, beforeSend = { event ->
            seen += event.getString("event")
            if (event.getString("event") == "\$screen") {
                assertEquals("explicit", event.getJSONObject("properties").getString("section"))
                assertTrue(event.has("uuid"))
                assertTrue(event.has("timestamp"))
            }
            event.put("event", "changed")
            event.getJSONObject("properties").put("last", true)
            event
        }).use { events ->
            events.screen("Home", mapOf("section" to "explicit"))
            events.recordLifecycle("active")
            events.capture("\$session_start")
            events.identify("user_42")
            events.capturePushNotificationOpened()
            events.capture("custom")
            assertEquals(listOf("\$screen", "\$application_opened", "\$session_start", "\$identify",
                "\$push_notification_opened", "custom"), seen)
            assertEquals(6, queued(events).size)
            queued(events).forEach {
                assertEquals("changed", it.getString("event"))
                assertTrue(it.getJSONObject("properties").getBoolean("last"))
            }
        }
    }

    @Test
    fun beforeSendNullAndThrowBothDropTheEvent() {
        for (hook in listOf<(JSONObject) -> JSONObject?>({ null }, { error("drop") })) {
            client(beforeSend = hook).use { events ->
                events.screen("Home")
                events.capture("custom")
                assertTrue(queued(events).isEmpty())
            }
        }
    }

    @Test
    fun beforeSendInvalidResultsAreDropped() {
        val invalid = listOf<Pair<String, Any?>>(
            "uuid" to "invalid", "uuid" to 123, "uuid" to null,
            "timestamp" to "invalid", "timestamp" to "2026-02-30T00:00:00Z", "timestamp" to 123,
            "event" to "", "event" to "\$unknown", "event" to 3,
            "distinct_id" to "",
            "properties" to "invalid", "options" to JSONObject().put("process_person_profile", "yes"),
        )
        for ((key, value) in invalid) {
            client(beforeSend = { it.put(key, value) }).use { events ->
                events.capture("custom")
                assertTrue("Accepted $key = $value", queued(events).isEmpty())
            }
        }
        client(beforeSend = { JSONObject() }).use { events ->
            events.capture("custom")
            assertTrue(queued(events).isEmpty())
        }
    }

    @Test
    fun beforeSendEnvelopeContractTable() {
        data class Case(
            val label: String,
            val mutate: (JSONObject) -> JSONObject,
            val kept: Boolean = true,
            val original: String = "custom",
            val check: (JSONObject) -> Unit = {},
        )
        val v4 = "12345678-1234-4567-89ab-123456789abc"
        val v7 = "12345678-1234-7567-89ab-123456789abc"
        val cases = listOf(
            Case("unknown keys", {
                it.put("extra", true)
                it.getJSONObject("options").put("cookieless_mode", true).put("extra", true)
                it
            }, check = {
                assertFalse(it.has("extra"))
                assertEquals(setOf("process_person_profile"),
                    it.getJSONObject("options").keys().asSequence().toSet())
            }),
            Case("padded control rename", { it.put("event", " \$mobile_purchase_claim") }, false),
            Case("control rename", { it.put("event", "\$mobile_purchase_claim") }, false),
            Case("own internal name", { it }, original = "\$identify",
                check = { assertEquals("\$identify", it.getString("event")) }),
            Case("trim public name", { it.put("event", "  public_name  ") },
                check = { assertEquals("public_name", it.getString("event")) }),
            Case("empty name", { it.put("event", "  ") }, false),
            Case("invalid uuid", { it.put("uuid", "invalid") }, false),
            Case("v4 uuid", { it.put("uuid", v4) }),
            Case("uppercase uuid", { it.put("uuid", v4.uppercase()) }),
            Case("bad uuid variant", { it.put("uuid", v4.replace("89ab", "79ab")) }, false),
            Case("bad uuid version", { it.put("uuid", v4.replace("4567", "9567")) }, false),
            Case("trim distinct id", { it.put("distinct_id", "  user_42  ") },
                check = { assertEquals("user_42", it.getString("distinct_id")) }),
            Case("empty distinct id", { it.put("distinct_id", "  ") }, false),
            Case("illegal distinct id", { it.put("distinct_id", " null ") }, false),
            Case("long distinct id", { it.put("distinct_id", "x".repeat(401)) }, false),
            Case("whole seconds", { it.put("timestamp", "2026-08-14T00:00:00Z") }),
            Case("fractional seconds", { it.put("timestamp", "2026-08-14T00:00:00.123456789123Z") }),
            // Ingest takes UTC only: an offset timestamp is queued as the same instant in UTC.
            Case("positive offset rewritten to UTC", { it.put("timestamp", "2026-08-14T05:30:00+05:30") },
                check = { assertEquals("2026-08-14T00:00:00.000Z", it.getString("timestamp")) }),
            Case("negative offset rewritten to UTC", { it.put("timestamp", "2026-08-13T20:00:00.5-04:00") },
                check = { assertEquals("2026-08-14T00:00:00.500Z", it.getString("timestamp")) }),
            Case("impossible day", { it.put("timestamp", "2026-02-30T00:00:00Z") }, false),
            Case("no timezone", { it.put("timestamp", "2026-08-14T00:00:00") }, false),
            Case("bad offset", { it.put("timestamp", "2026-08-14T00:00:00+24:00") }, false),
            Case("v4 session removed", { it.put("session_id", v4) },
                check = { assertFalse(it.has("session_id")) }),
            Case("v7 session kept", { it.put("session_id", v7) },
                check = { assertEquals(v7, it.getString("session_id")) }),
            Case("invalid session removed", { it.put("session_id", 123) },
                check = { assertFalse(it.has("session_id")) }),
            Case("bad variant session removed", { it.put("session_id", v7.replace("89ab", "79ab")) },
                check = { assertFalse(it.has("session_id")) }),
            Case("missing session allowed", { it.remove("session_id"); it },
                check = { assertFalse(it.has("session_id")) }),
            Case("window kept", { it.put("window_id", "x".repeat(200)) },
                check = { assertEquals(200, it.getString("window_id").length) }),
            Case("long window removed", { it.put("window_id", "x".repeat(201)) },
                check = { assertFalse(it.has("window_id")) }),
            Case("invalid window removed", { it.put("window_id", 123) },
                check = { assertFalse(it.has("window_id")) }),
            Case("empty window removed", { it.put("window_id", "") },
                check = { assertFalse(it.has("window_id")) }),
            Case("blank window removed", { it.put("window_id", "   ") },
                check = { assertFalse(it.has("window_id")) }),
            Case("padded window trimmed", { it.put("window_id", " window ") },
                check = { assertEquals("window", it.getString("window_id")) }),
            Case("window past 200 UTF-16 units removed", { it.put("window_id", "\uD83D\uDE00".repeat(101)) },
                check = { assertFalse(it.has("window_id")) }),
            Case("non-object properties", { it.put("properties", org.json.JSONArray()) }, false),
            Case("non-boolean profile", {
                it.put("options", JSONObject().put("process_person_profile", "true"))
            }, false),
        )
        for (case in cases) {
            client(beforeSend = case.mutate).use { events ->
                events.capture(case.original)
                val queue = queued(events)
                assertEquals(case.label, if (case.kept) 1 else 0, queue.size)
                if (case.kept) case.check(queue.single())
            }
        }
    }

    @Test
    fun timestampParserUsesRealCalendarComponentsAndOffsetsForQueueTtl() {
        val instant = checkNotNull(parseWireTimestamp("2026-08-14T00:00:00Z"))
        for (timestamp in listOf("2026-08-14T00:00:00.000Z", "2026-08-14T05:30:00+05:30",
            "2026-08-13T20:00:00-04:00", "2026-08-14T00:00:00+00:00")) {
            assertEquals(timestamp, instant, parseWireTimestamp(timestamp))
        }
        assertEquals(instant + 123, parseWireTimestamp("2026-08-14T00:00:00.123456789123Z"))
        for (timestamp in listOf("2026-02-30T00:00:00Z", "2026-02-29T00:00:00+05:30",
            "2026-08-14T24:00:00Z", "2026-08-14T00:60:00Z", "2026-08-14T00:00:00+01:60",
            "2026-08-14T00:00:00", "2026-08-14T00:00:00.Z")) {
            assertNull(timestamp, parseWireTimestamp(timestamp))
        }
        assertTrue(parseWireTimestamp("2024-02-29T00:00:00Z") != null)
    }

    @Test
    fun beforeSendRetainedReferencesCannotChangeQueuedEvents() {
        var retained: JSONObject? = null
        client(beforeSend = { retained = it; it }).use { events ->
            events.capture("custom")
            retained!!.put("uuid", "invalid")
            retained!!.getJSONObject("properties").put("late", true)
            assertFalse(queued(events).single().getJSONObject("properties").has("late"))
            assertTrue(queued(events).single().getString("uuid") != "invalid")
        }
    }

    @Test
    fun beforeSendCanReturnAValidReplacementEvent() {
        val replacementId = UUID.randomUUID().toString()
        client(beforeSend = {
            JSONObject(it.toString())
                .put("uuid", replacementId)
                .put("timestamp", "2026-08-14T00:00:00Z")
                .put("event", "replacement")
        }).use { events ->
            events.capture("original")
            val event = queued(events).single()
            assertEquals(replacementId, event.getString("uuid"))
            assertEquals("replacement", event.getString("event"))
            assertEquals("2026-08-14T00:00:00Z", event.getString("timestamp"))
        }
    }

    @Test
    fun purchaseControlEventsBypassEveryHookOutcome() {
        val hooks = listOf<(JSONObject) -> JSONObject?>(
            { null }, { it.put("event", "reshaped").put("properties", JSONObject()) }, { error("drop") },
        )
        for (hook in hooks) {
            val seen = mutableListOf<String>()
            var autoCalls = 0
            client(autoProperties = { autoCalls++; null }, beforeSend = {
                seen += it.getString("event")
                hook(it)
            }).use { events ->
                events.claimSubscription(
                    FounderHQObservedPurchase(FounderHQPurchaseSource.PLAY_BILLING, "play_store"),
                    FounderHQRevenueClaimConfirmation.MOVE_FUTURE_REVENUE,
                )
                val prepared = CountDownLatch(1)
                events.preparePurchase(FounderHQPurchaseSource.PLAY_BILLING) { prepared.countDown() }
                val controls = awaitQueue(events, 2)
                // Let preparation finish before close shuts down its executor.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (prepared.count > 0 && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertEquals(0L, prepared.count)
                assertEquals(listOf("\$mobile_purchase_claim", "\$mobile_purchase_prepared"),
                    controls.map { it.getString("event") })
                controls.forEach { assertTrue(it.getJSONObject("properties").length() > 0) }
                assertTrue(seen.isEmpty())
                assertEquals(0, autoCalls)
            }
        }
    }

    @Test
    fun beforeSendAlsoGatesNewIdentityRetries() {
        val hooks = listOf<Pair<String, (JSONObject) -> JSONObject?>>(
            "drop" to { null },
            "throw" to { error("drop") },
            "invalid" to { it.put("uuid", "invalid") },
            "keep" to {
                it.put("extra", true).put("distinct_id", "  user_42  ")
                    .put("session_id", UUID.randomUUID().toString())
            },
        )
        for ((outcome, retryHook) in hooks) {
            var calls = 0
            var sawTransformedIdentify = false
            var sends = 0
            val transport = object : FounderHQTransport {
                override fun send(url: String, headers: Map<String, String>, body: String): FounderHQTransportResponse {
                    val uuid = JSONObject(body).getJSONArray("batch").getJSONObject(0).getString("uuid")
                    val response = JSONObject()
                        .put("results", JSONObject().put(uuid, JSONObject().put("result", "ok")))
                    if (sends++ == 0) {
                        response.put("directives", org.json.JSONArray().put(JSONObject()
                            .put("type", "rotate_distinct_id")
                            .put("distinct_id", UUID.randomUUID().toString())))
                    }
                    return FounderHQTransportResponse(200, response.toString())
                }
            }
            client(beforeSend = {
                calls++
                if (calls == 1) {
                    it.getJSONObject("properties").put("transformed", true)
                    it
                } else {
                    sawTransformedIdentify = it.getString("event") == "\$identify" &&
                        it.getJSONObject("properties").getBoolean("transformed")
                    retryHook(it)
                }
            }, transport = transport).use { events ->
                events.identify("user_42")
                assertEquals(1, queued(events).size)
                assertTrue(events.flush())
                assertEquals(outcome, 2, calls)
                assertTrue(outcome, sawTransformedIdentify)
                val queue = queued(events)
                assertEquals(outcome, if (outcome == "keep") 1 else 0, queue.size)
                if (outcome == "keep") {
                    assertEquals("user_42", queue.single().getString("distinct_id"))
                    assertFalse(queue.single().has("extra"))
                    assertFalse(queue.single().has("session_id"))
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun screenColorSchemeIsReadAtCaptureTimeAndAbsentFromOtherEvents() {
        client().use { events ->
            events.screen("Dark")
            assertEquals("dark", queued(events).last().getJSONObject("properties").getString("\$prefers_color_scheme"))
            RuntimeEnvironment.setQualifiers("notnight")
            events.screen("Light")
            assertEquals("light", queued(events).last().getJSONObject("properties").getString("\$prefers_color_scheme"))
            events.capture("custom")
            events.recordLifecycle("active")
            queued(events).takeLast(2).forEach {
                assertFalse(it.getJSONObject("properties").has("\$prefers_color_scheme"))
            }
            val configuration = RuntimeEnvironment.getApplication().resources.configuration
            configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()
            events.screen("Unspecified")
            assertFalse(queued(events).last().getJSONObject("properties").has("\$prefers_color_scheme"))
        }
    }

    @Test
    fun unavailableAppearanceIsOmittedSafely() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getResources(): Resources = error("unavailable")
        }
        assertNull(founderHqColorScheme(context))
    }

    private fun client(
        autoProperties: ((FounderHQAutoPropertiesContext) -> Map<String, Any?>?)? = null,
        beforeSend: ((JSONObject) -> JSONObject?)? = null,
        transport: FounderHQTransport? = null,
    ): FounderHQEvents {
        val stored = mutableMapOf<String, String>()
        return FounderHQEvents(
            RuntimeEnvironment.getApplication(),
            "fhq_pk_auto_properties",
            FounderHQEventsConfig(
                flushAt = 1000, flushIntervalSeconds = 0, remoteConfig = false,
                captureSessions = false, captureInstallUpdates = false,
                captureElementInteractions = true, autoProperties = autoProperties, beforeSend = beforeSend,
            ),
            FounderHQEventsDependencies(
                clock = FounderHQClock { 1_786_694_000_000 },
                uuid = object : FounderHQUuidProvider {
                    override fun uuid() = UUID.randomUUID().toString()
                    override fun uuidV7(nowMillis: Long) = founderHqUuidV7(nowMillis)
                },
                storage = object : FounderHQStorage {
                    override fun getString(key: String) = stored[key]
                    override fun putString(key: String, value: String?) {
                        if (value == null) stored.remove(key) else stored[key] = value
                    }
                },
                transport = transport ?: object : FounderHQTransport {
                    override fun send(url: String, headers: Map<String, String>, body: String) =
                        FounderHQTransportResponse(503, "")
                },
                platformFacts = FounderHQPlatformFactsProvider { emptyMap() },
            ),
        ).also { it.readyForCapture() }
    }

    private fun queued(events: FounderHQEvents): List<JSONObject> {
        val queue = events.persistedState().getJSONArray("queue")
        return (0 until queue.length()).map(queue::getJSONObject)
    }

    private fun awaitQueue(events: FounderHQEvents, count: Int): List<JSONObject> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val queue = queued(events)
            if (queue.size >= count) return queue
            Thread.sleep(10)
        }
        val queue = queued(events)
        assertEquals("Timed out waiting for captured taps", count, queue.size)
        return queue
    }
}
