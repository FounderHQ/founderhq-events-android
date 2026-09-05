package com.founderhq.events

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class PersonProfiles(val wireValue: String) {
    ALWAYS("always"), IDENTIFIED_ONLY("identified_only"), NEVER("never")
}

fun interface FounderHQClock { fun nowMillis(): Long }

interface FounderHQUuidProvider {
    fun uuid(): String
    fun uuidV7(nowMillis: Long): String
}

interface FounderHQStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

data class FounderHQTransportResponse(val statusCode: Int, val body: String)

interface FounderHQTransport {
    fun send(url: String, headers: Map<String, String>, body: String): FounderHQTransportResponse

    /** GET seam for SDK configuration. Existing custom event transports remain source-compatible. */
    fun get(url: String, headers: Map<String, String>): FounderHQTransportResponse {
        throw UnsupportedOperationException("Remote config GET is not implemented by this transport")
    }
}

fun interface FounderHQPlatformFactsProvider {
    /** Canonical v2 `$` properties. Dimensions must be physical pixels. */
    fun properties(): Map<String, Any?>
}

interface FounderHQRevenueCatIdentity {
    fun configure(appUserId: String)
    fun logIn(appUserId: String)
}

data class FounderHQPurchaseAttribution(
    val appAccountToken: String,
    val obfuscatedExternalAccountId: String,
    val revenueCatAppUserId: String,
)

enum class FounderHQPurchaseSource { REVENUECAT, APP_STORE, PLAY_BILLING }

data class FounderHQPreparedPurchase(
    val purchaseContextToken: String,
    val obfuscatedExternalAccountId: String?,
    internal val source: FounderHQPurchaseSource,
    internal val accountProperties: Map<String, Any?>,
    val appAccountToken: String? = null,
)

enum class FounderHQRevenueClaimConfirmation { MOVE_FUTURE_REVENUE }

data class FounderHQSubscriptionClaimReceipt(
    val claimId: String,
    val status: String,
)

data class FounderHQObservedPurchase(
    val source: FounderHQPurchaseSource,
    val store: String,
    val transactionId: String? = null,
    val subscriptionId: String? = null,
    val purchaseToken: String? = null,
    val productId: String? = null,
    val purchasedAt: String? = null,
)

data class FounderHQAccountContext(
    val key: String,
    val properties: Map<String, Any?> = emptyMap(),
    val contextToken: String? = null,
)

data class FounderHQEventsDependencies(
    val clock: FounderHQClock,
    val uuid: FounderHQUuidProvider,
    val storage: FounderHQStorage,
    val transport: FounderHQTransport,
    val platformFacts: FounderHQPlatformFactsProvider,
)

data class FounderHQEventsConfig(
    val host: String = "https://app.getfounderhq.com",
    val flushAt: Int = 20,
    val flushIntervalSeconds: Long = 5,
    val personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY,
    val optOutByDefault: Boolean = false,
    val captureLifecycle: Boolean = true,
    val captureScreens: Boolean = true,
    val captureSessions: Boolean = true,
    val captureInstallUpdates: Boolean = true,
    val remoteConfig: Boolean = true,
    val account: FounderHQAccountContext? = null,
    val purchasePrepareTimeoutMillis: Long = 3_000,
    val maxQueueSize: Int = 100,
    val eventTtlMillis: Long = 24 * 60 * 60 * 1_000,
)

class FounderHQEvents(
    context: Context,
    private val apiKey: String,
    private val config: FounderHQEventsConfig = FounderHQEventsConfig(),
    injectedDependencies: FounderHQEventsDependencies? = null,
    private val revenueCatIdentity: FounderHQRevenueCatIdentity? = null,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val application = context.applicationContext as Application
    private val dependencies = injectedDependencies ?: defaultDependencies(application, apiKey)
    private val remoteConfigKey = "remote_config_v1_${apiKey.take(16)}"
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val purchaseExecutor = Executors.newCachedThreadPool()
    private val initialized = CountDownLatch(1)
    @Volatile private var initializationThread: Thread? = null
    private val lock = Any()
    private val stateWriter = SerializedStateWriter(dependencies.storage, STATE_KEY)
    private var state = State.restore(
        dependencies,
        config.optOutByDefault,
        config.maxQueueSize,
        config.eventTtlMillis,
    )
    private var foregroundActivities = 0
    private var captureLifecycleEnabled = config.captureLifecycle
    private var captureScreensEnabled = config.captureScreens
    private var captureSessionsEnabled = config.captureSessions
    private var callbacksRegistered = false

    init {
        applyCachedRemoteConfig()
        if (config.flushIntervalSeconds > 0) {
            executor.scheduleWithFixedDelay(
                { flush() },
                config.flushIntervalSeconds,
                config.flushIntervalSeconds,
                TimeUnit.SECONDS,
            )
        }
        executor.execute {
            initializationThread = Thread.currentThread()
            try {
                config.account?.let { setAccountLocked(it, emit = true) }
                if (revenueCatIdentity != null) {
                    synchronized(lock) {
                        state.purchaseAttributionEnabled = true
                        persist()
                        revenueCatIdentity.configure(state.purchaseAttributionToken)
                        capture("\$set", mapOf(
                            "\$purchase_attribution_token" to state.purchaseAttributionToken,
                        ))
                    }
                }
                if (config.remoteConfig) fetchRemoteConfig()
                synchronized(lock) {
                    updateLifecycleRegistration()
                    if (state.needsSessionStart && captureSessionsEnabled && !state.optedOut) {
                        capture("\$session_start")
                    }
                    if (config.captureInstallUpdates) captureInstallOrUpdate()
                }
            } finally {
                initializationThread = null
                initialized.countDown()
            }
        }
    }

    fun capture(event: String, properties: Map<String, Any?> = emptyMap()) {
        awaitInitialization()
        val name = event.trim()
        if (!isAllowedEvent(name)) return
        synchronized(lock) {
            if (state.optedOut) return
            val rotated = rotateSession()
            if (rotated && captureSessionsEnabled && name != "\$session_start") {
                capture("\$session_start")
            }
            val merged = automaticProperties().toMutableMap().apply {
                putAll(state.registered)
                putAll(properties)
                state.account?.let { account ->
                    put("\$groups", mapOf("account" to account.key))
                    put("\$account_span_id", account.spanId)
                    if (account.contextToken != null) {
                        put("\$account_context_token", account.contextToken)
                    } else {
                        remove("\$account_context_token")
                    }
                }
            }
            enqueueEventLocked(eventPayload(name, merged))
            state.lastActivityAt = dependencies.clock.nowMillis()
            persist()
            if (state.queue.length() >= config.flushAt) executor.execute { flush() }
        }
    }

    fun identify(
        distinctId: String,
        properties: Map<String, Any?> = emptyMap(),
        account: FounderHQAccountContext? = null,
    ) {
        awaitInitialization()
        val id = normalizeDistinctId(distinctId)
        if (id == null) {
            Log.w(SDK_NAME, "identify ignored an invalid distinct ID")
            return
        }
        synchronized(lock) {
            if (state.optedOut) return
            val accountSwitch = state.identified && state.distinctId != id
            if (accountSwitch) {
                val guestId = dependencies.uuid.uuid()
                state.anonymousId = guestId
                state.purchaseAttributionToken = guestId
                state.account = null
            }
            val groupSet = account?.let(::applyAccountLocked)
            val anonymousId = state.anonymousId
            val set = if (config.personProfiles == PersonProfiles.NEVER) {
                emptyMap()
            } else {
                state.pendingSet.toMutableMap().apply { putAll(properties) }
            }
            val setOnce = if (config.personProfiles == PersonProfiles.NEVER) {
                emptyMap()
            } else state.pendingSetOnce.toMap()
            state.distinctId = id
            state.identified = true
            state.pendingSet.clear()
            state.pendingSetOnce.clear()
            persist()
            if (state.purchaseAttributionEnabled) {
                revenueCatIdentity?.logIn(state.purchaseAttributionToken)
            }
            capture("\$identify", mapOf(
                "\$anon_distinct_id" to anonymousId,
                "\$set" to set,
                "\$set_once" to setOnce,
            ) + if (groupSet != null) mapOf("\$group_set" to groupSet) else emptyMap())
        }
    }

    fun setAccount(
        key: String,
        properties: Map<String, Any?> = emptyMap(),
        contextToken: String? = null,
    ) = setAccount(FounderHQAccountContext(key, properties, contextToken))

    fun setAccount(account: FounderHQAccountContext) = withInitializedState {
        setAccountLocked(account, emit = true)
    }

    fun clearAccount() = withInitializedState {
        state.account = null
        persist()
    }

    /** Alias for logout flows that use plural account/group terminology. */
    fun resetAccounts() = clearAccount()

    fun setAccountProperties(properties: Map<String, Any?>) = withInitializedState {
        val account = state.account ?: return@withInitializedState
        account.properties.putAll(properties)
        persist()
        capture("\$groupidentify", mapOf("\$group_set" to account.properties.toMap()))
    }

    fun setPersonProperties(
        set: Map<String, Any?>,
        setOnce: Map<String, Any?> = emptyMap(),
    ) {
        awaitInitialization()
        synchronized(lock) {
            if (config.personProfiles == PersonProfiles.NEVER) return
            val safeSet = set.filterKeys { it !in identityKeys }
            val safeSetOnce = setOnce.filterKeys { it !in identityKeys }
            if (config.personProfiles == PersonProfiles.IDENTIFIED_ONLY && !state.identified) {
                state.pendingSet.putAll(safeSet)
                safeSetOnce.forEach { (key, value) -> state.pendingSetOnce.putIfAbsent(key, value) }
                persist()
                return
            }
            capture("\$set", mapOf("\$set" to safeSet, "\$set_once" to safeSetOnce))
        }
    }

    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) {
        awaitInitialization()
        if (!captureScreensEnabled) return
        capture(
            "\$screen",
            mapOf("\$screen_name" to name, "\$screen_id" to dependencies.uuid.uuid()) + properties,
        )
    }

    fun register(properties: Map<String, Any?>) = withInitializedState {
        state.registered.putAll(properties); persist()
    }
    fun registerOnce(properties: Map<String, Any?>) = withInitializedState {
        properties.forEach { (key, value) -> state.registered.putIfAbsent(key, value) }; persist()
    }
    fun unregister(key: String) = withInitializedState { state.registered.remove(key); persist() }
    fun optIn() = withInitializedState { state.optedOut = false; persist() }
    fun optOut() = withInitializedState { state.optedOut = true; state.queue = JSONArray(); persist() }
    fun isOptedOut(): Boolean = withInitializedState { state.optedOut }
    fun getDistinctId(): String = withInitializedState { state.distinctId }
    fun getSessionId(): String = withInitializedState {
        if (rotateSession() && captureSessionsEnabled) capture("\$session_start")
        state.sessionId
    }

    fun purchaseAttribution(): FounderHQPurchaseAttribution = withInitializedState {
        state.purchaseAttributionEnabled = true
        persist()
        capture("\$set", mapOf(
            "\$purchase_attribution_token" to state.purchaseAttributionToken,
        ))
        FounderHQPurchaseAttribution(
            appAccountToken = state.purchaseAttributionToken,
            obfuscatedExternalAccountId = state.purchaseAttributionToken,
            revenueCatAppUserId = state.purchaseAttributionToken,
        )
    }

    fun preparePurchase(
        source: FounderHQPurchaseSource,
        completion: (Result<FounderHQPreparedPurchase>) -> Unit,
    ) {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(
            maxOf(0, config.purchasePrepareTimeoutMillis),
        )
        val deadline = System.nanoTime() + timeoutNanos
        purchaseExecutor.execute {
            awaitInitialization()
            val prepared = synchronized(lock) {
                val token = dependencies.uuid.uuid()
                state.purchaseAttributionEnabled = true
                val accountProperties = controlAccountProperties(state.account)
                captureControlLocked(
                    "\$mobile_purchase_prepared",
                    mapOf(
                        "purchase_context_token" to token,
                        "source" to wirePurchaseSource(source),
                        "mobile_identity_token" to state.purchaseAttributionToken,
                        "prepared_at" to isoTimestamp(dependencies.clock.nowMillis()),
                        "prepared_acknowledgement" to "UNACKNOWLEDGED",
                    ),
                    accountProperties,
                    persistImmediately = false,
                )
                FounderHQPreparedPurchase(
                    purchaseContextToken = token,
                    appAccountToken =
                        if (source == FounderHQPurchaseSource.APP_STORE) token else null,
                    obfuscatedExternalAccountId =
                        if (source == FounderHQPurchaseSource.PLAY_BILLING) token else null,
                    source = source,
                    accountProperties = accountProperties,
                )
            }
            val delivery = purchaseExecutor.submit<Boolean> {
                val reserved = synchronized(lock) {
                    stateWriter.reserve(state.toJson().toString())
                }
                if (reserved != null) stateWriter.drain(reserved)
                flush()
            }
            var acknowledged = false
            try {
                acknowledged = delivery.get(
                    maxOf(0, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS,
                )
            } catch (_: TimeoutException) {
                // The blocked attempt remains best-effort background work.
            } catch (_: Exception) {
                // Checkout still proceeds with the in-memory prepared context.
            }
            // Durability is best-effort here: a hung store may lose this snapshot on process
            // death, but checkout must never block and UNACKNOWLEDGED can be persisted later.
            purchaseExecutor.execute {
                synchronized(lock) {
                    for (index in 0 until state.queue.length()) {
                        val event = state.queue.getJSONObject(index)
                        val properties = event.optJSONObject("properties") ?: continue
                        if (
                            event.optString("event") == "\$mobile_purchase_prepared" &&
                            properties.optString("purchase_context_token") == prepared.purchaseContextToken
                        ) {
                            properties.put(
                                "prepared_acknowledgement",
                                if (acknowledged) "ACKNOWLEDGED" else "UNACKNOWLEDGED",
                            )
                        }
                    }
                    persist()
                }
            }
            Handler(Looper.getMainLooper()).post {
                completion(Result.success(prepared))
            }
        }
    }

    /** Calls BillingFlowParams.Builder.setObfuscatedAccountId without taking a hard dependency. */
    fun <T : Any> applyPurchaseContext(
        builder: T,
        prepared: FounderHQPreparedPurchase,
    ): T {
        val token = prepared.obfuscatedExternalAccountId
            ?: throw IllegalArgumentException("Prepared purchase is not Play Billing context")
        val method = builder.javaClass.methods.firstOrNull {
            it.name == "setObfuscatedAccountId" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: throw IllegalArgumentException("Builder does not support setObfuscatedAccountId")
        method.invoke(builder, token)
        return builder
    }

    fun purchasesUpdatedListener(
        delegate: (List<FounderHQObservedPurchase>) -> Unit,
    ): (List<FounderHQObservedPurchase>, FounderHQPreparedPurchase?) -> Unit =
        { purchases, prepared ->
            purchases.forEach { observePurchase(it, prepared) }
            delegate(purchases)
        }

    fun revenueCatPurchaseCallback(
        prepared: FounderHQPreparedPurchase,
        delegate: (FounderHQObservedPurchase?, Throwable?) -> Unit,
    ): (FounderHQObservedPurchase?, Throwable?) -> Unit = { purchase, error ->
        if (purchase != null) observePurchase(purchase, prepared)
        delegate(purchase, error)
    }

    fun observePurchase(
        purchase: FounderHQObservedPurchase,
        prepared: FounderHQPreparedPurchase? = null,
    ) {
        awaitInitialization()
        synchronized(lock) {
            val context = prepared ?: return@synchronized
            require(context.source == purchase.source) {
                "Observed purchase source does not match its preparation"
            }
            captureControlLocked(
                "\$mobile_purchase_claim",
                claimProperties(context.purchaseContextToken, "PURCHASE", purchase),
                context.accountProperties,
            )
        }
    }

    fun claimSubscription(
        purchase: FounderHQObservedPurchase,
        confirmation: FounderHQRevenueClaimConfirmation,
    ): FounderHQSubscriptionClaimReceipt = withInitializedState {
        require(confirmation == FounderHQRevenueClaimConfirmation.MOVE_FUTURE_REVENUE)
        val claimId = dependencies.uuid.uuid()
        captureControlLocked(
            "\$mobile_purchase_claim",
            claimProperties(claimId, "TRANSFER", purchase),
            controlAccountProperties(state.account),
        )
        FounderHQSubscriptionClaimReceipt(claimId, "queued")
    }

    fun claimRevenueCatSubscription(
        purchase: FounderHQObservedPurchase,
        confirmation: FounderHQRevenueClaimConfirmation,
    ): FounderHQSubscriptionClaimReceipt {
        require(purchase.source == FounderHQPurchaseSource.REVENUECAT)
        return claimSubscription(purchase, confirmation)
    }

    fun applyRemoteConfig(remote: Map<String, Any?>) = withInitializedState {
        applyRemoteConfigLocked(remote)
    }

    /** Refetches operator capture settings through the authenticated config endpoint. */
    fun refreshRemoteConfig(): Boolean {
        awaitInitialization()
        return fetchRemoteConfig()
    }

    private fun applyRemoteConfigLocked(remote: Map<String, Any?>) {
        captureLifecycleEnabled =
            remote["capture_lifecycle"] as? Boolean ?: config.captureLifecycle
        captureScreensEnabled = remote["capture_screens"] as? Boolean ?: config.captureScreens
        captureSessionsEnabled = remote["capture_sessions"] as? Boolean ?: config.captureSessions
        updateLifecycleRegistration()
        val disabled = buildSet {
            if (!captureSessionsEnabled) add("\$session_start")
            if (!captureScreensEnabled) add("\$screen")
            if (!captureLifecycleEnabled) {
                add("\$application_opened")
                add("\$application_backgrounded")
            }
        }
        if (disabled.isNotEmpty()) {
            state.queue = JSONArray(
                (0 until state.queue.length())
                    .map { state.queue.getJSONObject(it) }
                    .filterNot { it.optString("event") in disabled },
            )
            persist()
        }
    }

    fun recordLifecycle(state: String) {
        awaitInitialization()
        if (!captureLifecycleEnabled) return
        when (state) {
            "active" -> capture("\$application_opened")
            "background" -> capture("\$application_backgrounded")
        }
    }

    fun reset() = withInitializedState {
        val optedOut = state.optedOut
        val purchaseAttributionEnabled = state.purchaseAttributionEnabled
        state = State.fresh(dependencies, optedOut)
        state.purchaseAttributionEnabled = purchaseAttributionEnabled
        persist()
        if (purchaseAttributionEnabled) {
            revenueCatIdentity?.logIn(state.purchaseAttributionToken)
            capture("\$set", mapOf(
                "\$purchase_attribution_token" to state.purchaseAttributionToken,
            ))
        }
    }

    fun captureDeepLink(url: String) {
        val uri = android.net.Uri.parse(url)
        val campaign = campaignKeys.mapNotNull { key ->
            uri.getQueryParameter(key)?.let { key to it }
        }.toMap()
        val safeDeepLink = uri.buildUpon().clearQuery().fragment(null).build().toString()
        capture("\$application_opened", campaign + mapOf("\$deep_link_url" to safeDeepLink))
    }

    fun captureInstallReferrer(properties: Map<String, String>) {
        capture("\$application_installed", properties + mapOf("source" to "play_install_referrer"))
    }

    fun flush(): Boolean {
        awaitInitialization()
        // Serialize flushes: overlapping timer, lifecycle, and manual callers
        // run one at a time, so two flushes can never send the same queue
        // head or rotate identity twice. The follower flushes whatever
        // remains, which the server deduplicates by event UUID.
        synchronized(flushGate) {
            return flushOnce()
        }
    }

    private val flushGate = Any()

    private fun flushOnce(): Boolean {
        val selected: List<JSONObject>
        synchronized(lock) {
            pruneQueueLocked()
            persist()
            if (state.optedOut || state.queue.length() == 0) return true
            // The ingest endpoint rejects batches over 100 items; an
            // unclamped flushAt above that would retry the same oversized
            // batch forever.
            val flushAt = minOf(100, maxOf(1, config.flushAt))
            selected = (0 until minOf(state.queue.length(), flushAt))
                .map { state.queue.getJSONObject(it) }
        }
        return try {
            val envelope = JSONObject()
                .put("sent_at", isoTimestamp(dependencies.clock.nowMillis()))
                .put("batch", JSONArray(selected))
            val response = dependencies.transport.send(
                config.host.trimEnd('/') + "/i/v2/e",
                mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $apiKey",
                    "FounderHq-Sdk-Info" to "$SDK_NAME/$SDK_VERSION",
                ),
                envelope.toString(),
            )
            if (response.statusCode !in 200..299) return false
            val ack = JSONObject(response.body)
            val results = ack.getJSONObject("results")
            val completed = mutableSetOf<String>()
            var hasRetry = false
            for (uuid in results.keys()) {
                val result = results.getJSONObject(uuid)
                if (result.getString("result") == "retry") hasRetry = true
                else completed += uuid
            }
            val directive = ack.optJSONArray("directives")?.let { directives ->
                (0 until directives.length())
                    .map { directives.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "rotate_distinct_id" }
            }
            val identify = if (directive != null) {
                selected.firstOrNull { it.optString("event") == "\$identify" }
            } else null
            synchronized(lock) {
                state.queue = JSONArray(
                    (0 until state.queue.length())
                        .map { state.queue.getJSONObject(it) }
                        .filterNot { it.getString("uuid") in completed },
                )
                if (identify != null) retryIdentify(
                    identify,
                    directive?.optString("distinct_id")?.takeIf { it.isNotBlank() },
                )
                persist()
            }
            !hasRetry
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        flush()
        executor.shutdown()
        purchaseExecutor.shutdownNow()
        if (callbacksRegistered) {
            application.unregisterActivityLifecycleCallbacks(this)
            callbacksRegistered = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activity.intent?.dataString?.let(::captureDeepLink)
    }
    override fun onActivityStarted(activity: Activity) {
        foregroundActivities++
        if (foregroundActivities == 1) recordLifecycle("active")
    }
    override fun onActivityResumed(activity: Activity) {
        if (captureScreensEnabled) {
            screen(activity.title?.toString()?.takeIf { it.isNotBlank() } ?: activity.javaClass.simpleName)
        }
    }
    override fun onActivityStopped(activity: Activity) {
        foregroundActivities = maxOf(0, foregroundActivities - 1)
        if (foregroundActivities == 0 && captureLifecycleEnabled) {
            recordLifecycle("background")
            executor.execute { flush() }
        }
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    internal fun persistedState(): JSONObject = synchronized(lock) { state.toJson() }

    fun readyForCapture() { awaitInitialization() }

    private fun updateLifecycleRegistration() {
        val shouldRegister = captureLifecycleEnabled || captureScreensEnabled
        if (shouldRegister && !callbacksRegistered) {
            application.registerActivityLifecycleCallbacks(this)
            callbacksRegistered = true
        } else if (!shouldRegister && callbacksRegistered) {
            application.unregisterActivityLifecycleCallbacks(this)
            callbacksRegistered = false
        }
    }

    private fun applyCachedRemoteConfig() {
        if (!config.remoteConfig) return
        try {
            val cached = dependencies.storage.getString(remoteConfigKey) ?: return
            synchronized(lock) { applyRemoteConfigLocked(jsonMap(JSONObject(cached))) }
        } catch (_: Exception) {
            // A corrupt cache falls through to the authenticated network config.
        }
    }

    private fun fetchRemoteConfig(): Boolean {
        if (!config.remoteConfig) return false
        return try {
            val response = dependencies.transport.get(
                config.host.trimEnd('/') + "/i/v1/analytics/config",
                mapOf("Authorization" to "Bearer $apiKey"),
            )
            if (response.statusCode !in 200..299) return false
            val remote = JSONObject(response.body).optJSONObject("config") ?: return false
            dependencies.storage.putString(remoteConfigKey, remote.toString())
            synchronized(lock) { applyRemoteConfigLocked(jsonMap(remote)) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun awaitInitialization() {
        if (Thread.currentThread() !== initializationThread) initialized.await()
    }

    private fun jsonMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { key ->
            json.opt(key).takeUnless { it == JSONObject.NULL }
        }

    private inline fun <T> withInitializedState(block: () -> T): T {
        awaitInitialization()
        return synchronized(lock, block)
    }

    private fun eventPayload(
        event: String,
        properties: Map<String, Any?>,
    ): JSONObject = JSONObject()
        .put("uuid", dependencies.uuid.uuid())
        .put("event", event)
        .put("distinct_id", state.distinctId)
        .put("timestamp", isoTimestamp(dependencies.clock.nowMillis()))
        .put(
            "properties",
            JSONObject(properties.filterKeys {
                it !in setOf("\$lib", "\$lib_version", "\$session_id", "\$window_id")
            }),
        )
        .put("session_id", state.sessionId)
        .put("options", JSONObject()
            .put(
                "process_person_profile",
                config.personProfiles == PersonProfiles.ALWAYS ||
                    (config.personProfiles == PersonProfiles.IDENTIFIED_ONLY && state.identified),
            ))

    private fun captureControlLocked(
        event: String,
        properties: Map<String, Any?>,
        accountProperties: Map<String, Any?>,
        persistImmediately: Boolean = true,
    ) {
        if (state.optedOut) return
        val merged = automaticProperties().toMutableMap().apply {
            put("\$purchase_attribution_token", state.purchaseAttributionToken)
            putAll(properties)
            putAll(accountProperties)
        }
        enqueueEventLocked(eventPayload(event, merged))
        if (persistImmediately) persist()
    }

    private fun automaticProperties(): Map<String, Any?> =
        dependencies.platformFacts.properties().toMutableMap().apply {
            remove("\$lib")
            remove("\$lib_version")
            remove("\$session_id")
            remove("\$window_id")
            put("\$platform", "android")
            put("\$device_id", state.anonymousId)
            if (state.purchaseAttributionEnabled) {
                put("\$purchase_attribution_token", state.purchaseAttributionToken)
            }
        }

    private fun setAccountLocked(account: FounderHQAccountContext, emit: Boolean) {
        val groupSet = applyAccountLocked(account) ?: return
        persist()
        if (emit) capture("\$groupidentify", mapOf("\$group_set" to groupSet))
    }

    private fun applyAccountLocked(account: FounderHQAccountContext): Map<String, Any?>? {
        val key = normalizeAccountKey(account.key) ?: return null
        val spanId = if (state.account?.key == key) {
            checkNotNull(state.account).spanId
        } else {
            dependencies.uuid.uuid()
        }
        state.account = AccountState(
            key = key,
            properties = account.properties.toMutableMap(),
            contextToken = account.contextToken?.trim()?.takeIf(String::isNotEmpty),
            spanId = spanId,
        )
        return account.properties
    }

    private fun rotateSession(): Boolean {
        val now = dependencies.clock.nowMillis()
        if (now - state.lastActivityAt >= 30 * 60 * 1000 ||
            now - state.sessionStartedAt >= 24 * 60 * 60 * 1000) {
            state.sessionId = dependencies.uuid.uuidV7(now)
            state.sessionStartedAt = now
            state.lastActivityAt = now
            return true
        }
        return false
    }

    private fun retryIdentify(original: JSONObject, directiveId: String?) {
        val anonymousId = directiveId ?: dependencies.uuid.uuid()
        state.anonymousId = anonymousId
        val event = JSONObject(original.toString())
        event.put("uuid", dependencies.uuid.uuid())
        event.put("timestamp", isoTimestamp(dependencies.clock.nowMillis()))
        event.getJSONObject("properties")
            .put("\$anon_distinct_id", anonymousId)
            .put("\$device_id", anonymousId)
        state.queue = JSONArray(
            listOf(event) + (0 until state.queue.length()).map { state.queue.getJSONObject(it) },
        )
        pruneQueueLocked()
    }

    private fun captureInstallOrUpdate() {
        val version = dependencies.platformFacts.properties()["\$app_version"] as? String ?: return
        if (version.isBlank()) return
        val key = "installed_version"
        val previous = dependencies.storage.getString(key)
        dependencies.storage.putString(key, version)
        if (previous == null) capture("\$application_installed", mapOf("\$app_version" to version))
        else if (previous != version) capture("\$application_updated", mapOf(
            "\$previous_app_version" to previous,
            "\$app_version" to version,
        ))
    }

    private fun persist() { stateWriter.persist(state.toJson().toString()) }

    private fun enqueueEventLocked(event: JSONObject) {
        state.queue.put(event)
        pruneQueueLocked()
    }

    private fun pruneQueueLocked() {
        state.queue = pruneEventQueue(
            state.queue,
            dependencies.clock.nowMillis(),
            config.maxQueueSize,
            config.eventTtlMillis,
        )
    }

    companion object {
        const val SDK_NAME = "com.founderhq:events"
        const val SDK_VERSION = "0.7.0"
        private const val STATE_KEY = "state_v2"
        private val identityKeys = setOf("email", "phone", "externalId", "external_id", "distinct_id")
        // One source of truth: generated from @founderhq/events-core.
        private val allowedReservedEvents =
            FounderHQProtocolConstants.RESERVED_EVENT_NAMES.toSet()
        private val campaignKeys = FounderHQProtocolConstants.CAMPAIGN_PROPERTIES

        private fun isAllowedEvent(event: String) = event.isNotBlank() &&
            event.length <= 200 && (!event.startsWith("\$") || event in allowedReservedEvents)

        private fun defaultDependencies(
            application: Application,
            apiKey: String,
        ): FounderHQEventsDependencies {
            val preferences = application.getSharedPreferences(
                "founderhq_events_v2_${apiKey.take(16)}",
                Context.MODE_PRIVATE,
            )
            return FounderHQEventsDependencies(
                clock = FounderHQClock { System.currentTimeMillis() },
                uuid = SystemUuidProvider,
                storage = SharedPreferencesStorage(preferences),
                transport = HttpTransport,
                platformFacts = AndroidPlatformFacts(application),
            )
        }
    }
}

private data class State(
    var anonymousId: String,
    var distinctId: String,
    var identified: Boolean,
    var purchaseAttributionToken: String,
    var purchaseAttributionEnabled: Boolean,
    var sessionId: String,
    var sessionStartedAt: Long,
    var lastActivityAt: Long,
    val registered: MutableMap<String, Any?>,
    val pendingSet: MutableMap<String, Any?>,
    val pendingSetOnce: MutableMap<String, Any?>,
    var optedOut: Boolean,
    var queue: JSONArray,
    var account: AccountState?,
    val restored: Boolean,
    val needsSessionStart: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("anonymousId", anonymousId)
        .put("distinctId", distinctId)
        .put("identified", identified)
        .put("purchaseAttributionToken", purchaseAttributionToken)
        .put("purchaseAttributionEnabled", purchaseAttributionEnabled)
        .put("sessionId", sessionId)
        .put("sessionStartedAt", sessionStartedAt)
        .put("lastActivityAt", lastActivityAt)
        .put("registered", JSONObject(registered))
        .put("pendingSet", JSONObject(pendingSet))
        .put("pendingSetOnce", JSONObject(pendingSetOnce))
        .put("optedOut", optedOut)
        .put("queue", queue)
        .put("account", account?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fresh(dependencies: FounderHQEventsDependencies, optedOut: Boolean): State {
            val now = dependencies.clock.nowMillis()
            val id = dependencies.uuid.uuid()
            return State(
                id, id, false, id, false, dependencies.uuid.uuidV7(now), now, now,
                mutableMapOf(), mutableMapOf(), mutableMapOf(), optedOut, JSONArray(), null,
                false, true,
            )
        }

        fun restore(
            dependencies: FounderHQEventsDependencies,
            optOutByDefault: Boolean,
            maxQueueSize: Int,
            eventTtlMillis: Long,
        ): State {
            val raw = dependencies.storage.getString("state_v2")
                ?: return fresh(dependencies, optOutByDefault)
            return try {
                val json = JSONObject(raw)
                val restored = State(
                    json.getString("anonymousId"),
                    json.getString("distinctId"),
                    json.getBoolean("identified"),
                    json.optString("purchaseAttributionToken", json.getString("anonymousId")),
                    json.optBoolean("purchaseAttributionEnabled", false),
                    json.getString("sessionId"),
                    json.getLong("sessionStartedAt"),
                    json.getLong("lastActivityAt"),
                    jsonObjectMap(json.getJSONObject("registered")),
                    jsonObjectMap(json.getJSONObject("pendingSet")),
                    jsonObjectMap(json.getJSONObject("pendingSetOnce")),
                    json.getBoolean("optedOut"),
                    json.getJSONArray("queue"),
                    json.optJSONObject("account")?.let(AccountState::fromJson),
                    true,
                    false,
                )
                restored.queue = pruneEventQueue(
                    restored.queue,
                    dependencies.clock.nowMillis(),
                    maxQueueSize,
                    eventTtlMillis,
                )
                dependencies.storage.putString("state_v2", restored.toJson().toString())
                if (isUuidV7(restored.sessionId)) restored else {
                    val now = dependencies.clock.nowMillis()
                    restored.sessionId = dependencies.uuid.uuidV7(now)
                    restored.sessionStartedAt = now
                    restored.lastActivityAt = now
                    dependencies.storage.putString("state_v2", restored.toJson().toString())
                    restored.copy(needsSessionStart = true)
                }
            } catch (_: Exception) {
                fresh(dependencies, optOutByDefault)
            }
        }

        private fun jsonObjectMap(json: JSONObject): MutableMap<String, Any?> =
            json.keys().asSequence().associateWith {
                json.opt(it).takeUnless { value -> value == JSONObject.NULL }
            }.toMutableMap()
    }
}

private data class AccountState(
    val key: String,
    val properties: MutableMap<String, Any?>,
    val contextToken: String?,
    val spanId: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("properties", JSONObject(properties))
        .put("contextToken", contextToken ?: JSONObject.NULL)
        .put("spanId", spanId)

    companion object {
        fun fromJson(json: JSONObject): AccountState? {
            val key = normalizeAccountKey(json.optString("key")) ?: return null
            val spanId = json.optString("spanId").takeIf(String::isNotBlank) ?: return null
            return AccountState(
                key = key,
                properties = json.optJSONObject("properties")
                    ?.let { value -> value.keys().asSequence().associateWith { value.opt(it) }.toMutableMap() }
                    ?: mutableMapOf(),
                contextToken = json.optString("contextToken").takeIf(String::isNotBlank),
                spanId = spanId,
            )
        }
    }
}

private fun controlAccountProperties(account: AccountState?): Map<String, Any?> {
    if (account == null) return emptyMap()
    return buildMap {
        put("\$groups", mapOf("account" to account.key))
        put("\$account_span_id", account.spanId)
        account.contextToken?.let { put("\$account_context_token", it) }
    }
}

private fun claimProperties(
    token: String,
    intent: String,
    purchase: FounderHQObservedPurchase,
): Map<String, Any?> = buildMap {
    put("purchase_context_token", token)
    put("intent", intent)
    put("source", wirePurchaseSource(purchase.source))
    put("store", purchase.store.uppercase(Locale.ROOT))
    purchase.transactionId?.let { put("provider_transaction_id", it) }
    purchase.subscriptionId?.let { put("provider_subscription_id", it) }
    purchase.purchaseToken?.let { put("provider_purchase_token", it) }
    purchase.productId?.let { put("product_id", it) }
    purchase.purchasedAt?.let { put("provider_purchased_at", it) }
    put(
        "confirmation",
        if (intent == "PURCHASE") "new_purchase" else "move_future_revenue",
    )
}

private fun wirePurchaseSource(source: FounderHQPurchaseSource): String = when (source) {
    FounderHQPurchaseSource.REVENUECAT -> "REVENUECAT"
    FounderHQPurchaseSource.APP_STORE -> "STOREKIT"
    FounderHQPurchaseSource.PLAY_BILLING -> "PLAY_BILLING"
}

private val wireTimestampFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
private fun isoTimestamp(nowMillis: Long): String =
    wireTimestampFormatter.format(Instant.ofEpochMilli(nowMillis))

private fun normalizeDistinctId(value: String): String? {
    val distinctId = value.trim()
    return distinctId.takeIf {
        it.length <= 400 &&
            it.lowercase(Locale.ROOT) !in FounderHQProtocolConstants.ILLEGAL_DISTINCT_IDS
    }
}

private fun pruneEventQueue(
    queue: JSONArray,
    nowMillis: Long,
    maxQueueSize: Int,
    eventTtlMillis: Long,
): JSONArray {
    val cutoff = nowMillis - maxOf(0, eventTtlMillis)
    val retained = (0 until queue.length()).mapNotNull { index ->
        val event = queue.optJSONObject(index) ?: return@mapNotNull null
        val createdAt = runCatching {
            Instant.parse(event.optString("timestamp")).toEpochMilli()
        }.getOrNull() ?: return@mapNotNull null
        event.takeIf { createdAt >= cutoff }
    }
    return JSONArray(retained.takeLast(maxOf(1, maxQueueSize)))
}

private class SerializedStateWriter(
    private val storage: FounderHQStorage,
    private val key: String,
) {
    private val lock = Any()
    private var writing = false
    private var pending: String? = null

    fun persist(snapshot: String) {
        val reserved = reserve(snapshot) ?: return
        drain(reserved)
    }

    fun reserve(snapshot: String): String? {
        synchronized(lock) {
            if (writing) {
                pending = snapshot
                return null
            }
            writing = true
            return snapshot
        }
    }

    fun drain(snapshot: String) {
        var next = snapshot
        while (true) {
            storage.putString(key, next)
            synchronized(lock) {
                val queued = pending
                if (queued == null) {
                    writing = false
                    return
                }
                pending = null
                next = queued
            }
        }
    }
}

private fun isUuidV7(value: String): Boolean =
    value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))

private fun normalizeAccountKey(value: String): String? {
    val key = java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFC)
    return key.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 200 }
}

private object SystemUuidProvider : FounderHQUuidProvider {
    override fun uuid(): String = UUID.randomUUID().toString()

    override fun uuidV7(nowMillis: Long): String = founderHqUuidV7(nowMillis)
}

internal fun founderHqUuidV7(nowMillis: Long): String {
    val bytes = ByteBuffer.allocate(16)
        .putLong(UUID.randomUUID().mostSignificantBits)
        .putLong(UUID.randomUUID().leastSignificantBits)
        .array()
    var timestamp = nowMillis.coerceAtLeast(0)
    for (index in 5 downTo 0) {
        bytes[index] = (timestamp and 0xff).toByte()
        timestamp = timestamp ushr 8
    }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private class SharedPreferencesStorage(
    private val preferences: SharedPreferences,
) : FounderHQStorage {
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String?) {
        preferences.edit().putString(key, value).apply()
    }
}

private object HttpTransport : FounderHQTransport {
    override fun send(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): FounderHQTransportResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.doOutput = true
        headers.forEach(connection::setRequestProperty)
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return FounderHQTransportResponse(
            status,
            stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
        )
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
    ): FounderHQTransportResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 1_500
        connection.readTimeout = 1_500
        headers.forEach(connection::setRequestProperty)
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return FounderHQTransportResponse(
            status,
            stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
        )
    }
}

private class AndroidPlatformFacts(
    private val application: Application,
) : FounderHQPlatformFactsProvider {
    override fun properties(): Map<String, Any?> {
        val metrics = application.resources.displayMetrics
        val locale = if (android.os.Build.VERSION.SDK_INT >= 24) {
            LocaleList.getDefault()[0]
        } else Locale.getDefault()
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        @Suppress("DEPRECATION")
        val build = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else packageInfo.versionCode.toLong()
        return mapOf(
            "\$locale" to locale.toLanguageTag(),
            "\$timezone" to TimeZone.getDefault().id,
            "\$device_manufacturer" to android.os.Build.MANUFACTURER,
            "\$device_name" to android.os.Build.MODEL,
            "\$device_model" to android.os.Build.MODEL,
            "\$device_type" to "Mobile",
            "\$os" to "Android",
            "\$os_version" to android.os.Build.VERSION.RELEASE,
            "\$screen_width" to metrics.widthPixels,
            "\$screen_height" to metrics.heightPixels,
            "\$viewport_width" to metrics.widthPixels,
            "\$viewport_height" to metrics.heightPixels,
            "\$app_name" to application.applicationInfo.loadLabel(application.packageManager).toString(),
            "\$app_namespace" to application.packageName,
            "\$app_version" to packageInfo.versionName,
            "\$app_build" to build.toString(),
        ).filterValues { it != null && it != "" }
    }
}
