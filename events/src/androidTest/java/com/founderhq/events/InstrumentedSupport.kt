package com.founderhq.events

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Wall clock that a test can move forward without waiting for it. */
class MovableClock(private var value: Long = System.currentTimeMillis()) : FounderHQClock {
    override fun nowMillis(): Long = value
    fun advance(milliseconds: Long) { value += milliseconds }
}

/** Storage that survives this process, so a later process can read it back. */
class DevicePreferencesStorage(name: String) : FounderHQStorage {
    private val preferences = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        // commit, not apply: a test may end the process right after this.
        preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }
            .commit()
    }

    fun clear() { preferences.edit().clear().commit() }
}

class InMemoryStorage : FounderHQStorage {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = synchronized(values) { values[key] }
    override fun putString(key: String, value: String?) = synchronized(values) {
        if (value == null) values.remove(key) else values[key] = value
        Unit
    }
}

class RecordingTransport(var failing: Boolean = false) : FounderHQTransport {
    val bodies = mutableListOf<String>()
    @Volatile var attempts = 0

    override fun send(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): FounderHQTransportResponse {
        attempts++
        synchronized(bodies) { bodies += body }
        if (failing) throw IOException("transport is down")
        val results = JSONObject()
        val batch = JSONObject(body).getJSONArray("batch")
        for (index in 0 until batch.length()) {
            results.put(
                batch.getJSONObject(index).getString("uuid"),
                JSONObject().put("result", "ok"),
            )
        }
        return FounderHQTransportResponse(202, JSONObject().put("results", results).toString())
    }
}

class RandomUuidProvider : FounderHQUuidProvider {
    override fun uuid(): String = UUID.randomUUID().toString()
    override fun uuidV7(nowMillis: Long): String = founderHqUuidV7(nowMillis)
}

/**
 * A client wired to real Android — a real Application, real Activity lifecycle
 * callbacks, a real main looper — with only the network and the clock replaced.
 */
fun instrumentedClient(
    label: String,
    transport: FounderHQTransport,
    storage: FounderHQStorage = InMemoryStorage(),
    clock: FounderHQClock = MovableClock(),
    captureElementInteractions: Boolean = false,
    tracingHeaders: List<String>? = null,
    maxRetries: Int = 5,
): FounderHQEvents = FounderHQEvents(
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
    "fhq_pk_$label",
    FounderHQEventsConfig(
        flushIntervalSeconds = 0,
        captureLifecycle = false,
        captureScreens = false,
        captureSessions = false,
        captureInstallUpdates = false,
        remoteConfig = false,
        captureElementInteractions = captureElementInteractions,
        tracingHeaders = tracingHeaders,
        maxRetries = maxRetries,
    ),
    FounderHQEventsDependencies(
        clock = clock,
        uuid = RandomUuidProvider(),
        storage = storage,
        transport = transport,
        platformFacts = FounderHQPlatformFactsProvider { emptyMap() },
    ),
)

/**
 * Waits until the Activity owns the input focus. Injection is refused when
 * another app's window is on top, and the failure that follows blames the
 * SDK for something the device did, so this asks plainly instead.
 */
fun awaitWindowFocus(activity: Activity, timeoutMillis: Long = 15_000) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    val focused = BooleanArray(1)
    while (SystemClock.uptimeMillis() < deadline) {
        instrumentation.runOnMainSync { focused[0] = activity.hasWindowFocus() }
        if (focused[0]) return
        Thread.sleep(50)
    }
    error("the activity never took window focus: another window is on top of it")
}

/**
 * Injects a real tap through the input system: the same path a finger takes,
 * so the window callback, the view hierarchy and the click listener all run
 * exactly as they do in a shipped app.
 */
fun tap(view: View, holdMillis: Long = 40) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val point = FloatArray(2)
    instrumentation.runOnMainSync {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        point[0] = location[0] + view.width / 2f
        point[1] = location[1] + view.height / 2f
    }
    check(point[0] > 0f && point[1] > 0f) { "view is not on screen" }
    val down = SystemClock.uptimeMillis()
    val downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point[0], point[1], 0)
    val upEvent = MotionEvent.obtain(
        down,
        down + holdMillis,
        MotionEvent.ACTION_UP,
        point[0],
        point[1],
        0,
    )
    try {
        instrumentation.sendPointerSync(downEvent)
        instrumentation.sendPointerSync(upEvent)
    } finally {
        downEvent.recycle()
        upEvent.recycle()
    }
    instrumentation.waitForIdleSync()
}

/**
 * Injects a real drag: the gesture a user makes to scroll a list. It has to be
 * told apart from a tap, because it ends with a finger lifting off whatever
 * happens to be under it.
 */
fun drag(from: View, to: View, steps: Int = 12) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val start = FloatArray(2)
    val end = FloatArray(2)
    instrumentation.runOnMainSync {
        val location = IntArray(2)
        from.getLocationOnScreen(location)
        start[0] = location[0] + from.width / 2f
        start[1] = location[1] + from.height / 2f
        to.getLocationOnScreen(location)
        end[0] = location[0] + to.width / 2f
        end[1] = location[1] + to.height / 2f
    }
    val down = SystemClock.uptimeMillis()
    val events = mutableListOf<MotionEvent>()
    events += MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, start[0], start[1], 0)
    for (step in 1..steps) {
        val fraction = step.toFloat() / steps
        events += MotionEvent.obtain(
            down,
            down + step * 10L,
            MotionEvent.ACTION_MOVE,
            start[0] + (end[0] - start[0]) * fraction,
            start[1] + (end[1] - start[1]) * fraction,
            0,
        )
    }
    events += MotionEvent.obtain(
        down,
        down + (steps + 1) * 10L,
        MotionEvent.ACTION_UP,
        end[0],
        end[1],
        0,
    )
    try {
        events.forEach(instrumentation::sendPointerSync)
    } finally {
        events.forEach(MotionEvent::recycle)
    }
    instrumentation.waitForIdleSync()
}

fun queuedEvents(client: FounderHQEvents): JSONArray =
    client.persistedState().getJSONArray("queue")

fun capturedEvents(client: FounderHQEvents, name: String): List<JSONObject> {
    val queue = queuedEvents(client)
    return (0 until queue.length())
        .map { queue.getJSONObject(it) }
        .filter { it.optString("event") == name }
}

/** Waits for work the SDK does on its own executor, rather than guessing. */
fun awaitEvents(
    client: FounderHQEvents,
    name: String,
    count: Int,
    timeoutMillis: Long = 5_000,
): List<JSONObject> {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var latest = capturedEvents(client, name)
    while (latest.size < count && SystemClock.uptimeMillis() < deadline) {
        Thread.sleep(25)
        latest = capturedEvents(client, name)
    }
    return latest
}

/** Gives the SDK's executor time to prove that it captured nothing. */
fun settle(millis: Long = 1_000) = Thread.sleep(millis)
