package com.founderhq.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Static state, and therefore a witness that says whether this is the same
 * operating system process as the previous test method.
 */
internal object ProcessWitness {
    @Volatile var visited = false
}

private const val PREFERENCES = "fhq_instrumented_next_launch"
private const val STATE_KEY = "state_v2"

/**
 * The last rung of the retry ladder waits for a new process, not for a clock,
 * so no single-process test can prove it. These two methods run in two
 * processes: the module sets `execution = "ANDROIDX_TEST_ORCHESTRATOR"`, which
 * starts a fresh instrumentation process for every test method while leaving
 * the app's stored data in place.
 *
 * [step2_aNewProcessSpendsTheFinalRung] refuses to pass if it finds itself in
 * the same process as [step1_theLadderParksTheEventForTheNextLaunch], so this
 * cannot quietly degrade into a claim it is not making.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NextLaunchRetryInstrumentedTest {
    @Test
    fun step1_theLadderParksTheEventForTheNextLaunch() {
        ProcessWitness.visited = true
        val storage = DevicePreferencesStorage(PREFERENCES).also { it.clear() }
        val clock = MovableClock()
        val transport = RecordingTransport(failing = true)
        val client = instrumentedClient(
            label = "next_launch",
            transport = transport,
            storage = storage,
            clock = clock,
            maxRetries = 5,
        )
        client.readyForCapture()
        client.capture("checkout_started")

        // One first send, then the whole clock driven part of the ladder.
        assertFalse(client.flushOnSchedule())
        for (wait in listOf(30_000L, 30_000L, 120_000L, 300_000L)) {
            clock.advance(wait)
            client.flushOnSchedule()
        }
        assertEquals("the ladder should have spent five sends", 5, transport.attempts)

        // No clock makes it eligible now, however far it is moved. An hour
        // stays inside the 24 hour TTL, which drops an event outright and
        // would hide the parking this test is about.
        clock.advance(60 * 60 * 1_000L)
        assertTrue(client.flushOnSchedule())
        assertEquals(5, transport.attempts)

        val uuid = parkedUuid(storage)
        assertEquals(
            "the event is waiting for a new process",
            FOUNDERHQ_RETRY_NEXT_LAUNCH,
            JSONObject(checkNotNull(storage.getString(STATE_KEY)))
                .getJSONObject("retryEligibleAt")
                .getLong(uuid),
        )

        // close() flushes on purpose and would spend the last rung here, in
        // this process. The process ending is the point of the test, so the
        // client is left exactly as a killed app would leave it.
    }

    @Test
    fun step2_aNewProcessSpendsTheFinalRung() {
        assertFalse(
            "this ran in the same process as step1, so it proves nothing about " +
                "a new launch: check that ANDROIDX_TEST_ORCHESTRATOR is active",
            ProcessWitness.visited,
        )
        val storage = DevicePreferencesStorage(PREFERENCES)
        val raw = checkNotNull(storage.getString(STATE_KEY)) {
            "step1 left nothing on disk; run the class, not the method"
        }
        val uuid = parkedUuid(storage)
        assertEquals(
            "the parked marker did not survive the process ending",
            FOUNDERHQ_RETRY_NEXT_LAUNCH,
            JSONObject(raw).getJSONObject("retryEligibleAt").getLong(uuid),
        )

        val transport = RecordingTransport(failing = false)
        val client = instrumentedClient(
            label = "next_launch",
            transport = transport,
            storage = storage,
            clock = MovableClock(),
            maxRetries = 5,
        )
        client.readyForCapture()

        // Building the first client in a new process is what frees the event.
        assertFalse(
            "the event is still parked after a new process started",
            client.persistedState().getJSONObject("retryEligibleAt").has(uuid),
        )

        assertTrue("the freed event was not delivered", client.flushOnSchedule())
        assertEquals(1, transport.attempts)
        val batch = JSONObject(transport.bodies.single()).getJSONArray("batch")
        assertEquals(1, batch.length())
        assertEquals(uuid, batch.getJSONObject(0).getString("uuid"))
        assertEquals("checkout_started", batch.getJSONObject(0).getString("event"))
        assertEquals(0, queuedEvents(client).length())

        client.close()
        storage.clear()
    }

    private fun parkedUuid(storage: FounderHQStorage): String {
        val state = JSONObject(checkNotNull(storage.getString(STATE_KEY)))
        val queue = state.getJSONArray("queue")
        assertEquals("exactly one event should be waiting", 1, queue.length())
        return queue.getJSONObject(0).getString("uuid")
    }
}
