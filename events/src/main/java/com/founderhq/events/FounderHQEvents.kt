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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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
    val host: String = "https://i.getfounderhq.com",
    val flushAt: Int = 20,
    /**
     * Seconds between background flushes. Ten seconds keeps a phone's radio
     * asleep for longer than five did, and no product decision depends on an
     * event arriving five seconds sooner.
     */
    val flushIntervalSeconds: Long = 10,
    val personProfiles: PersonProfiles = PersonProfiles.IDENTIFIED_ONLY,
    val optOutByDefault: Boolean = false,
    val captureLifecycle: Boolean = true,
    val captureScreens: Boolean = true,
    val captureSessions: Boolean = true,
    val captureInstallUpdates: Boolean = true,
    val remoteConfig: Boolean = true,
    /**
     * Captures `$autocapture` when the user taps a button or another control,
     * and `$rageclick` when they tap the same control again and again. It is
     * off by default because it reports on screens the app author never
     * annotated, which is a decision they should make on purpose.
     *
     * Semantic facts are stored: the view class, the resource entry name, the
     * content description, and the view hierarchy path. A control's own label
     * is stored with them — a `Button` and its subclasses, and the selected tab
     * of a Material `TabLayout` — scrubbed of card- and SSN-like tokens and
     * truncated. A plain `TextView`, an `EditText`'s contents, an `EditText`'s
     * hint, and touch coordinates are never read into an event.
     *
     * This flag is the default, not the ceiling. The remote `autocapture`
     * setting wins in both directions once it arrives, so an operator can
     * switch capture on for an app that shipped with this false, and off for
     * one that shipped with it true. Rage clicks follow `capture_rageclicks`.
     */
    val captureElementInteractions: Boolean = false,
    /**
     * Emits `$push_notification_opened` when the app reports a notification
     * tap through [FounderHQEvents.capturePushNotificationOpened]. The SDK
     * cannot observe taps by itself on Android, so this only gates the
     * method the app calls.
     */
    val capturePushNotificationOpened: Boolean = true,
    /**
     * Exact hostnames whose outgoing requests carry the
     * `x-founderhq-session-id` header through [FounderHQOkHttpInterceptor], so
     * a backend event can be joined to the visit that caused it. Entries are
     * hostnames only: no protocol, path, port, or wildcard. Every host you
     * list can read the session id, so list your own APIs and nothing else.
     */
    val tracingHeaders: List<String>? = null,
    val account: FounderHQAccountContext? = null,
    val purchasePrepareTimeoutMillis: Long = 3_000,
    /**
     * Events held on disk while delivery fails. A thousand covers a long
     * offline stretch and still costs well under a megabyte of storage; the
     * old limit of a hundred threw away a busy session in a few minutes.
     */
    val maxQueueSize: Int = 1000,
    val eventTtlMillis: Long = 24 * 60 * 60 * 1_000,
    /**
     * Retries an event may spend before the SDK drops it, on top of the first
     * send. Five walks the whole ladder in [FOUNDERHQ_RETRY_LADDER_MILLIS]:
     * 30s, 30s, 2min, 5min, and then one last try the next time the app
     * starts. A lower number truncates the ladder from the front, so 2 means
     * 30s, 30s and then give up.
     */
    val maxRetries: Int = 5,
    /**
     * Logs what the SDK dropped and what it refused to configure. Off by
     * default so a release build stays silent in logcat.
     */
    val debug: Boolean = false,
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
    // Touch dispatch reads these on the main thread; remote config writes them
    // on the SDK's own executor.
    @Volatile private var captureElementInteractionsEnabled = config.captureElementInteractions
    @Volatile private var captureRageClicksEnabled = config.captureElementInteractions
    private var callbacksRegistered = false
    private val tracingHosts = founderHqNormalizeTracingHosts(config.tracingHeaders) { entry ->
        debugLog("tracingHeaders ignored \"$entry\": list exact hostnames only")
    }
    private val ingestHost = founderHqHostOf(config.host)
    /** Windows whose callback this SDK wrapped, so [close] can hand them back. */
    /**
     * The activity on screen, so remote config that switches capture on can
     * wrap its window now instead of waiting for the next screen. Weak, so a
     * finished activity is never held alive by this SDK.
     */
    private var resumedActivity: java.lang.ref.WeakReference<Activity>? = null
    private val wrappedWindows: MutableMap<Window, Window.Callback> =
        Collections.synchronizedMap(WeakHashMap())
    // Touch dispatch is single threaded, so rage detection needs no lock.
    private var rageTargetKey: String? = null
    private val rageTouchTimes = mutableListOf<Long>()
    private var rageEmittedAt = 0L
    // The gesture in progress, so a scroll is not mistaken for a tap.
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchIsATap = false

    init {
        // A new Activity is not a new process; only the first client built in
        // this process spends the ladder's last rung.
        if (FounderHQProcessLaunch.claimFirstInitialization()) {
            synchronized(lock) { grantNextLaunchRetriesLocked() }
        }
        applyCachedRemoteConfig()
        if (config.flushIntervalSeconds > 0) {
            executor.scheduleWithFixedDelay(
                { flushOnSchedule() },
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
            if (state.queue.length() >= config.flushAt) executor.execute { flushOnSchedule() }
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
    fun optOut() = withInitializedState {
        state.optedOut = true
        state.queue = JSONArray()
        state.deliveryAttempts.clear()
        state.retryEligibleAt.clear()
        persist()
    }
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
        val elementsWereEnabled = captureElementInteractionsEnabled
        applyRemoteElementCaptureLocked(remote)
        updateLifecycleRegistration()
        if (!elementsWereEnabled && captureElementInteractionsEnabled) {
            armElementInteractionsOnResumedActivity()
        }
        val disabled = buildSet {
            if (!captureSessionsEnabled) add("\$session_start")
            if (!captureScreensEnabled) add("\$screen")
            if (!captureLifecycleEnabled) {
                add("\$application_opened")
                add("\$application_backgrounded")
            }
            if (!captureElementInteractionsEnabled) add("\$autocapture")
            if (!captureRageClicksEnabled) add("\$rageclick")
        }
        if (disabled.isNotEmpty()) {
            state.queue = JSONArray(
                (0 until state.queue.length())
                    .map { state.queue.getJSONObject(it) }
                    .filterNot { it.optString("event") in disabled },
            )
            pruneDeliveryAttemptsLocked()
            persist()
        }
    }

    /**
     * Applies the operator's `autocapture` and `capture_rageclicks` settings to
     * element capture.
     *
     * The operator's setting wins, in both directions, exactly as it does for
     * `capture_screens` and `capture_lifecycle`. An app can ship the wrong
     * default and cannot be rebuilt on demand, so the dashboard must be able
     * to switch capture both off and on. [FounderHQEventsConfig.captureElementInteractions]
     * is the default that holds until the operator's settings arrive.
     *
     * Either direction takes effect at once: switching off hands every wrapped
     * window straight back, and switching on wraps the window on screen.
     *
     * The web SDK also accepts an object of URL and CSS-selector rules under
     * `autocapture`. Those describe a document, not a view hierarchy, so a
     * non-boolean value means only "not switched off" here.
     */
    private fun applyRemoteElementCaptureLocked(remote: Map<String, Any?>) {
        val wasEnabled = captureElementInteractionsEnabled
        captureElementInteractionsEnabled =
            remote["autocapture"] as? Boolean ?: config.captureElementInteractions
        captureRageClicksEnabled = captureElementInteractionsEnabled &&
            (remote["capture_rageclicks"] as? Boolean ?: true)
        if (wasEnabled && !captureElementInteractionsEnabled) releaseWrappedWindows()
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

    /**
     * Records that the user opened the app from a notification. Android hands
     * the tap to the app, not to this SDK, so call this from the activity or
     * receiver that handles the notification intent. Pass only the campaign
     * facts you want in analytics; the notification payload may hold message
     * content, and the SDK sends whatever it is given.
     */
    fun capturePushNotificationOpened(properties: Map<String, Any?> = emptyMap()) {
        if (!config.capturePushNotificationOpened) return
        capture("\$push_notification_opened", properties)
    }

    /**
     * Returns the current session id when [host] is one the app listed in
     * `tracingHeaders`, and null otherwise. [FounderHQOkHttpInterceptor] calls
     * this; apps on another HTTP client can call it too. It never blocks on
     * SDK start-up, because no header is worth delaying the app's request.
     */
    fun tracingSessionId(host: String?): String? {
        if (tracingHosts.isEmpty()) return null
        val target = host?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        if (target !in tracingHosts) return null
        // Our own ingest already carries the session in the request body.
        if (target == ingestHost) return null
        if (initialized.count > 0L) return null
        return try {
            if (isOptedOut()) null else getSessionId()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sends the queue now. An app calling this by hand is asking for a send at
     * this moment, so a waiting event on the retry ladder is sent anyway; the
     * ladder still governs every flush the SDK schedules for itself.
     */
    fun flush(): Boolean = flushInternal(respectRetryLadder = false)

    /**
     * The flush the SDK runs on its own: the interval timer, the flushAt
     * threshold, and going to the background. It skips events whose retry wait
     * has not elapsed.
     */
    internal fun flushOnSchedule(): Boolean = flushInternal(respectRetryLadder = true)

    private fun flushInternal(respectRetryLadder: Boolean): Boolean {
        awaitInitialization()
        // Serialize flushes: overlapping timer, lifecycle, and manual callers
        // run one at a time, so two flushes can never send the same queue
        // head or rotate identity twice. The follower flushes whatever
        // remains, which the server deduplicates by event UUID.
        synchronized(flushGate) {
            return flushOnce(respectRetryLadder)
        }
    }

    private val flushGate = Any()

    private fun flushOnce(respectRetryLadder: Boolean): Boolean {
        val selected: List<JSONObject>
        synchronized(lock) {
            pruneQueueLocked()
            persist()
            if (state.optedOut || state.queue.length() == 0) return true
            // The ingest endpoint rejects batches over 100 items; an
            // unclamped flushAt above that would retry the same oversized
            // batch forever.
            val flushAt = minOf(100, maxOf(1, config.flushAt))
            // One waiting event never blocks the batch: the eligible events
            // behind it go now, and the waiting one keeps its place in the
            // queue for its own rung.
            val now = dependencies.clock.nowMillis()
            selected = (0 until state.queue.length())
                .map { state.queue.getJSONObject(it) }
                .filter { !respectRetryLadder || isRetryEligibleLocked(it, now) }
                .take(flushAt)
            if (selected.isEmpty()) return true
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
            if (response.statusCode !in 200..299) {
                recordDeliveryFailure(selected)
                return false
            }
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
                pruneDeliveryAttemptsLocked()
                persist()
            }
            if (hasRetry) recordDeliveryFailure(selected)
            !hasRetry
        } catch (_: Exception) {
            recordDeliveryFailure(selected)
            false
        }
    }

    /** An event with no recorded wait, or one whose wait has elapsed. */
    private fun isRetryEligibleLocked(event: JSONObject, nowMillis: Long): Boolean {
        val uuid = event.optString("uuid").takeIf(String::isNotBlank) ?: return true
        val eligibleAt = state.retryEligibleAt[uuid] ?: return true
        return eligibleAt <= nowMillis
    }

    /**
     * Spends the final rung of the ladder. Events parked on
     * [FOUNDERHQ_RETRY_NEXT_LAUNCH] become eligible again, once, because this
     * is a new process: the device may have moved, rebooted, or changed
     * network since the last try.
     */
    private fun grantNextLaunchRetriesLocked() {
        val parked = state.retryEligibleAt.filterValues { it == FOUNDERHQ_RETRY_NEXT_LAUNCH }.keys
        if (parked.isEmpty()) return
        state.retryEligibleAt.keys.removeAll(parked)
        persist()
        debugLog("new process: ${parked.size} event(s) earned their last delivery attempt")
    }

    /**
     * Counts one lost delivery attempt against every event that stayed queued,
     * puts the survivors on the next rung of [FOUNDERHQ_RETRY_LADDER_MILLIS],
     * and drops the events that ran out of attempts. The events-node client
     * bounds a batch the same way: one first send plus `maxRetries` retries.
     */
    private fun recordDeliveryFailure(selected: List<JSONObject>) {
        val maxRetries = maxOf(0, config.maxRetries)
        val now = dependencies.clock.nowMillis()
        synchronized(lock) {
            val queued = (0 until state.queue.length()).map { state.queue.getJSONObject(it) }
            val queuedIds = queued.mapNotNull { it.optString("uuid").takeIf(String::isNotBlank) }
                .toSet()
            val exhausted = mutableSetOf<String>()
            for (event in selected) {
                val uuid = event.optString("uuid").takeIf(String::isNotBlank) ?: continue
                if (uuid !in queuedIds) {
                    state.deliveryAttempts.remove(uuid)
                    state.retryEligibleAt.remove(uuid)
                    continue
                }
                val attempts = (state.deliveryAttempts[uuid] ?: 0) + 1
                if (attempts > maxRetries) {
                    exhausted += uuid
                    state.deliveryAttempts.remove(uuid)
                    state.retryEligibleAt.remove(uuid)
                } else {
                    state.deliveryAttempts[uuid] = attempts
                    state.retryEligibleAt[uuid] =
                        founderHqRetryEligibleAt(attempts, maxRetries, now)
                }
            }
            if (exhausted.isNotEmpty()) {
                state.queue = JSONArray(
                    queued.filterNot { it.optString("uuid") in exhausted },
                )
                debugLog(
                    "dropped ${exhausted.size} event(s) after ${maxRetries + 1} delivery attempts",
                )
            }
            pruneDeliveryAttemptsLocked()
            persist()
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
        releaseWrappedWindows()
    }

    /** Hands every window this SDK wrapped back to the app that owns it. */
    private fun releaseWrappedWindows() {
        val windows = synchronized(wrappedWindows) { wrappedWindows.keys.toList() }
        wrappedWindows.clear()
        // A window callback belongs to the main thread that dispatches into it.
        if (windows.isNotEmpty()) {
            Handler(Looper.getMainLooper()).post { windows.forEach(::restoreWindowCallback) }
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
        resumedActivity = java.lang.ref.WeakReference(activity)
        attachElementInteractions(activity)
        if (captureScreensEnabled) {
            screen(activity.title?.toString()?.takeIf { it.isNotBlank() } ?: activity.javaClass.simpleName)
        }
    }
    override fun onActivityStopped(activity: Activity) {
        foregroundActivities = maxOf(0, foregroundActivities - 1)
        if (foregroundActivities == 0 && captureLifecycleEnabled) {
            recordLifecycle("background")
            executor.execute { flushOnSchedule() }
        }
    }
    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) resumedActivity = null
        detachElementInteractions(activity)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity?.get() === activity) resumedActivity = null
        detachElementInteractions(activity)
    }

    /**
     * Wraps the window of the activity on screen, for the moment remote config
     * switches capture on while the user is already looking at a screen.
     */
    private fun armElementInteractionsOnResumedActivity() {
        val activity = resumedActivity?.get() ?: return
        // A window callback belongs to the main thread that dispatches into it.
        Handler(Looper.getMainLooper()).post { attachElementInteractions(activity) }
    }

    internal fun persistedState(): JSONObject = synchronized(lock) { state.toJson() }

    /** The element-capture gate as it stands after remote config, for tests. */
    internal fun elementCaptureEnabled(): Boolean = captureElementInteractionsEnabled

    /** The rage-click gate as it stands after remote config, for tests. */
    internal fun rageClickCaptureEnabled(): Boolean = captureRageClicksEnabled

    fun readyForCapture() { awaitInitialization() }

    private fun updateLifecycleRegistration() {
        val shouldRegister = captureLifecycleEnabled || captureScreensEnabled ||
            captureElementInteractionsEnabled
        if (shouldRegister && !callbacksRegistered) {
            application.registerActivityLifecycleCallbacks(this)
            callbacksRegistered = true
        } else if (!shouldRegister && callbacksRegistered) {
            application.unregisterActivityLifecycleCallbacks(this)
            callbacksRegistered = false
        }
    }

    /**
     * Watches touches by wrapping the window callback, which is the only hook
     * Android offers for taps the app itself handles. The wrapper forwards
     * every call, so the app sees no change in behaviour.
     */
    private fun attachElementInteractions(activity: Activity) {
        if (!captureElementInteractionsEnabled) return
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is FounderHQWindowCallback) return
        window.callback = FounderHQWindowCallback(current, window, ::observeTouch)
        wrappedWindows[window] = current
    }

    private fun detachElementInteractions(activity: Activity) {
        val window = activity.window ?: return
        restoreWindowCallback(window)
        wrappedWindows.remove(window)
    }

    private fun restoreWindowCallback(window: Window) {
        // Another library may have wrapped ours since; unwrapping then would
        // silently disable it, so only the outermost wrapper is removed.
        val current = window.callback
        if (current is FounderHQWindowCallback) window.callback = current.delegate
    }

    /**
     * Decides whether the gesture that just ended was a tap.
     *
     * A user who scrolls a list lifts their finger over whatever row happens
     * to be under it, and that is not an interaction with the row. So the
     * whole gesture is watched: a finger that travels further than the
     * platform's touch slop, a second finger, or a cancelled stream all mean
     * the gesture was something other than a tap. A stream that starts while
     * the SDK is attaching has no beginning, and is not a tap either.
     */
    private fun observeTouch(window: Window, event: MotionEvent) {
        // Remote config can switch capture off while a wrapped window is still
        // dispatching; the wrapper stays, and stops reporting.
        if (!captureElementInteractionsEnabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchIsATap = true
                return
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchIsATap && movedBeyondSlop(window, event)) touchIsATap = false
                return
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                touchIsATap = false
                return
            }
            MotionEvent.ACTION_UP -> Unit
            else -> return
        }
        val wasATap = touchIsATap
        touchIsATap = false
        if (!wasATap || movedBeyondSlop(window, event)) return
        val root = window.peekDecorView() ?: return
        // Coordinates are used to find the view and are then discarded. They
        // are never read into an event.
        val touched = founderHqTouchedView(root, event.x, event.y) ?: return
        if (founderHqTouchedEnteredValue(touched)) return
        val target = founderHqInteractiveTarget(touched) ?: return
        if (founderHqCaptureBlocked(target)) return
        val properties = mapOf(
            "\$event_type" to "touch",
            "\$elements" to founderHqElementMetadata(target),
            "\$element_path" to founderHqElementPath(target),
        )
        // Capture takes the state lock and may flush; the touch dispatch that
        // called us belongs to the frame the user is looking at.
        executor.execute { capture("\$autocapture", properties) }
        if (captureRageClicksEnabled &&
            rageTouchDetected(founderHqTargetKey(target), dependencies.clock.nowMillis())
        ) {
            executor.execute { capture("\$rageclick", properties) }
        }
    }

    /** True once the finger has travelled further than a tap ever does. */
    private fun movedBeyondSlop(window: Window, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(window.context).scaledTouchSlop
        return abs(event.x - touchDownX) > slop || abs(event.y - touchDownY) > slop
    }

    /**
     * Reports the third tap on one target inside a second, and then stays
     * quiet for a second so one frustrated burst is one event.
     */
    private fun rageTouchDetected(key: String, now: Long): Boolean {
        if (key != rageTargetKey) {
            rageTargetKey = key
            rageTouchTimes.clear()
        }
        while (rageTouchTimes.isNotEmpty() && now - rageTouchTimes.first() > RAGE_WINDOW_MILLIS) {
            rageTouchTimes.removeAt(0)
        }
        rageTouchTimes.add(now)
        if (rageTouchTimes.size < RAGE_TOUCH_COUNT) return false
        if (now - rageEmittedAt <= RAGE_WINDOW_MILLIS) return false
        rageEmittedAt = now
        return true
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
        pruneDeliveryAttemptsLocked()
    }

    /**
     * Attempt counters and retry waits only describe queued events; once an
     * event is delivered or dropped, its ladder position is dead weight.
     */
    private fun pruneDeliveryAttemptsLocked() {
        if (state.deliveryAttempts.isEmpty() && state.retryEligibleAt.isEmpty()) return
        val queuedIds = (0 until state.queue.length())
            .mapNotNull { state.queue.optJSONObject(it)?.optString("uuid") }
            .toSet()
        state.deliveryAttempts.keys.retainAll(queuedIds)
        state.retryEligibleAt.keys.retainAll(queuedIds)
    }

    private fun debugLog(message: String) {
        if (config.debug) Log.d(SDK_NAME, message)
    }

    companion object {
        const val SDK_NAME = "com.founderhq:events"
        const val SDK_VERSION = "1.0.0"
        private const val STATE_KEY = "state_v2"
        private const val RAGE_WINDOW_MILLIS = FounderHQProtocolConstants.RAGE_WINDOW_MILLIS
        private const val RAGE_TOUCH_COUNT = FounderHQProtocolConstants.RAGE_TAP_COUNT
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
    /** Lost delivery attempts per queued event UUID. Kept beside the queue so
     * the wire payload stays exactly what the server expects. */
    val deliveryAttempts: MutableMap<String, Int>,
    /**
     * When each waiting event may be sent again, as a wall clock millisecond.
     * [FOUNDERHQ_RETRY_NEXT_LAUNCH] means the event waits for a new process
     * instead. Persisted beside the queue so the retry ladder survives the
     * process dying, and kept out of the event itself so the wire payload
     * stays exactly what the server expects.
     */
    val retryEligibleAt: MutableMap<String, Long>,
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
        .put("deliveryAttempts", JSONObject(deliveryAttempts.toMap()))
        .put("retryEligibleAt", JSONObject(retryEligibleAt.toMap()))

    companion object {
        fun fresh(dependencies: FounderHQEventsDependencies, optedOut: Boolean): State {
            val now = dependencies.clock.nowMillis()
            val id = dependencies.uuid.uuid()
            return State(
                id, id, false, id, false, dependencies.uuid.uuidV7(now), now, now,
                mutableMapOf(), mutableMapOf(), mutableMapOf(), optedOut, JSONArray(), null,
                mutableMapOf(), mutableMapOf(), false, true,
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
                    json.optJSONObject("deliveryAttempts")?.let(::jsonIntMap) ?: mutableMapOf(),
                    json.optJSONObject("retryEligibleAt")?.let(::jsonLongMap) ?: mutableMapOf(),
                    true,
                    false,
                )
                restored.queue = pruneEventQueue(
                    restored.queue,
                    dependencies.clock.nowMillis(),
                    maxQueueSize,
                    eventTtlMillis,
                )
                val queuedIds = (0 until restored.queue.length())
                    .mapNotNull { restored.queue.optJSONObject(it)?.optString("uuid") }
                    .toSet()
                restored.deliveryAttempts.keys.retainAll(queuedIds)
                restored.retryEligibleAt.keys.retainAll(queuedIds)
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

        private fun jsonLongMap(json: JSONObject): MutableMap<String, Long> =
            json.keys().asSequence()
                .mapNotNull { key -> json.optLong(key, 0L).takeIf { it > 0L }?.let { key to it } }
                .toMap()
                .toMutableMap()

        private fun jsonIntMap(json: JSONObject): MutableMap<String, Int> =
            json.keys().asSequence()
                .mapNotNull { key -> json.optInt(key, 0).takeIf { it > 0 }?.let { key to it } }
                .toMap()
                .toMutableMap()

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

// java.time requires API 26; the SDK supports Android API 24 and 25 too.
private val wireTimestampFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
    isLenient = false
}
private val wireTimestampPattern = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?Z$")
internal fun isoTimestamp(nowMillis: Long): String = synchronized(wireTimestampFormatter) {
    wireTimestampFormatter.format(Date(nowMillis))
}

internal fun parseWireTimestamp(value: String): Long? {
    val match = wireTimestampPattern.matchEntire(value) ?: return null
    // Older SDKs emitted whole seconds or variable fractional precision.
    val normalized = "${match.groupValues[1]}.${match.groupValues[2].take(3).padEnd(3, '0')}Z"
    return synchronized(wireTimestampFormatter) {
        val position = ParsePosition(0)
        wireTimestampFormatter.parse(normalized, position)
            ?.takeIf { position.index == normalized.length }?.time
    }
}

private fun normalizeDistinctId(value: String): String? {
    val distinctId = value.trim()
    return distinctId.takeIf {
        it.length <= 400 &&
            it.lowercase(Locale.ROOT) !in FounderHQProtocolConstants.ILLEGAL_DISTINCT_IDS
    }
}

/**
 * The waits between delivery attempts, in order: 30s, 30s, 2min, 5min. After
 * the last one the event waits for [FOUNDERHQ_RETRY_NEXT_LAUNCH] instead of a
 * clock.
 *
 * The ladder exists because the two reasons a send fails need opposite
 * answers. A phone in a lift, in a tunnel, or on a train is fine and only
 * needs a few minutes: the short first rungs catch it, and the app usually
 * never notices the outage. An event the server will never accept is broken
 * for good, and retrying it forever wastes the radio, holds a queue slot, and
 * costs the user battery. So the waits grow, the count is bounded, and the
 * event dies after the last rung.
 *
 * The last rung is the next app launch rather than a longer wait. A failure
 * that survives five minutes is usually not about the network any more: the
 * device may have been rebooted, the app killed, or the connection changed.
 * A fresh process is the moment those conditions have most likely changed, so
 * the SDK spends the final attempt there. It also rescues a queue from a
 * process that died before it could retry.
 *
 * The 24 hour TTL still wins. An event older than [FounderHQEventsConfig.eventTtlMillis]
 * is dropped when the queue is pruned, whether or not it has attempts left.
 */
internal val FOUNDERHQ_RETRY_LADDER_MILLIS = longArrayOf(30_000L, 30_000L, 120_000L, 300_000L)

/**
 * Eligibility marker for the final rung: no clock makes this event eligible,
 * only the SDK starting up in a new process.
 */
internal const val FOUNDERHQ_RETRY_NEXT_LAUNCH = Long.MAX_VALUE

/**
 * The time an event becomes eligible again after [attempts] failed deliveries.
 *
 * [attempts] counts the sends already lost, so the first failure earns the
 * first rung. A [maxRetries] below the ladder length truncates it from the
 * front and never reaches the next launch rung. A [maxRetries] above it
 * repeats the last wait, and the next launch rung stays last.
 */
internal fun founderHqRetryEligibleAt(
    attempts: Int,
    maxRetries: Int,
    nowMillis: Long,
): Long {
    val ladder = FOUNDERHQ_RETRY_LADDER_MILLIS
    if (maxRetries > ladder.size && attempts >= maxRetries) return FOUNDERHQ_RETRY_NEXT_LAUNCH
    return nowMillis + ladder[minOf(maxOf(attempts - 1, 0), ladder.size - 1)]
}

/**
 * One shot per operating system process. The first [FounderHQEvents] built in
 * a process claims it, which is how the SDK tells a cold start from a new
 * Activity: a rotated or restarted Activity reuses the process, and this
 * static stays claimed.
 */
internal object FounderHQProcessLaunch {
    private val claimed = AtomicBoolean(false)

    fun claimFirstInitialization(): Boolean = claimed.compareAndSet(false, true)

    /** Lets a test stand in for the operating system starting a new process. */
    internal fun resetForTests() = claimed.set(false)
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
        val createdAt = parseWireTimestamp(event.optString("timestamp")) ?: return@mapNotNull null
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
        val locale = LocaleList.getDefault()[0]
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
