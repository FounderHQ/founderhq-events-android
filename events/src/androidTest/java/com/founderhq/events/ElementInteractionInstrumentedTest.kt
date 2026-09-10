package com.founderhq.events

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Element capture on a real device, driven by real taps.
 *
 * The unit tests build MotionEvents and call the observer directly. These
 * tests inject touches through the input system, so the window callback, the
 * view hierarchy walk, the app's own click listener and Activity recreation
 * all run the way they do in a shipped app.
 */
@RunWith(AndroidJUnit4::class)
class ElementInteractionInstrumentedTest {
    private lateinit var transport: RecordingTransport
    private lateinit var client: FounderHQEvents
    private var scenario: ActivityScenario<ElementCaptureTestActivity>? = null

    @Before
    fun setUp() {
        transport = RecordingTransport(failing = true)
        client = instrumentedClient(
            label = "elements",
            transport = transport,
            // A real clock: rage detection must hold against real tap timing.
            clock = FounderHQClock { System.currentTimeMillis() },
            captureElementInteractions = true,
        )
        // Lifecycle callbacks register during start-up, so the client has to be
        // ready before the Activity it is meant to watch exists.
        client.readyForCapture()
        scenario = ActivityScenario.launch(ElementCaptureTestActivity::class.java)
        // Real taps need the real thing: an Activity that owns the focus.
        awaitWindowFocus(currentActivity())
    }

    @After
    fun tearDown() {
        scenario?.close()
        client.close()
    }

    @Test
    fun aRealTapOnAButtonCapturesItsLabelAndSemanticMetadataAndNothingElse() {
        val activity = currentActivity()
        tap(activity.button)

        val events = awaitEvents(client, "\$autocapture", 1)
        assertEquals("one tap, one \$autocapture", 1, events.size)
        val properties = events.first().getJSONObject("properties")
        assertEquals("touch", properties.getString("\$event_type"))

        val elements = properties.getJSONArray("\$elements")
        val target = elements.getJSONObject(0)
        assertTrue(
            "unexpected view_class ${target.getString("view_class")}",
            target.getString("view_class").contains("Button"),
        )
        assertEquals("fhq_checkout_button", target.getString("view_id"))
        assertEquals(FHQ_TEST_BUTTON_DESCRIPTION, target.getString("content_description"))
        // A control announces what it does, so its own label is recorded.
        assertEquals(FHQ_TEST_BUTTON_LABEL, target.getString("text"))

        // The parent chain is recorded by id, which is what makes a path stable.
        val path = properties.getString("\$element_path")
        assertTrue(path, path.contains("fhq_root_column"))
        assertTrue(path, path.endsWith("fhq_checkout_button"))

        // A control's label plus semantic facts, and nothing else: no
        // coordinates, no view tags, no free-form attributes.
        for (index in 0 until elements.length()) {
            val element = elements.getJSONObject(index)
            val unexpected = element.keys().asSequence()
                .filterNot { it in setOf("view_class", "view_id", "content_description", "text") }
                .toList()
            assertEquals("element $index carries $unexpected", emptyList<String>(), unexpected)
        }
        // The parent column is layout, not a control, so it has no label.
        assertFalse("a plain layout reported text", elements.getJSONObject(1).has("text"))
        for (key in listOf("\$x", "\$y", "x", "y")) {
            assertFalse("payload carries $key", properties.has(key))
        }

        // The wrapper forwards the touch: the app's own listener still runs.
        assertEquals(1, activity.buttonClicks.get())
    }

    @Test
    fun aControlsLabelIsCapturedAndEveryOtherStringOnTheScreenIsNot() {
        val activity = currentActivity()
        tap(activity.button)
        tap(activity.field)

        // The button is recorded. The field is not recorded at all: a tap on a
        // field is the start of typing, not a press of a control.
        val events = awaitEvents(client, "\$autocapture", 1)
        assertEquals("the field was recorded as a control", 1, events.size)
        val targets = events.map {
            it.getJSONObject("properties").getJSONArray("\$elements").getJSONObject(0)
        }
        val ids = targets.map { it.getString("view_id") }
        assertEquals(listOf("fhq_checkout_button"), ids)

        // Everything the SDK holds, including the parts that never left the
        // device, searched for every string that was on the screen.
        val payload = client.persistedState().toString() +
            synchronized(transport.bodies) { transport.bodies.joinToString("\n") }
        // The button's own label is now part of the record.
        assertTrue(
            "captured payload is missing the button's label",
            payload.contains(FHQ_TEST_BUTTON_LABEL),
        )
        // What the user typed, what the field promised, and what the app wrote
        // on the screen all stay on the device.
        for (secret in listOf(
            FHQ_TEST_FIELD_TEXT,
            FHQ_TEST_FIELD_HINT,
            FHQ_TEST_LABEL_TEXT,
        )) {
            assertFalse("captured payload contains \"$secret\"", payload.contains(secret))
        }
    }

    @Test
    fun tappingPlainLayoutCapturesNothing() {
        val activity = currentActivity()
        tap(activity.plainView)
        settle()

        assertEquals(
            "a tap on a non interactive view is not an interaction",
            emptyList<JSONObject>(),
            capturedEvents(client, "\$autocapture"),
        )
    }

    @Test
    fun aDragThatEndsOnAControlIsNotAnInteraction() {
        val activity = currentActivity()
        // The everyday gesture this protects: the user scrolls a list and
        // lifts a finger over a row. Nothing was pressed, so nothing happened.
        drag(activity.plainView, activity.button)
        settle()
        assertEquals(
            "a drag that ended over the button was recorded as a tap on it",
            emptyList<JSONObject>(),
            capturedEvents(client, "\$autocapture"),
        )
        assertEquals("the button was never clicked", 0, activity.buttonClicks.get())

        // And the same gesture started on the control it is dragged away from.
        drag(activity.button, activity.plainView)
        settle()
        assertEquals(
            "a drag that started on the button was recorded as a tap",
            emptyList<JSONObject>(),
            capturedEvents(client, "\$autocapture"),
        )
    }

    @Test
    fun threeFastTapsOnOneControlEmitOneRageClick() {
        val activity = currentActivity()
        // Warm the input path, so the burst below is not paying for first use.
        tap(activity.plainView)

        // `sendPointerSync` waits for the device, and a loaded emulator can
        // take longer over three taps than the rage window allows. That is the
        // harness being slow, not the SDK being wrong, so the burst is retried
        // until the taps really were fast and only then is the rule judged.
        var burstMillis = Long.MAX_VALUE
        var attempts = 0
        while (burstMillis >= FounderHQProtocolConstants.RAGE_WINDOW_MILLIS && attempts < 5) {
            attempts++
            restartClientAndActivity()
            val started = System.currentTimeMillis()
            repeat(3) { tap(currentActivity().button) }
            burstMillis = System.currentTimeMillis() - started
        }
        assertTrue(
            "the emulator never delivered three taps inside " +
                "${FounderHQProtocolConstants.RAGE_WINDOW_MILLIS}ms; last burst ${burstMillis}ms",
            burstMillis < FounderHQProtocolConstants.RAGE_WINDOW_MILLIS,
        )

        val rage = awaitEvents(client, "\$rageclick", 1)
        assertEquals("three fast taps are one rage click", 1, rage.size)
        val elements = rage.first().getJSONObject("properties").getJSONArray("\$elements")
        assertEquals("fhq_checkout_button", elements.getJSONObject(0).getString("view_id"))

        assertEquals(3, awaitEvents(client, "\$autocapture", 3).size)
        assertEquals(3, currentActivity().buttonClicks.get())
    }

    /** A clean client and screen, so a retried burst starts from nothing. */
    private fun restartClientAndActivity() {
        scenario?.close()
        client.close()
        transport = RecordingTransport(failing = true)
        client = instrumentedClient(
            label = "elements",
            transport = transport,
            clock = FounderHQClock { System.currentTimeMillis() },
            captureElementInteractions = true,
        )
        client.readyForCapture()
        scenario = ActivityScenario.launch(ElementCaptureTestActivity::class.java)
        awaitWindowFocus(currentActivity())
    }

    @Test
    fun remoteConfigStopsCaptureOnTheDeviceAndHandsTheWindowBack() {
        val activity = currentActivity()
        tap(activity.button)
        assertEquals(1, awaitEvents(client, "\$autocapture", 1).size)

        // The operator switches element capture off for this key.
        client.applyRemoteConfig(mapOf("autocapture" to false))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(200)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // What was already waiting to be sent is dropped, not sent later.
        assertEquals(
            "an event captured before the operator said stop is still queued",
            emptyList<JSONObject>(),
            capturedEvents(client, "\$autocapture"),
        )
        assertFalse(
            "the app never gets its window callback back",
            activity.window.callback is FounderHQWindowCallback,
        )

        val clicksBefore = activity.buttonClicks.get()
        tap(activity.button)
        settle()
        assertEquals(
            "capture is off and the tap was still recorded",
            emptyList<JSONObject>(),
            capturedEvents(client, "\$autocapture"),
        )
        // The app's own touch handling is untouched by any of this.
        assertEquals(clicksBefore + 1, activity.buttonClicks.get())
    }

    /**
     * The other direction, which only a device can prove: the window on screen
     * was never wrapped, because the app shipped with capture off. Switching
     * it on has to wrap that window now, not at the next activity.
     */
    @Test
    fun remoteConfigStartsCaptureOnTheWindowAlreadyOnScreen() {
        val activity = currentActivity()
        client.applyRemoteConfig(mapOf("autocapture" to false))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        settle()
        tap(activity.button)
        settle()
        assertEquals(
            "capture is off and the tap was still recorded",
            emptyList<JSONObject>(),
            capturedEvents(client, "\$autocapture"),
        )

        // The operator switches it back on while the user is on this screen.
        client.applyRemoteConfig(mapOf("autocapture" to true))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(200)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(
            "the window on screen was never wrapped",
            activity.window.callback is FounderHQWindowCallback,
        )

        val clicksBefore = activity.buttonClicks.get()
        tap(activity.button)
        val events = awaitEvents(client, "\$autocapture", 1)
        assertEquals(1, events.size)
        assertEquals(clicksBefore + 1, activity.buttonClicks.get())
    }

    @Test
    fun theWrappedCallbackSurvivesActivityRecreation() {
        val first = currentActivity()
        tap(first.button)
        assertEquals(1, awaitEvents(client, "\$autocapture", 1).size)
        assertEquals(1, first.buttonClicks.get())

        checkNotNull(scenario).recreate()

        val second = currentActivity()
        assertTrue("recreate returned the same instance", first !== second)
        assertTrue(
            "the window callback was not re-wrapped after recreate",
            second.window.callback is FounderHQWindowCallback,
        )

        tap(second.button)
        assertEquals(2, awaitEvents(client, "\$autocapture", 2).size)
        // The new Activity starts at zero, so this is the new window's touch.
        assertEquals(1, second.buttonClicks.get())
    }

    @Test
    fun closingTheClientHandsTheWindowCallbackBack() {
        val activity = currentActivity()
        assertTrue(activity.window.callback is FounderHQWindowCallback)

        client.close()
        // close() restores callbacks on the main thread; wait for that post.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(200)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertFalse(
            "the app never gets its window callback back",
            activity.window.callback is FounderHQWindowCallback,
        )
        val clicksBefore = activity.buttonClicks.get()
        tap(activity.button)
        assertEquals(clicksBefore + 1, activity.buttonClicks.get())
    }

    private fun currentActivity(): ElementCaptureTestActivity {
        val holder = arrayOfNulls<ElementCaptureTestActivity>(1)
        checkNotNull(scenario).onActivity { holder[0] = it }
        return checkNotNull(holder[0])
    }
}