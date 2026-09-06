package com.founderhq.events

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Instant
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FounderHQEventsTest {
    @Test
    fun productionUuidV7GeneratorSetsVersionAndVariant() {
        repeat(100) { offset ->
            val value = founderHqUuidV7(1_785_542_400_000 + offset)
            assertTrue(value, value.matches(UUID_V7))
        }
    }

    @Test
    fun revenueCatPurchaseIdentitySurvivesIdentifyAndReinstallThenRotatesWithLogIn() {
        val storage = TestStorage()
        val revenueCat = TestRevenueCatIdentity()
        val uuid = TestUuidProvider(
            (1..24).map { "10000000-0000-4000-8000-%012d".format(it) }.toMutableList(),
            (1..4).map { "01989f2e-7800-7000-8000-%012d".format(it) }.toMutableList(),
        )
        val dependencies = FounderHQEventsDependencies(
            clock = TestClock(1_786_694_000_000),
            uuid = uuid,
            storage = storage,
            transport = TestTransport(),
            platformFacts = FounderHQPlatformFactsProvider { emptyMap() },
        )
        val config = FounderHQEventsConfig(
            flushIntervalSeconds = 0,
            captureLifecycle = false,
            captureScreens = false,
            captureSessions = false,
            captureInstallUpdates = false,
            remoteConfig = false,
        )
        val firstInstall = FounderHQEvents(
            RuntimeEnvironment.getApplication(),
            "fhq_pk_revenuecat",
            config,
            dependencies,
            revenueCat,
        )
        firstInstall.readyForCapture()
        val initial = firstInstall.purchaseAttribution()
        assertEquals(initial.appAccountToken, initial.obfuscatedExternalAccountId)
        assertEquals(initial.appAccountToken, initial.revenueCatAppUserId)

        firstInstall.identify("account-a")
        assertEquals(initial.appAccountToken, revenueCat.configured.last())
        assertEquals(initial.appAccountToken, revenueCat.loggedIn.last())

        val reinstall = FounderHQEvents(
            RuntimeEnvironment.getApplication(),
            "fhq_pk_revenuecat",
            config,
            dependencies,
            revenueCat,
        )
        reinstall.readyForCapture()
        val restored = reinstall.purchaseAttribution()
        assertEquals(initial, restored)

        reinstall.identify("account-b")
        val switched = reinstall.purchaseAttribution()
        assertTrue(switched.appAccountToken != initial.appAccountToken)
        assertEquals(switched.appAccountToken, revenueCat.loggedIn.last())

        reinstall.reset()
        val reset = reinstall.purchaseAttribution()
        assertTrue(reset.appAccountToken != switched.appAccountToken)
        assertEquals(
            listOf(initial.appAccountToken, initial.appAccountToken),
            revenueCat.configured,
        )
        assertEquals(
            listOf(initial.appAccountToken, switched.appAccountToken, reset.appAccountToken),
            revenueCat.loggedIn,
        )
        firstInstall.close()
        reinstall.close()
    }

    @Test
    fun identifyRejectsReservedAndOversizedIdsWithoutChangingIdentity() {
        val client = FounderHQEvents(
            RuntimeEnvironment.getApplication(),
            "fhq_pk_identity_validation",
            FounderHQEventsConfig(
                flushIntervalSeconds = 0,
                captureLifecycle = false,
                captureScreens = false,
                captureSessions = false,
                captureInstallUpdates = false,
                remoteConfig = false,
            ),
            FounderHQEventsDependencies(
                clock = TestClock(1_786_694_000_000),
                uuid = TestUuidProvider(
                    (1..8).map { "10000000-0000-4000-8000-%012d".format(it) }.toMutableList(),
                    mutableListOf("01989f2e-7800-7000-8000-000000000001"),
                ),
                storage = TestStorage(),
                transport = TestTransport(),
                platformFacts = FounderHQPlatformFactsProvider { emptyMap() },
            ),
        )
        client.readyForCapture()
        client.identify("customer-1")
        client.identify(" [object Object] ")
        client.identify("x".repeat(401))

        assertEquals("customer-1", client.getDistinctId())
        client.close()
    }

    @Test
    @Config(sdk = [24])
    fun offlineQueueAppliesSizeAndAgeCaps() {
        val clock = TestClock(1_786_694_000_000)
        val client = FounderHQEvents(
            RuntimeEnvironment.getApplication(),
            "fhq_pk_queue_caps",
            FounderHQEventsConfig(
                flushAt = 100,
                flushIntervalSeconds = 0,
                captureLifecycle = false,
                captureScreens = false,
                captureSessions = false,
                captureInstallUpdates = false,
                remoteConfig = false,
                maxQueueSize = 2,
                eventTtlMillis = 60_000,
            ),
            FounderHQEventsDependencies(
                clock = clock,
                uuid = TestUuidProvider(
                    (1..8).map { "10000000-0000-4000-8000-%012d".format(it) }.toMutableList(),
                    mutableListOf("01989f2e-7800-7000-8000-000000000001"),
                ),
                storage = TestStorage(),
                transport = TestTransport().also { it.online = false },
                platformFacts = FounderHQPlatformFactsProvider { emptyMap() },
            ),
        )
        client.readyForCapture()
        client.capture("first")
        client.capture("second")
        client.capture("third")
        clock.advance(61_000)
        client.capture("fresh")

        val queue = client.persistedState().getJSONArray("queue")
        assertEquals(1, queue.length())
        assertEquals("fresh", queue.getJSONObject(0).getString("event"))
        assertEquals(isoTimestamp(clock.nowMillis()), queue.getJSONObject(0).getString("timestamp"))
        client.close()
    }

    @Test
    @Config(sdk = [24])
    fun wireTimestampsSupportAndroid24AndPreviouslyPersistedPrecision() {
        val epoch = 1_786_694_000_000L
        assertEquals("2026-08-14T07:53:20.123Z", isoTimestamp(epoch + 123))
        assertEquals(epoch, parseWireTimestamp("2026-08-14T07:53:20Z"))
        assertEquals(epoch + 100, parseWireTimestamp("2026-08-14T07:53:20.1Z"))
        assertEquals(epoch + 123, parseWireTimestamp("2026-08-14T07:53:20.123456789Z"))
        assertEquals(null, parseWireTimestamp("2026-02-30T07:53:20.123Z"))
        assertEquals(null, parseWireTimestamp("2026-08-14T07:53:20.123Zextra"))
        assertEquals(null, parseWireTimestamp("invalid"))
    }

    @Test
    fun offlinePurchasePreparationStillInvokesCheckoutCompletionAndMarksContextUnacknowledged() {
        val storage = TestStorage()
        val transport = TestTransport().also { it.online = false }
        val client = FounderHQEvents(
            RuntimeEnvironment.getApplication(),
            "fhq_pk_purchase_timeout",
            FounderHQEventsConfig(
                flushIntervalSeconds = 0,
                captureLifecycle = false,
                captureScreens = false,
                captureSessions = false,
                captureInstallUpdates = false,
                remoteConfig = false,
                purchasePrepareTimeoutMillis = 10,
            ),
            FounderHQEventsDependencies(
                clock = TestClock(1_786_694_000_000),
                uuid = TestUuidProvider(
                    mutableListOf(
                        "00000000-0000-4000-8000-000000000001",
                        "00000000-0000-4000-8000-000000000002",
                        "00000000-0000-4000-8000-000000000003",
                    ),
                    mutableListOf("01989f2e-7800-7000-8000-000000000080"),
                ),
                storage = storage,
                transport = transport,
                platformFacts = FounderHQPlatformFactsProvider { emptyMap() },
            ),
        )
        client.readyForCapture()
        val completed = CountDownLatch(1)
        var prepared: FounderHQPreparedPurchase? = null

        client.preparePurchase(FounderHQPurchaseSource.REVENUECAT) { result ->
            prepared = result.getOrThrow()
            completed.countDown()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (
            System.nanoTime() < deadline &&
            storage.getString("state_v2")?.contains("UNACKNOWLEDGED") != true
        ) {
            Thread.yield()
        }
        while (System.nanoTime() < deadline && completed.count > 0) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("checkout completion was not invoked", completed.count == 0L)
        assertTrue(prepared != null)

        val state = JSONObject(checkNotNull(storage.getString("state_v2")))
        val queue = state.getJSONArray("queue")
        val event = (0 until queue.length())
            .map { queue.getJSONObject(it) }
            .first { it.getString("event") == "\$mobile_purchase_prepared" }
        assertEquals(
            "UNACKNOWLEDGED",
            event.getJSONObject("properties").getString("prepared_acknowledgement"),
        )
        client.close()
    }

    @Test
    fun hungTransportDoesNotBlockPurchasePreparation() {
        val transport = NeverReturningTransport()
        val client = purchasePreparationClient(TestStorage(), transport, "transport")
        client.readyForCapture()
        val completed = CountDownLatch(1)
        val startedAt = System.nanoTime()

        client.preparePurchase(FounderHQPurchaseSource.REVENUECAT) { result ->
            result.getOrThrow()
            completed.countDown()
        }
        assertTrue("transport was not entered", transport.started.await(1, TimeUnit.SECONDS))
        idleMainLooperUntil(completed)

        assertTrue("checkout completion was not invoked", completed.count == 0L)
        assertTrue(System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(1))
        assertPreparedUnacknowledged(client.persistedState())
        transport.release.countDown()
        client.close()
    }

    @Test
    fun hungStorageDoesNotBlockPurchasePreparation() {
        val storage = NeverReturningStorage()
        val client = purchasePreparationClient(
            storage,
            TestTransport().also { it.online = false },
            "storage",
        )
        client.readyForCapture()
        storage.blockWrites = true
        val completed = CountDownLatch(1)
        val startedAt = System.nanoTime()

        client.preparePurchase(FounderHQPurchaseSource.REVENUECAT) { result ->
            result.getOrThrow()
            completed.countDown()
        }
        assertTrue("storage was not entered", storage.started.await(1, TimeUnit.SECONDS))
        idleMainLooperUntil(completed)

        assertTrue("checkout completion was not invoked", completed.count == 0L)
        assertTrue(System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(1))
        assertPreparedUnacknowledged(client.persistedState())
        storage.release.countDown()
        client.close()
    }

    @Test
    fun executesEveryApplicableFixtureExactlyAgainstSharedGolden() {
        val suite = loadSuite()
        val capabilities = suite.getJSONObject("capability_matrix")
            .getJSONArray("android")
            .strings()
            .toSet()
        val exceptions = suite.getJSONObject("capability_exceptions")
            .getJSONObject("android")
        val fixtures = suite.getJSONArray("fixtures")
        val expectedRan = mutableListOf<String>()
        val actualRan = mutableListOf<String>()
        val notApplicable = mutableMapOf<String, List<String>>()

        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val requires = fixture.getJSONArray("requires").strings().toSet()
            val missing = (requires - capabilities).sorted()
            if (missing.isNotEmpty()) {
                val reasons = missing.map { capability ->
                    exceptions.optString(capability).also { reason ->
                        assertTrue(
                            "$capability needs an explicit Android N/A reason",
                            reason.isNotBlank(),
                        )
                    }
                }
                notApplicable[fixture.getString("name")] = reasons
                continue
            }
            expectedRan += fixture.getString("name")
            val actual = runFixture(fixture)
            val tokens = fixtureTokens(fixture)
            val expected = expandTokens(
                fixture.getJSONObject("expected").getJSONObject("mobile"),
                tokens,
            )
            assertEquals(
                fixture.getString("name"),
                canonicalJson(expected),
                canonicalJson(actual),
            )
            actualRan += fixture.getString("name")
        }

        assertEquals(expectedRan, actualRan)
        assertEquals(fixtures.length(), actualRan.size + notApplicable.size)
    }

    private fun runFixture(fixture: JSONObject): JSONObject {
        val name = fixture.getString("name")
        val providers = fixture.getJSONObject("providers")
        val clock = TestClock(Instant.parse(providers.getString("start_time")).toEpochMilli())
        val uuidSequences = providers.getJSONObject("uuid_sequences")
        val ids = uuidSequences.getJSONArray("mobile").strings().toMutableList()
        val sessionSequences = providers.optJSONObject("session_uuid_sequences")
        val sessionIds = (sessionSequences?.optJSONArray("mobile")
            ?: providers.getJSONArray("session_uuids")).strings().toMutableList()
        val uuid = TestUuidProvider(ids, sessionIds)
        val storage = TestStorage()
        providers.optJSONObject("initial_storage")
            ?.optJSONObject("state")
            ?.let { storage.putString("state_v2", it.toString()) }
        providers.optJSONObject("initial_storage")
            ?.optJSONObject("remote_config")
            ?.let { storage.putString("remote_config_v1_fhq_pk_fixture", it.toString()) }
        val transport = TestTransport()
        val platformFacts = providers.getJSONObject("platform_facts")
            .optJSONObject("android")
            ?.let(::jsonMap)
            ?: emptyMap()
        val dependencies = FounderHQEventsDependencies(
            clock = clock,
            uuid = uuid,
            storage = storage,
            transport = transport,
            platformFacts = FounderHQPlatformFactsProvider { platformFacts },
        )
        var apiKey = "fhq_pk_fixture"
        var configuration = fixtureConfiguration(null)
        var client: FounderHQEvents? = null
        val actionResults = JSONArray()
        val preparedPurchases = mutableMapOf<String, FounderHQPreparedPurchase>()
        val actions = fixture.getJSONArray("actions")

        for (index in 0 until actions.length()) {
            val action = actions.getJSONObject(index)
            val type = action.getString("type")
            val actionResult = JSONObject().put("index", index).put("type", type)
            when (type) {
                "init" -> {
                    apiKey = action.getString("api_key")
                        .replace("{{platform_api_key}}", "fhq_pk_fixture")
                    val options = action.optJSONObject("options")
                    configuration = fixtureConfiguration(options)
                    transport.hangWhenOffline = options?.optBoolean("hung_transport") ?: false
                    client = FounderHQEvents(
                        RuntimeEnvironment.getApplication(),
                        apiKey,
                        configuration,
                        dependencies,
                    )
                    client!!.readyForCapture()
                }
                "capture" -> client!!.capture(
                    action.getString("event"),
                    expandActionProperties(action.optJSONObject("properties")),
                )
                "identify" -> client!!.identify(
                    action.getString("distinct_id"),
                    action.optJSONObject("properties")?.let(::jsonMap) ?: emptyMap(),
                    fixtureAccount(action.opt("account")),
                )
                "setAccount" -> fixtureAccount(action.opt("account"))
                    ?.let(client!!::setAccount)
                    ?: client!!.clearAccount()
                "clearAccount" -> client!!.clearAccount()
                "setAccountProperties" -> client!!.setAccountProperties(
                    jsonMap(action.getJSONObject("properties")),
                )
                "screen" -> client!!.screen(
                    action.getString("name"),
                    action.optJSONObject("properties")?.let(::jsonMap) ?: emptyMap(),
                )
                "setPersonProperties" -> client!!.setPersonProperties(
                    jsonMap(action.getJSONObject("set")),
                    action.optJSONObject("set_once")?.let(::jsonMap) ?: emptyMap(),
                )
                "register" -> client!!.register(jsonMap(action.getJSONObject("properties")))
                "registerOnce" -> client!!.registerOnce(jsonMap(action.getJSONObject("properties")))
                "unregister" -> client!!.unregister(action.getString("key"))
                "advanceClock" -> clock.advance(action.getLong("milliseconds"))
                "goOffline" -> transport.online = false
                "goOnline" -> transport.online = true
                "receiveAck" -> transport.nextAck = action.getJSONObject("response")
                "flush" -> actionResult.put("result", client!!.flush())
                "restartWithPersistence" -> {
                    client = FounderHQEvents(
                        RuntimeEnvironment.getApplication(),
                        apiKey,
                        configuration,
                        dependencies,
                    )
                    client!!.readyForCapture()
                }
                "applyRemoteConfig" -> {
                    val remote = action.getJSONObject("config")
                    transport.nextRemoteConfig = remote
                    val before = transport.configUrls.size
                    assertTrue("$name remote config response", client!!.refreshRemoteConfig())
                    assertEquals("$name remote config transport", before + 1, transport.configUrls.size)
                    actionResult.put("config", JSONObject(remote.toString()))
                    actionResult.put("transport", JSONObject()
                        .put("method", "GET")
                        .put("path", "/i/v1/analytics/config"))
                }
                "reset" -> client!!.reset()
                "optIn" -> client!!.optIn()
                "optOut" -> client!!.optOut()
                "lifecycle" -> client!!.recordLifecycle(action.getString("state"))
                "prepare-purchase" -> {
                    val completed = CountDownLatch(1)
                    var result: Result<FounderHQPreparedPurchase>? = null
                    client!!.preparePurchase(fixturePurchaseSource(action.getString("source"))) {
                        result = it
                        completed.countDown()
                    }
                    idleMainLooperUntil(completed)
                    val prepared = checkNotNull(result).getOrThrow()
                    preparedPurchases[action.getString("ref")] = prepared
                    actionResult.put("result", JSONObject()
                        .put("purchaseContextToken", prepared.purchaseContextToken)
                        .put("appAccountToken", prepared.appAccountToken ?: JSONObject.NULL)
                        .put(
                            "obfuscatedExternalAccountId",
                            prepared.obfuscatedExternalAccountId ?: JSONObject.NULL,
                        ))
                    transport.releaseOfflineHang()
                }
                "observe-purchase" -> {
                    val reference = action.getString("prepared")
                    client!!.observePurchase(
                        fixtureObservedPurchase(action.getJSONObject("purchase")),
                        checkNotNull(preparedPurchases[reference]) {
                            "$name references unknown preparation $reference"
                        },
                    )
                }
                "claim-subscription" -> {
                    val receipt = client!!.claimSubscription(
                        fixtureObservedPurchase(action.getJSONObject("purchase")),
                        FounderHQRevenueClaimConfirmation.MOVE_FUTURE_REVENUE,
                    )
                    actionResult.put("result", JSONObject()
                        .put("claimId", receipt.claimId)
                        .put("status", receipt.status))
                }
                else -> error("Unexpected Android fixture action $type in $name")
            }
            actionResults.put(actionResult)
        }

        assertEquals("$name left UUID provider values", 0, uuid.remainingIds)
        assertEquals("$name left session UUID values", 0, uuid.remainingSessionIds)
        transport.urls.forEach { assertEquals("https://i.getfounderhq.com/i/v2/e", it) }
        transport.configUrls.forEach {
            assertEquals("https://i.getfounderhq.com/i/v1/analytics/config", it)
        }
        transport.headers.forEach {
            assertEquals("Bearer $apiKey", it["Authorization"])
            if (it.containsKey("Content-Type")) {
                assertEquals(
                    "${FounderHQEvents.SDK_NAME}/${FounderHQEvents.SDK_VERSION}",
                    it["FounderHq-Sdk-Info"],
                )
            }
        }
        val persisted = checkNotNull(client).persistedState()
        val normalized = normalizePersistedState(persisted)
            .put(
                "remote_config",
                storage.getString("remote_config_v1_fhq_pk_fixture")?.let(::JSONObject)
                    ?: JSONObject.NULL,
            )
        return JSONObject()
            .put("wire_batches", JSONArray(transport.bodies.map(::JSONObject)))
            .put("persisted_state", normalized)
            .put("directive_handling", JSONObject().put("actions", actionResults))
    }

    private fun loadSuite(): JSONObject = JSONObject(
        checkNotNull(javaClass.classLoader?.getResource("conformance-v2.json")).readText(),
    )
}

private fun fixtureConfiguration(options: JSONObject?): FounderHQEventsConfig =
    FounderHQEventsConfig(
        flushAt = 20,
        flushIntervalSeconds = 0,
        personProfiles = when (options?.optString("person_profiles")) {
            "always" -> PersonProfiles.ALWAYS
            "never" -> PersonProfiles.NEVER
            else -> PersonProfiles.IDENTIFIED_ONLY
        },
        optOutByDefault = options?.optBoolean("opt_out_by_default") ?: false,
        captureLifecycle = options?.optBoolean("capture_lifecycle") ?: false,
        captureScreens = options?.optBoolean("capture_screens") ?: false,
        captureSessions = options?.optBoolean("capture_sessions") ?: false,
        captureInstallUpdates = false,
        remoteConfig = options
            ?.takeIf { it.has("remote_config") }
            ?.getBoolean("remote_config")
            ?: true,
        purchasePrepareTimeoutMillis = options
            ?.optLong("purchase_prepare_timeout_ms", 3_000)
            ?: 3_000,
        account = fixtureAccount(options?.opt("account")),
    )

private fun fixturePurchaseSource(value: String): FounderHQPurchaseSource = when (value) {
    "revenuecat" -> FounderHQPurchaseSource.REVENUECAT
    "app_store" -> FounderHQPurchaseSource.APP_STORE
    "google_play" -> FounderHQPurchaseSource.PLAY_BILLING
    else -> error("Unknown fixture purchase source: $value")
}

private fun fixtureObservedPurchase(purchase: JSONObject): FounderHQObservedPurchase {
    val source = fixturePurchaseSource(purchase.getString("source"))
    return when (source) {
        FounderHQPurchaseSource.REVENUECAT -> {
            val transactionId = purchase.getString("transactionIdentifier")
            FounderHQObservedPurchase(
                source = source,
                store = purchase.optString("store", "app_store").uppercase(),
                transactionId = transactionId,
                subscriptionId = purchase
                    .optString("originalTransactionIdentifier")
                    .takeIf(String::isNotBlank)
                    ?: transactionId,
                purchaseToken = purchase.optString("purchaseToken").takeIf(String::isNotBlank),
                productId = purchase.optString("productIdentifier").takeIf(String::isNotBlank),
                purchasedAt = purchase.optString("purchaseDate").takeIf(String::isNotBlank),
            )
        }
        FounderHQPurchaseSource.APP_STORE -> FounderHQObservedPurchase(
            source = source,
            store = "APP_STORE",
            transactionId = purchase.getString("transactionId"),
            subscriptionId = purchase.getString("originalTransactionId"),
            productId = purchase.optString("productId").takeIf(String::isNotBlank),
            purchasedAt = purchase.optString("purchaseDate").takeIf(String::isNotBlank),
        )
        FounderHQPurchaseSource.PLAY_BILLING -> {
            val purchaseToken = purchase.getString("purchaseToken")
            FounderHQObservedPurchase(
                source = source,
                store = "GOOGLE_PLAY",
                transactionId = purchase.optString("orderId").takeIf(String::isNotBlank),
                subscriptionId = purchaseToken,
                purchaseToken = purchaseToken,
                productId = purchase.optJSONArray("products")?.optString(0)
                    ?.takeIf(String::isNotBlank),
                purchasedAt = purchase
                    .takeIf { it.has("purchaseTime") && !it.isNull("purchaseTime") }
                    ?.getLong("purchaseTime")
                    ?.let(::isoFixtureTimestamp),
            )
        }
    }
}

private fun isoFixtureTimestamp(milliseconds: Long): String =
    java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(Instant.ofEpochMilli(milliseconds))

private fun fixtureAccount(value: Any?): FounderHQAccountContext? = when (value) {
    is String -> FounderHQAccountContext(value)
    is JSONObject -> FounderHQAccountContext(
        key = value.optString("key"),
        properties = value.optJSONObject("properties")?.let(::jsonMap) ?: emptyMap(),
        contextToken = value.optString("contextToken").takeIf(String::isNotBlank),
    )
    else -> null
}

private fun normalizePersistedState(state: JSONObject): JSONObject = JSONObject()
    .put("anonymous_id", state.get("anonymousId"))
    .put("distinct_id", state.get("distinctId"))
    .put("identified", state.get("identified"))
    .put("session_id", state.get("sessionId"))
    .put("session_started_at", state.get("sessionStartedAt"))
    .put("last_activity_at", state.get("lastActivityAt"))
    .put("registered", state.get("registered"))
    .put("pending_set", state.get("pendingSet"))
    .put("pending_set_once", state.get("pendingSetOnce"))
    .put("opted_out", state.get("optedOut"))
    .put("app_version", JSONObject.NULL)
    .put("queue", state.get("queue"))

private fun fixtureTokens(fixture: JSONObject): Map<String, Any?> {
    val facts = fixture.getJSONObject("providers")
        .getJSONObject("platform_facts")
        .optJSONObject("android")
    val tokens = mutableMapOf<String, Any?>(
        "oversize_65537_bytes" to "x".repeat(65_537),
        "lib" to FounderHQEvents.SDK_NAME,
        "lib_version" to FounderHQEvents.SDK_VERSION,
        "platform" to "android",
    )
    facts?.keys()?.forEach { key -> tokens[key.removePrefix("$")] = facts.get(key) }
    return tokens
}

private fun expandActionProperties(properties: JSONObject?): Map<String, Any?> {
    if (properties == null) return emptyMap()
    return jsonMap(expandTokens(properties, mapOf(
        "oversize_65537_bytes" to "x".repeat(65_537),
    )) as JSONObject)
}

private fun jsonMap(json: JSONObject): Map<String, Any?> =
    json.keys().asSequence().associateWith { key ->
        json.get(key).takeUnless { it == JSONObject.NULL }
    }

private fun JSONArray.strings(): List<String> =
    (0 until length()).map(::getString)

private fun expandTokens(value: Any?, tokens: Map<String, Any?>): Any? = when (value) {
    is JSONObject -> JSONObject().also { output ->
        value.keys().forEach { key -> output.put(key, expandTokens(value.get(key), tokens)) }
    }
    is JSONArray -> JSONArray().also { output ->
        for (index in 0 until value.length()) output.put(expandTokens(value.get(index), tokens))
    }
    is String -> if (value.startsWith("{{") && value.endsWith("}}")) {
        val key = value.removePrefix("{{").removeSuffix("}}")
        check(tokens.containsKey(key)) { "Unknown fixture token: $value" }
        tokens[key]
    } else value
    else -> value
}

private fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        "${JSONObject.quote(it)}:${canonicalJson(value.get(it))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
        canonicalJson(value.get(it))
    }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> error("Unsupported canonical JSON value: ${value::class.java.name}")
}

private fun purchasePreparationClient(
    storage: FounderHQStorage,
    transport: FounderHQTransport,
    label: String,
) = FounderHQEvents(
    RuntimeEnvironment.getApplication(),
    "fhq_pk_purchase_$label",
    FounderHQEventsConfig(
        flushIntervalSeconds = 0,
        captureLifecycle = false,
        captureScreens = false,
        captureSessions = false,
        captureInstallUpdates = false,
        remoteConfig = false,
        purchasePrepareTimeoutMillis = 10,
    ),
    FounderHQEventsDependencies(
        clock = TestClock(1_786_694_000_000),
        uuid = TestUuidProvider(
            mutableListOf(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000003",
            ),
            mutableListOf("01989f2e-7800-7000-8000-000000000080"),
        ),
        storage = storage,
        transport = transport,
        platformFacts = FounderHQPlatformFactsProvider { emptyMap() },
    ),
)

private fun idleMainLooperUntil(completed: CountDownLatch) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (System.nanoTime() < deadline && completed.count > 0) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.yield()
    }
}

private fun assertPreparedUnacknowledged(state: JSONObject) {
    val queue = state.getJSONArray("queue")
    val event = (0 until queue.length())
        .map { queue.getJSONObject(it) }
        .first { it.getString("event") == "\$mobile_purchase_prepared" }
    assertEquals(
        "UNACKNOWLEDGED",
        event.getJSONObject("properties").getString("prepared_acknowledgement"),
    )
}

private class TestClock(private var value: Long) : FounderHQClock {
    override fun nowMillis(): Long = value
    fun advance(milliseconds: Long) { value += milliseconds }
}

private class TestUuidProvider(
    private val ids: MutableList<String>,
    private val sessionIds: MutableList<String>,
) : FounderHQUuidProvider {
    val remainingIds: Int get() = ids.size
    val remainingSessionIds: Int get() = sessionIds.size
    override fun uuid(): String = checkNotNull(ids.removeFirstOrNull()) {
        "Fixture exhausted its UUID provider"
    }
    override fun uuidV7(nowMillis: Long): String = checkNotNull(sessionIds.removeFirstOrNull()) {
        "Fixture exhausted its session UUID provider"
    }
}

private class TestStorage : FounderHQStorage {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class NeverReturningStorage : FounderHQStorage {
    private val values = mutableMapOf<String, String>()
    @Volatile var blockWrites = false
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun getString(key: String): String? = synchronized(values) { values[key] }

    override fun putString(key: String, value: String?) {
        if (blockWrites) {
            started.countDown()
            release.await()
        }
        synchronized(values) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}

private class NeverReturningTransport : FounderHQTransport {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun send(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): FounderHQTransportResponse {
        started.countDown()
        release.await()
        throw IOException("released after checkout bound assertion")
    }
}

private class TestTransport : FounderHQTransport {
    val urls = mutableListOf<String>()
    val configUrls = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()
    val bodies = mutableListOf<String>()
    var online = true
    var hangWhenOffline = false
    private val offlineHangRelease = CountDownLatch(1)
    var nextAck: JSONObject? = null
    var nextRemoteConfig: JSONObject? = null

    override fun get(
        url: String,
        headers: Map<String, String>,
    ): FounderHQTransportResponse {
        configUrls += url
        this.headers += headers
        if (!online) throw java.io.IOException("fixture offline")
        val config = nextRemoteConfig ?: JSONObject()
        nextRemoteConfig = null
        return FounderHQTransportResponse(
            200,
            JSONObject().put("config", config).toString(),
        )
    }

    override fun send(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): FounderHQTransportResponse {
        urls += url
        this.headers += headers
        bodies += body
        if (!online) {
            if (hangWhenOffline) offlineHangRelease.await()
            throw java.io.IOException("fixture offline")
        }
        if (nextAck?.optString("kind") == "retry") {
            nextAck = null
            throw java.io.IOException("fixture retry")
        }
        val batch = JSONObject(body).getJSONArray("batch")
        val requested = nextAck
        nextAck = null
        val requestedResults = requested?.optJSONObject("results") ?: JSONObject()
        val results = JSONObject()
        for (index in 0 until batch.length()) {
            val uuid = batch.getJSONObject(index).getString("uuid")
            val fixtureResult = requestedResults.optJSONObject(uuid)
            results.put(
                uuid,
                JSONObject()
                .put(
                    "result",
                    fixtureResult?.optString("result")?.takeIf(String::isNotBlank) ?: "ok",
                )
                .also { result ->
                    fixtureResult?.optString("details")?.takeIf(String::isNotBlank)
                        ?.let { result.put("details", it) }
                },
            )
        }
        val response = JSONObject().put("results", results)
        requested?.optJSONArray("directives")?.let { response.put("directives", it) }
        return FounderHQTransportResponse(202, response.toString())
    }

    fun releaseOfflineHang() { offlineHangRelease.countDown() }
}

private class TestRevenueCatIdentity : FounderHQRevenueCatIdentity {
    val configured = mutableListOf<String>()
    val loggedIn = mutableListOf<String>()
    override fun configure(appUserId: String) { configured += appUserId }
    override fun logIn(appUserId: String) { loggedIn += appUserId }
}

private val UUID_V7 = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
