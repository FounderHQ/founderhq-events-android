package com.founderhq.events

import okhttp3.Interceptor
import okhttp3.Response

/** Header the ingest pipeline reads to join a backend event to the visit that caused it. */
const val FOUNDERHQ_SESSION_ID_HEADER = "x-founderhq-session-id"

/**
 * Adds the session id to requests going to the hosts listed in
 * `tracingHeaders`, so events your server records land on the same session as
 * the taps that triggered them.
 *
 * Install it on your own OkHttp client:
 *
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(FounderHQOkHttpInterceptor(events))
 *     .build()
 * ```
 *
 * Only the session id travels in the header. A distinct id never does: a
 * header is trivial to forge, and the server ignores one.
 *
 * OkHttp is an optional dependency of this SDK. Reference this class only from
 * an app that already depends on OkHttp.
 */
class FounderHQOkHttpInterceptor(
    private val events: FounderHQEvents,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sessionId = try {
            events.tracingSessionId(request.url.host)
        } catch (_: Throwable) {
            // A header is never worth failing the caller's request.
            null
        }
        val outgoing = if (sessionId == null || request.header(FOUNDERHQ_SESSION_ID_HEADER) != null) {
            request
        } else {
            request.newBuilder().header(FOUNDERHQ_SESSION_ID_HEADER, sessionId).build()
        }
        return chain.proceed(outgoing)
    }
}
