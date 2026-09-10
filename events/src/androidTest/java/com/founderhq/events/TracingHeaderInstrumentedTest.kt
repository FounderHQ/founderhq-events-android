package com.founderhq.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * The OkHttp interceptor against real sockets.
 *
 * Two MockWebServers on the loopback interface stand in for "my API" and
 * "somebody else's API": one is reachable as `localhost`, the other only as
 * `127.0.0.1`, and only the first is in `tracingHeaders`. The session id must
 * appear on one and never on the other, because that header is the thing that
 * would leak a visit to a third party.
 */
@RunWith(AndroidJUnit4::class)
class TracingHeaderInstrumentedTest {
    private lateinit var allowed: MockWebServer
    private lateinit var denied: MockWebServer
    private lateinit var events: FounderHQEvents
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        allowed = MockWebServer().apply { start(InetAddress.getByName("localhost"), 0) }
        denied = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        events = instrumentedClient(
            label = "tracing",
            transport = RecordingTransport(failing = true),
            tracingHeaders = listOf("localhost"),
        )
        events.readyForCapture()
        http = OkHttpClient.Builder()
            .addInterceptor(FounderHQOkHttpInterceptor(events))
            .build()
    }

    @After
    fun tearDown() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        events.close()
        allowed.shutdown()
        denied.shutdown()
    }

    @Test
    fun anAllowlistedHostReceivesTheSessionIdAndAnotherHostDoesNot() {
        allowed.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        denied.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        get(loopbackUrl("localhost", allowed.port, "mine"))
        get(loopbackUrl("127.0.0.1", denied.port, "theirs"))

        val sessionId = events.getSessionId()
        assertEquals(
            "the allowlisted host did not receive the session id",
            sessionId,
            allowed.takeRequest().getHeader(FOUNDERHQ_SESSION_ID_HEADER),
        )
        assertNull(
            "a host outside tracingHeaders received the session id",
            denied.takeRequest().getHeader(FOUNDERHQ_SESSION_ID_HEADER),
        )
    }

    @Test
    fun aHeaderTheAppSetItselfIsLeftAlone() {
        allowed.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        http.newCall(
            Request.Builder()
                .url(loopbackUrl("localhost", allowed.port, "mine"))
                .header(FOUNDERHQ_SESSION_ID_HEADER, "set-by-the-app")
                .build(),
        ).execute().use { it.body?.string() }

        assertEquals(
            "set-by-the-app",
            allowed.takeRequest().getHeader(FOUNDERHQ_SESSION_ID_HEADER),
        )
    }

    @Test
    fun anOptedOutUserSendsNoSessionIdAnywhere() {
        allowed.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        events.optOut()

        get(loopbackUrl("localhost", allowed.port, "mine"))

        assertNull(
            "an opted out user's session id travelled to the API",
            allowed.takeRequest().getHeader(FOUNDERHQ_SESSION_ID_HEADER),
        )
    }

    private fun get(url: HttpUrl) {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
    }

    /**
     * Built by hand rather than from [MockWebServer.url], because the server
     * reports whatever name the loopback address resolves to and this test
     * needs the two hostnames to stay apart.
     */
    private fun loopbackUrl(host: String, port: Int, path: String): HttpUrl =
        HttpUrl.Builder().scheme("http").host(host).port(port).addPathSegment(path).build()
}
