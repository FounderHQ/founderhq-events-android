package com.founderhq.events

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
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
import java.util.GregorianCalendar
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

/** The service that delivers to a push token. */
enum class FounderHQPushProvider(val wireValue: String) {
    FCM("fcm"), APNS("apns"), EXPO("expo")
}

/** The APNs environment of a token. Only for [FounderHQPushProvider.APNS]. */
enum class FounderHQPushEnvironment(val wireValue: String) {
    SANDBOX("sandbox"), PRODUCTION("production")
}

/** The notification permission the app last saw. The SDK never asks for it. */
enum class FounderHQPushPermission(val wireValue: String) {
    AUTHORIZED("authorized"), DENIED("denied"), PROVISIONAL("provisional"),
    NOT_DETERMINED("not_determined")
}

/**
 * The two FounderHQ keys of a notification's data. Nothing else in the data
 * or the intent extras is read.
 */
data class FounderHQPushPayload(
    /** `fhqOutboundMessageId`: the message this notification belongs to. */
    val messageId: String?,
    /** `fhqLink`: the URL or deep link to open. The SDK never opens it. */
    val link: String?,
    /**
     * The name of the notification: `fhqNotificationKey` when the sender set
     * one, or else the message id. Android shows a FounderHQ push with this
     * as its tag. An app that shows the notification itself (a push that
     * arrives while it is open) posts it with the same tag, so a later push
     * replaces it and [FounderHQEvents.dismissPushNotification] finds it.
     */
    val notificationKey: String? = null,
) {
    companion object {
        /** From `RemoteMessage.getData()`. */
        @JvmStatic
        fun from(data: Map<String, *>?): FounderHQPushPayload {
            val messageId = text(data?.get(FounderHQProtocolConstants.PUSH_PAYLOAD_MESSAGE_ID_KEY))
            return FounderHQPushPayload(
                messageId = messageId,
                link = text(data?.get(FounderHQProtocolConstants.PUSH_PAYLOAD_LINK_KEY)),
                notificationKey =
                    text(data?.get(FounderHQProtocolConstants.PUSH_PAYLOAD_NOTIFICATION_KEY))
                        ?: messageId,
            )
        }

        /**
         * From the extras of the intent a notification tap starts. Extras
         * that cannot be read give an empty payload: an intent from another
         * app can hold a `Parcelable` this app does not know, and Android
         * throws for the whole bundle then. This never throws.
         */
        @JvmStatic
        fun from(extras: Bundle?): FounderHQPushPayload = try {
            val messageId =
                text(extras?.getString(FounderHQProtocolConstants.PUSH_PAYLOAD_MESSAGE_ID_KEY))
            FounderHQPushPayload(
                messageId = messageId,
                link = text(extras?.getString(FounderHQProtocolConstants.PUSH_PAYLOAD_LINK_KEY)),
                notificationKey =
                    text(extras?.getString(FounderHQProtocolConstants.PUSH_PAYLOAD_NOTIFICATION_KEY))
                        ?: messageId,
            )
        } catch (_: Throwable) {
            EMPTY
        }

        /** The same, for an intent. This never throws. */
        @JvmStatic
        fun from(intent: Intent?): FounderHQPushPayload = try {
            from(intent?.extras)
        } catch (_: Throwable) {
            EMPTY
        }

        private val EMPTY = FounderHQPushPayload(messageId = null, link = null)

        private fun text(value: Any?): String? =
            (value as? String)?.trim()?.takeIf(String::isNotEmpty)
    }
}

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
    /** Adds properties to screen views and captured taps, never to custom or lifecycle events. */
    val autoProperties: ((FounderHQAutoPropertiesContext) -> Map<String, Any?>?)? = null,
    /**
     * Runs under the SDK lock on the capturing thread (SDK executor for taps),
     * except for purchase and push device controls. Null or a throw drops the event. Must be fast,
     * non-blocking, and must not call the SDK. Identity retries run it again.
     */
    val beforeSend: ((JSONObject) -> JSONObject?)? = null,
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
    // Written under [flushGate]. A closed client sends and stores nothing more.
    @Volatile private var closed = false
    @Volatile private var initializationThread: Thread? = null
    private val lock = Any()
    private val stateWriter = SerializedStateWriter(dependencies.storage, STATE_KEY)
    private var state = State.restore(
        dependencies,
        config.optOutByDefault,
        config.maxQueueSize,
        config.eventTtlMillis,
    )
    // UI capture gates must not wait for a beforeSend or persistence holding the lock.
    @Volatile private var optedOutSnapshot = state.optedOut
    // Lets a caller decide, without the lock, whether the notification
    // permission is worth reading before it takes the lock.
    @Volatile private var pushTokenStoredSnapshot = state.push?.token != null
    // The last registration this process queued. Guarded by [lock].
    private var lastPushRegistration: SentPushRegistration? = null
    private var foregroundActivities = 0
    private var captureLifecycleEnabled = config.captureLifecycle
    @Volatile private var captureScreensEnabled = config.captureScreens
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
    @Volatile private var currentScreenName: String? = null
    private val autoPropertiesWarned = AtomicBoolean(false)

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
                // Before anything else this start queues: a removal that never
                // reached the server goes out first.
                synchronized(lock) { requeuePendingPushRemovalsLocked() }
                // A binder call, so it is made before the lock is taken.
                val pushPermission = systemPushPermissionIfNeeded()
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
                    // Every start registers the stored token again for an
                    // identified person: it is what keeps "the last used
                    // device" true on the server.
                    registerStoredPushDeviceLocked(pushPermission)
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
        val pushPermission = systemPushPermissionIfNeeded()
        synchronized(lock) {
            if (state.optedOut) {
                // The identify is not recorded, but the person is signed in
                // again: a removal that still waits must not take the device.
                state.push?.let { dropPushRemovalsLocked(it.token, it.provider, id) }
                return
            }
            val previousDistinctId = state.distinctId
            val wasIdentified = state.identified
            val accountSwitch = state.identified && state.distinctId != id
            if (accountSwitch) {
                val guestId = dependencies.uuid.uuid()
                state.anonymousId = guestId
                state.purchaseAttributionToken = guestId
                state.account = null
                // The push switch belonged to the person before, as in reset().
                state.push?.takeIf { it.enabled != null }
                    ?.let { setPushLocked(it.copy(enabled = null)) }
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
            // After `$identify`, so the server already knows who this person
            // is. A registration for another person moves the device to them.
            // The same person again changes nothing, so nothing is sent.
            if (!wasIdentified || previousDistinctId != id) {
                registerStoredPushDeviceLocked(pushPermission)
            }
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
        currentScreenName = name
        if (!captureScreensEnabled || optedOutSnapshot) return
        // App code runs on the screen caller's thread, before taking the SDK lock.
        val extra = autoProperties(FounderHQAutoPropertiesContext("\$screen", name))
        synchronized(lock) {
            if (!captureScreensEnabled || state.optedOut) return
            val automatic = mutableMapOf<String, Any?>(
                "\$screen_name" to name,
                "\$screen_id" to dependencies.uuid.uuid(),
            )
            founderHqColorScheme(resumedActivity?.get() ?: application)?.let {
                automatic["\$prefers_color_scheme"] = it
            }
            capture("\$screen", automatic + extra + properties)
        }
    }

    private fun autoProperties(
        context: FounderHQAutoPropertiesContext,
        declarative: List<Pair<String, Any?>> = emptyList(),
    ): Map<String, Any> {
        val callback = try {
            config.autoProperties?.invoke(context)?.toList().orEmpty()
        } catch (_: Throwable) {
            if (autoPropertiesWarned.compareAndSet(false, true)) {
                Log.w(SDK_NAME, "autoProperties threw; capturing the event without its callback properties")
            }
            emptyList()
        }
        return founderHqSanitizeAutoProperties(declarative + callback)
    }

    fun register(properties: Map<String, Any?>) = withInitializedState {
        state.registered.putAll(properties); persist()
    }
    fun registerOnce(properties: Map<String, Any?>) = withInitializedState {
        properties.forEach { (key, value) -> state.registered.putIfAbsent(key, value) }; persist()
    }
    fun unregister(key: String) = withInitializedState { state.registered.remove(key); persist() }
    fun optIn() {
        val pushPermission = systemPushPermissionIfNeeded()
        withInitializedState {
            state.optedOut = false
            optedOutSnapshot = false
            persist()
            // Registrations were held back while the person was opted out.
            registerStoredPushDeviceLocked(pushPermission)
        }
    }
    fun optOut() = withInitializedState {
        state.optedOut = true
        optedOutSnapshot = true
        // A device removal still goes out: opting out of analytics must not
        // leave a signed-out person's device registered.
        state.queue = JSONArray(queuedPushDeviceRemovalsLocked())
        // A queued registration went with the queue.
        lastPushRegistration = null
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
        // The removal is built before the identity rotates, so it carries the
        // distinct ID of the person who signs out. It is the one event a reset
        // keeps: the rest of the queue goes, as before.
        removePushDeviceFromCurrentPersonLocked()
        val removals = queuedPushDeviceRemovalsLocked()
        // The token stays on the device so the next identify can register it.
        // The switch does not: it belonged to the person who signs out.
        val push = state.push?.copy(enabled = null)?.takeUnless(PushDeviceState::isEmpty)
        state = State.fresh(dependencies, optedOut)
        state.purchaseAttributionEnabled = purchaseAttributionEnabled
        state.queue = JSONArray(removals)
        state.push = push
        persist()
        if (purchaseAttributionEnabled) {
            revenueCatIdentity?.logIn(state.purchaseAttributionToken)
            capture("\$set", mapOf(
                "\$purchase_attribution_token" to state.purchaseAttributionToken,
            ))
        }
        if (removals.isNotEmpty()) flushPushDeviceRemoval()
    }

    /**
     * Stores this device's push token. Pass the token from
     * `FirebaseMessaging.getInstance().token` and from `onNewToken`. The SDK
     * has no Firebase dependency, never fetches a token, and never asks for
     * the notification permission; it only reads whether notifications are
     * on.
     *
     * The device is registered only for an identified person: the SDK sends
     * the registration after `identify` and until [reset], and sends nothing
     * for a guest. While a person is identified it sends the registration
     * again on every app start, on a new token, and after [setPushEnabled].
     * A call that changes nothing sends nothing when this process already
     * sent the registration, so the usual call on every launch costs no
     * second event.
     *
     * With the same [provider], a null [appId] or [environment] keeps the
     * stored value: `onNewToken` can pass only the token.
     *
     * A token the server would refuse is not stored and not sent, and the SDK
     * logs a warning with the reason (never with the token).
     */
    @JvmOverloads
    fun registerPushToken(
        token: String,
        provider: FounderHQPushProvider = FounderHQPushProvider.FCM,
        appId: String? = null,
        environment: FounderHQPushEnvironment? = null,
        enabled: Boolean? = null,
        permission: FounderHQPushPermission? = null,
    ) {
        val normalized = when (val result = founderHqNormalizePushToken(token, provider)) {
            is FounderHQPushTokenResult.Valid -> result.token
            is FounderHQPushTokenResult.Invalid -> {
                // The token is never logged, valid or not.
                Log.w(SDK_NAME, "registerPushToken ignored the token: ${result.reason}")
                return
            }
        }
        // A binder call, so it is made before the lock is taken.
        val seen = permission ?: systemPushPermission()
        withInitializedState {
            val previous = state.push
            if (previous?.token != normalized || previous?.provider != provider) {
                // A refreshed token replaces the old one instead of leaving it behind.
                removePushDeviceFromCurrentPersonLocked()
            }
            val sameProvider = previous?.provider == provider
            setPushLocked(PushDeviceState(
                token = normalized,
                provider = provider,
                appId = appId?.trim()?.takeIf(String::isNotEmpty)
                    ?: previous?.appId.takeIf { sameProvider },
                environment = environment ?: previous?.environment.takeIf { sameProvider },
                enabled = enabled ?: previous?.enabled,
                pendingRemovals = state.push?.pendingRemovals.orEmpty(),
            ))
            registerStoredPushDeviceLocked(seen, skipWhenAlreadySent = true)
        }
    }

    /**
     * The app's own push switch for this device. `false` stops every push to
     * it; the token stays registered. The SDK always stores the switch and
     * sends it only while a person is identified; otherwise it travels with
     * the next registration. [reset] clears it.
     */
    fun setPushEnabled(enabled: Boolean) {
        val pushPermission = systemPushPermissionIfNeeded()
        withInitializedState {
            setPushLocked((state.push ?: PushDeviceState()).copy(enabled = enabled))
            registerStoredPushDeviceLocked(pushPermission)
        }
    }

    /** Removes this device from the current person and forgets the token. */
    fun unregisterPushToken() = withInitializedState {
        val removes = removePushDeviceFromCurrentPersonLocked()
        // Only the removals the server has not accepted yet are kept.
        setPushLocked(PushDeviceState(pendingRemovals = state.push?.pendingRemovals.orEmpty()))
        if (removes) flushPushDeviceRemoval()
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
        capture(FounderHQProtocolConstants.PUSH_NOTIFICATION_OPENED_EVENT, properties)
    }

    /**
     * Records the open of a FounderHQ notification and returns its link
     * (`fhqLink`), or null. Pass the intent that started or reached the
     * activity. Only `fhqOutboundMessageId` and `fhqLink` are read from its
     * extras; your app opens the link, the SDK does not.
     *
     * An intent with neither key did not come from a FounderHQ notification
     * (a launcher tap, a deep link, another notification): nothing is sent
     * and the result is null. So it is safe to call for every intent. Extras
     * that cannot be read, and a start from Recents
     * (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`), also send nothing and give
     * null. This never throws.
     */
    @JvmOverloads
    fun capturePushNotificationOpened(
        intent: Intent?,
        properties: Map<String, Any?> = emptyMap(),
    ): String? {
        // A start from Recents brings the notification's intent back. The
        // person did not tap again: no second open, and no link to open.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            return null
        }
        return capturePushNotificationOpened(FounderHQPushPayload.from(intent), properties)
    }

    /** The same, for a payload read with [FounderHQPushPayload.from]. */
    @JvmOverloads
    fun capturePushNotificationOpened(
        payload: FounderHQPushPayload,
        properties: Map<String, Any?> = emptyMap(),
    ): String? {
        if (payload.messageId == null && payload.link == null) return null
        capturePushNotificationOpened(
            if (payload.messageId == null) properties
            else properties + mapOf(
                FounderHQProtocolConstants.PUSH_PROPERTY_MESSAGE_ID to payload.messageId,
            ),
        )
        return payload.link
    }

    /**
     * Takes a FounderHQ notification off the device: call it when the person
     * has seen what the notification is about (they opened the order, the
     * chat, the screen). [key] is the notification key the push was sent
     * with, or the id of the message when it had none.
     *
     * Android shows a FounderHQ push with that key as the notification's
     * tag, so this cancels this app's notifications with that tag, and no
     * other. It sends nothing and stores nothing. Returns how many it
     * cancelled. This never throws.
     */
    fun dismissPushNotification(key: String): Int {
        val wanted = key.trim()
        if (wanted.isEmpty()) return 0
        return try {
            val manager =
                application.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return 0
            var cancelled = 0
            for (shown in manager.activeNotifications) {
                if (shown.tag == wanted) {
                    manager.cancel(shown.tag, shown.id)
                    cancelled += 1
                }
            }
            cancelled
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Removes a notification when FounderHQ asks from the server. Call it
     * first in `FirebaseMessagingService.onMessageReceived` with
     * `message.data`:
     *
     *     override fun onMessageReceived(message: RemoteMessage) {
     *         if (founderHQ.handlePushMessage(message.data)) return
     *         // Your own handling.
     *     }
     *
     * Returns true when the message was a FounderHQ removal: it shows
     * nothing, and the SDK has cancelled the notification it names. Returns
     * false for every other message. This never throws.
     */
    fun handlePushMessage(data: Map<String, *>?): Boolean {
        val key = (data?.get(FounderHQProtocolConstants.PUSH_PAYLOAD_REMOVE_NOTIFICATION_KEY) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return false
        dismissPushNotification(key)
        return true
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
            // A flush that was scheduled before close() must not run after it:
            // by then a newer client can own the stored state.
            if (closed) return false
            return flushOnce(respectRetryLadder)
        }
    }

    private val flushGate = Any()

    private fun flushOnce(respectRetryLadder: Boolean): Boolean {
        val selected: List<JSONObject>
        synchronized(lock) {
            pruneQueueLocked()
            persist()
            if (state.queue.length() == 0) return true
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
                // While opted out only a device removal may leave.
                .filter { !state.optedOut || it.optString("event") == PUSH_DEVICE_REMOVED }
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
                debugLog("delivery refused: HTTP ${response.statusCode}")
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
                settlePushRemovalsLocked(completed)
                pruneDeliveryAttemptsLocked()
                persist()
            }
            if (hasRetry) recordDeliveryFailure(selected)
            !hasRetry
        } catch (error: Exception) {
            debugLog("delivery failed: ${error.javaClass.simpleName}: ${error.message}")
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
        // Waits for a flush that is in flight, and stops the ones still
        // scheduled: shutdown() lets queued tasks run.
        synchronized(flushGate) { closed = true }
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
        val name = activity.title?.toString()?.takeIf { it.isNotBlank() }
            ?: activity.javaClass.simpleName
        currentScreenName = name
        if (captureScreensEnabled) {
            screen(name)
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
        recordElementInteraction(touched)
    }

    internal fun recordElementInteraction(touched: View) {
        if (!captureElementInteractionsEnabled || optedOutSnapshot) return
        if (founderHqTouchedEnteredValue(touched)) return
        val target = founderHqInteractiveTarget(touched) ?: return
        if (founderHqCaptureBlocked(touched)) return
        val elements = founderHqElementMetadata(target)
        val properties = mapOf(
            "\$event_type" to "touch",
            "\$elements" to elements,
            "\$element_path" to founderHqElementPath(target),
        )
        val declarative = founderHqTapPropertyEntries(touched)
        val screenName = currentScreenName
        fun captureTap(event: String) {
            // Read views and the app's callback on the touch thread, before navigation
            // can replace the hierarchy. Only the property snapshot crosses threads.
            val extra = autoProperties(
                FounderHQAutoPropertiesContext(event, screenName, target, elements.first()),
                declarative,
            )
            executor.execute { capture(event, properties + extra) }
        }
        // Capture takes the state lock and may flush; the touch dispatch that
        // called us belongs to the frame the user is looking at.
        captureTap("\$autocapture")
        if (captureRageClicksEnabled &&
            rageTouchDetected(founderHqTargetKey(target), dependencies.clock.nowMillis())
        ) {
            captureTap("\$rageclick")
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

    /**
     * The one place an event envelope is built. It never rotates a session:
     * [capture] does that for a real event. A device removal names its own id
     * and the person who signed out; every other event is for the person in
     * place now.
     */
    private fun eventPayload(
        event: String,
        properties: Map<String, Any?>,
        uuid: String = dependencies.uuid.uuid(),
        distinctId: String = state.distinctId,
        identified: Boolean = state.identified,
        timestampMillis: Long = dependencies.clock.nowMillis(),
    ): JSONObject = JSONObject()
        .put("uuid", uuid)
        .put("event", event)
        .put("distinct_id", distinctId)
        .put("timestamp", isoTimestamp(timestampMillis))
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
                    (config.personProfiles == PersonProfiles.IDENTIFIED_ONLY && identified),
            ))

    /**
     * A control event: SDK-built properties only, so no super properties, and
     * `beforeSend` is skipped for it (see [finishEvent]). It does not rotate
     * the session and does not count as activity. Purchase controls always
     * carry the attribution token; push device controls never do.
     */
    private fun controlEventLocked(
        event: String,
        properties: Map<String, Any?>,
        attributionToken: Boolean,
        uuid: String = dependencies.uuid.uuid(),
        distinctId: String = state.distinctId,
        identified: Boolean = state.identified,
        timestampMillis: Long = dependencies.clock.nowMillis(),
    ): JSONObject {
        val merged = automaticProperties().toMutableMap().apply {
            if (attributionToken) {
                put("\$purchase_attribution_token", state.purchaseAttributionToken)
            } else {
                remove("\$purchase_attribution_token")
            }
            putAll(properties)
        }
        return eventPayload(event, merged, uuid, distinctId, identified, timestampMillis)
    }

    private fun captureControlLocked(
        event: String,
        properties: Map<String, Any?>,
        accountProperties: Map<String, Any?>,
        persistImmediately: Boolean = true,
    ) {
        if (state.optedOut) return
        enqueueEventLocked(
            controlEventLocked(event, properties + accountProperties, attributionToken = true),
        )
        if (persistImmediately) persist()
    }

    /**
     * Sends the stored token for the current person, and only for an
     * identified one: a guest gets no device. Each registration moves "last
     * seen" on the server, so an unchanged one is sent too: on a start, on
     * an identify, and on a switch. Only [skipWhenAlreadySent] (the app's own
     * `registerPushToken`) skips one that is equal to the last one this
     * process sent. [permission] is read by the caller before it takes the
     * lock.
     */
    private fun registerStoredPushDeviceLocked(
        permission: FounderHQPushPermission?,
        skipWhenAlreadySent: Boolean = false,
    ) {
        val push = state.push ?: return
        if (push.token == null || push.provider == null) return
        if (!state.identified) return
        // The person has this device again. A removal for the same token,
        // provider, and person that still waits would take it away on the
        // next start, so it goes. Before the opt-out check: no registration
        // is sent then, but the device the server has must stay.
        dropPushRemovalsLocked(push.token, push.provider, state.distinctId)
        if (state.optedOut) return
        val sent = SentPushRegistration(state.distinctId, push.copy(pendingRemovals = emptyList()), permission)
        if (skipWhenAlreadySent && sent == lastPushRegistration) return
        lastPushRegistration = sent
        enqueueEventLocked(controlEventLocked(PUSH_DEVICE_REGISTERED, buildMap {
            put(FounderHQProtocolConstants.PUSH_PROPERTY_TOKEN, push.token)
            put(FounderHQProtocolConstants.PUSH_PROPERTY_PROVIDER, push.provider.wireValue)
            put(FounderHQProtocolConstants.PUSH_PROPERTY_PLATFORM, PUSH_PLATFORM)
            push.appId?.let { put(FounderHQProtocolConstants.PUSH_PROPERTY_APP_ID, it) }
            push.environment?.let {
                put(FounderHQProtocolConstants.PUSH_PROPERTY_ENVIRONMENT, it.wireValue)
            }
            push.enabled?.let { put(FounderHQProtocolConstants.PUSH_PROPERTY_ENABLED, it) }
            permission?.let {
                put(FounderHQProtocolConstants.PUSH_PROPERTY_PERMISSION, it.wireValue)
            }
        }, attributionToken = false))
        persist()
    }

    /**
     * Takes the stored token away from the person in place now. Nothing was
     * registered for a guest, so there is nothing to remove for one. The
     * removal is sent even while opted out. Returns whether one was queued.
     */
    private fun removePushDeviceFromCurrentPersonLocked(): Boolean {
        val push = state.push ?: return false
        val token = push.token ?: return false
        if (!state.identified) return false
        // The next registerPushToken for this token is a real change again.
        lastPushRegistration = null
        val removal = PendingPushRemoval(
            uuid = dependencies.uuid.uuid(),
            token = token,
            provider = push.provider,
            distinctId = state.distinctId,
            removedAt = dependencies.clock.nowMillis(),
        )
        // Written down first: the queue can lose an event (age, size,
        // retries), this list cannot.
        setPushLocked(push.copy(
            pendingRemovals = (push.pendingRemovals + removal).takeLast(MAX_PENDING_PUSH_REMOVALS),
        ))
        enqueueEventLocked(pushDeviceRemovedEventLocked(removal))
        persist()
        return true
    }

    private fun pushDeviceRemovedEventLocked(removal: PendingPushRemoval): JSONObject =
        controlEventLocked(
            PUSH_DEVICE_REMOVED,
            buildMap {
                put(FounderHQProtocolConstants.PUSH_PROPERTY_TOKEN, removal.token)
                removal.provider?.let {
                    put(FounderHQProtocolConstants.PUSH_PROPERTY_PROVIDER, it.wireValue)
                }
            },
            attributionToken = false,
            uuid = removal.uuid,
            distinctId = removal.distinctId,
            // A removal is only ever written for an identified person.
            identified = true,
            // The moment the person signed out, on every send. The server
            // leaves alone a device that registered again after it.
            timestampMillis = removal.removedAt,
        )

    /**
     * Forgets every removal of this token for this person: the list entry
     * and the queued copy. Called when the same person has the device again.
     */
    private fun dropPushRemovalsLocked(
        token: String?,
        provider: FounderHQPushProvider?,
        distinctId: String,
    ) {
        if (token == null || provider == null) return
        val push = state.push ?: return
        fun matches(removal: PendingPushRemoval) = removal.token == token &&
            removal.provider == provider && removal.distinctId == distinctId
        val queued = (0 until state.queue.length()).map { state.queue.getJSONObject(it) }
        val kept = queued.filterNot { event ->
            event.optString("event") == PUSH_DEVICE_REMOVED &&
                event.optString("distinct_id") == distinctId &&
                event.optJSONObject("properties")?.let { properties ->
                    properties.optString(FounderHQProtocolConstants.PUSH_PROPERTY_TOKEN) == token &&
                        properties.optString(FounderHQProtocolConstants.PUSH_PROPERTY_PROVIDER) ==
                        provider.wireValue
                } == true
        }
        if (kept.size == queued.size && push.pendingRemovals.none(::matches)) return
        state.queue = JSONArray(kept)
        pruneDeliveryAttemptsLocked()
        setPushLocked(push.copy(pendingRemovals = push.pendingRemovals.filterNot(::matches)))
    }

    /**
     * On a start, every removal the server has not accepted goes to the front
     * of the queue again, before any registration. The queue copy of an older
     * attempt is replaced, so a removal is never in the queue twice, and it
     * starts the retry ladder again. It keeps its first timestamp.
     */
    private fun requeuePendingPushRemovalsLocked() {
        val pending = state.push?.pendingRemovals.orEmpty()
        if (pending.isEmpty()) return
        val others = (0 until state.queue.length())
            .map { state.queue.getJSONObject(it) }
            .filterNot { it.optString("event") == PUSH_DEVICE_REMOVED }
        state.queue = JSONArray(pending.map(::pushDeviceRemovedEventLocked) + others)
        for (removal in pending) {
            state.deliveryAttempts.remove(removal.uuid)
            state.retryEligibleAt.remove(removal.uuid)
        }
        pruneQueueLocked()
        persist()
    }

    /** A removal leaves the list when ingest answers anything but "retry". */
    private fun settlePushRemovalsLocked(completed: Set<String>) {
        val push = state.push ?: return
        if (push.pendingRemovals.none { it.uuid in completed }) return
        setPushLocked(push.copy(
            pendingRemovals = push.pendingRemovals.filterNot { it.uuid in completed },
        ))
    }

    /** Stores the push state. An empty one is not kept. */
    private fun setPushLocked(push: PushDeviceState) {
        state.push = push.takeUnless(PushDeviceState::isEmpty)
        pushTokenStoredSnapshot = push.token != null
        persist()
    }

    private fun queuedPushDeviceRemovalsLocked(): List<JSONObject> =
        (0 until state.queue.length())
            .map { state.queue.getJSONObject(it) }
            .filter { it.optString("event") == PUSH_DEVICE_REMOVED }

    /** A logout is often the last thing an app does, so the removal goes now. */
    private fun flushPushDeviceRemoval() {
        try {
            executor.execute { flushOnSchedule() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The client is closed; the removal stays queued for the next start.
        }
    }

    /**
     * Whether the system shows this app's notifications, read without a
     * prompt. Android has no "not asked yet" answer here: before the app asks
     * on Android 13 and later, notifications are off and this says denied.
     *
     * This is a binder call. Never make it while holding [lock]: every
     * caller reads it first and hands the answer in.
     */
    private fun systemPushPermission(): FounderHQPushPermission? = try {
        (application.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.areNotificationsEnabled()
            ?.let {
                if (it) FounderHQPushPermission.AUTHORIZED else FounderHQPushPermission.DENIED
            }
    } catch (_: Throwable) {
        null
    }

    /** Skips the binder call when there is no token a registration could carry. */
    private fun systemPushPermissionIfNeeded(): FounderHQPushPermission? =
        if (pushTokenStoredSnapshot) systemPushPermission() else null

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
        val finished = finishEvent(event) ?: return
        state.queue = JSONArray(
            listOf(finished) + (0 until state.queue.length()).map { state.queue.getJSONObject(it) },
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
        val finished = finishEvent(event) ?: return
        state.queue.put(finished)
        pruneQueueLocked()
    }

    /** Purchase and push device controls, and events without a hook, keep the SDK-built envelope. */
    private fun finishEvent(event: JSONObject): JSONObject? {
        val originalName = event.optString("event")
        if (originalName == "\$mobile_purchase_prepared" || originalName == "\$mobile_purchase_claim" ||
            originalName == PUSH_DEVICE_REGISTERED || originalName == PUSH_DEVICE_REMOVED
        ) {
            return event
        }
        val hook = config.beforeSend ?: return event
        return try {
            val result = hook.invoke(event) ?: return null
            if (state.optedOut) return null
            // Rebuild before snapshotting: unknown keys are ignored, not validated.
            normalizeTransformedEvent(result, originalName)?.let { JSONObject(it.toString()) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeTransformedEvent(event: JSONObject, originalName: String): JSONObject? {
        val name = (event.opt("event") as? String)?.trim() ?: return null
        if (name.isEmpty() || (name != originalName && !isAllowedEvent(name))) return null
        val uuid = event.opt("uuid") as? String ?: return null
        if (!EVENT_UUID.matches(uuid)) return null
        val supplied = event.opt("timestamp") as? String ?: return null
        val instant = parseWireTimestamp(supplied) ?: return null
        // Ingest takes UTC timestamps only: an offset one is the same instant,
        // rewritten rather than lost there. A UTC one keeps its own precision.
        val timestamp = if (supplied.endsWith("Z")) supplied else isoTimestamp(instant)
        val distinctId = (event.opt("distinct_id") as? String)?.let(::normalizeDistinctId) ?: return null
        val properties = event.optJSONObject("properties") ?: return null
        val processProfile = event.optJSONObject("options")?.opt("process_person_profile") as? Boolean
            ?: return null
        return JSONObject()
            .put("uuid", uuid)
            .put("event", name)
            .put("distinct_id", distinctId)
            .put("timestamp", timestamp)
            .put("properties", properties)
            .put("options", JSONObject().put("process_person_profile", processProfile))
            .apply {
                (event.opt("session_id") as? String)?.takeIf(::isUuidV7)?.let { put("session_id", it) }
                // Ingest trims a window id and refuses the whole event for an
                // empty one or one past 200 UTF-16 units; the id goes instead.
                (event.opt("window_id") as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= 200 }
                    ?.let { put("window_id", it) }
            }
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
        const val SDK_VERSION = "1.3.0"
        private val EVENT_UUID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
        private const val STATE_KEY = "state_v2"
        private const val PUSH_DEVICE_REGISTERED =
            FounderHQProtocolConstants.PUSH_DEVICE_REGISTERED_EVENT
        private const val PUSH_DEVICE_REMOVED =
            FounderHQProtocolConstants.PUSH_DEVICE_REMOVED_EVENT
        /** This SDK's own `$push_platform`. */
        private const val PUSH_PLATFORM = "android"
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
    /** Absent until the app registers a push token or sets its push switch. */
    var push: PushDeviceState? = null,
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
        .apply { push?.let { put("push", it.toJson()) } }

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
                    json.optJSONObject("push")
                        ?.let { PushDeviceState.fromJson(it, dependencies.clock.nowMillis()) },
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

/** What the SDK keeps about this device's push registration. */
private data class PushDeviceState(
    val token: String? = null,
    val provider: FounderHQPushProvider? = null,
    val appId: String? = null,
    val environment: FounderHQPushEnvironment? = null,
    /** The app's own switch. Null until the app sets it; `reset()` clears it. */
    val enabled: Boolean? = null,
    /**
     * Removals the server has not accepted yet. One leaves the list only when
     * ingest accepts it, so a logout while offline is not lost with the queue.
     */
    val pendingRemovals: List<PendingPushRemoval> = emptyList(),
) {
    fun isEmpty() = token == null && enabled == null && pendingRemovals.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        token?.let { put("token", it) }
        provider?.let { put("provider", it.wireValue) }
        appId?.let { put("appId", it) }
        environment?.let { put("environment", it.wireValue) }
        enabled?.let { put("enabled", it) }
        if (pendingRemovals.isNotEmpty()) {
            put("pendingRemovals", JSONArray(pendingRemovals.map(PendingPushRemoval::toJson)))
        }
    }

    companion object {
        /**
         * A field an older build wrote and this one does not know is left
         * behind, and a missing one is simply absent.
         */
        fun fromJson(json: JSONObject, nowMillis: Long): PushDeviceState? {
            val provider = json.optString("provider").let { value ->
                FounderHQPushProvider.values().firstOrNull { it.wireValue == value }
            }
            val token = json.optString("token").takeIf { it.isNotBlank() && provider != null }
            val removals = json.optJSONArray("pendingRemovals")
            return PushDeviceState(
                token = token,
                provider = provider.takeIf { token != null },
                appId = json.optString("appId").takeIf { it.isNotBlank() && token != null },
                environment = json.optString("environment").let { value ->
                    FounderHQPushEnvironment.values().firstOrNull { it.wireValue == value }
                }.takeIf { token != null },
                enabled = if (json.has("enabled")) json.optBoolean("enabled") else null,
                pendingRemovals = (0 until (removals?.length() ?: 0))
                    .mapNotNull { index ->
                        removals?.optJSONObject(index)
                            ?.let { PendingPushRemoval.fromJson(it, nowMillis) }
                    }
                    .takeLast(MAX_PENDING_PUSH_REMOVALS),
            ).takeUnless(PushDeviceState::isEmpty)
        }
    }
}

/** A person keeps at most this many removals waiting for the server. */
private const val MAX_PENDING_PUSH_REMOVALS = 10

private data class PendingPushRemoval(
    /** The event id. It stays the same on every send, so ingest counts it once. */
    val uuid: String,
    val token: String,
    val provider: FounderHQPushProvider?,
    /** The person the device is removed from. */
    val distinctId: String,
    /** When the person signed out. The event carries this time on every send. */
    val removedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("token", token)
        provider?.let { put("provider", it.wireValue) }
        put("distinctId", distinctId)
        put("removedAt", removedAt)
    }

    companion object {
        /** [nowMillis] stands in for the time an earlier build did not write. */
        fun fromJson(json: JSONObject, nowMillis: Long): PendingPushRemoval? {
            val uuid = json.optString("uuid").takeIf(String::isNotBlank) ?: return null
            val token = json.optString("token").takeIf(String::isNotBlank) ?: return null
            val distinctId = json.optString("distinctId").takeIf(String::isNotBlank) ?: return null
            return PendingPushRemoval(
                uuid = uuid,
                token = token,
                provider = json.optString("provider").let { value ->
                    FounderHQPushProvider.values().firstOrNull { it.wireValue == value }
                },
                distinctId = distinctId,
                removedAt = json.optLong("removedAt", 0L).takeIf { it > 0L } ?: nowMillis,
            )
        }
    }
}

/** What a registration event said, to tell an equal one from a changed one. */
private data class SentPushRegistration(
    val distinctId: String,
    val push: PushDeviceState,
    val permission: FounderHQPushPermission?,
)

/** What [founderHqNormalizePushToken] answers. */
internal sealed class FounderHQPushTokenResult {
    /** The token as the server stores it. */
    data class Valid(val token: String) : FounderHQPushTokenResult()
    /** Why the server would refuse it. Never holds the token. */
    data class Invalid(val reason: String) : FounderHQPushTokenResult()
}

private const val PUSH_TOKEN_MIN_LENGTH = 8
private const val PUSH_TOKEN_MAX_LENGTH = 4096
private val APNS_PUSH_TOKEN = Regex("^[0-9a-fA-F]{64,200}$")
private val EXPO_PUSH_TOKEN = Regex("^Expo(?:nent)?PushToken\\[[^\\[\\]\\s]+\\]$")

/**
 * Whitespace as the server's JavaScript reads it (`trim()` and `\s`): the
 * ECMAScript WhiteSpace and LineTerminator characters. Kotlin's own
 * `isWhitespace` is not the same set: it leaves out U+FEFF and adds U+001C
 * to U+001F, so it would store a token the server refuses and refuse one the
 * server stores.
 */
private fun isServerWhitespace(char: Char): Boolean = when (char) {
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680',
    '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
    else -> char in '\u2000'..'\u200A'
}

/**
 * The token as the server stores it, or the reason the server would refuse
 * it. The rules are the server's and must stay the same as
 * `normalizePushToken` in apps/web/src/lib/comms/push-devices.ts:
 *
 * - every provider: 8 to 4096 characters after a trim, no whitespace inside
 *   ("whitespace" is the server's: see [isServerWhitespace]);
 * - `apns`: 64 to 200 hex characters, stored lowercase;
 * - `expo`: `ExponentPushToken[...]` or `ExpoPushToken[...]`, with no
 *   bracket and no whitespace inside the brackets;
 * - `fcm`: nothing more.
 *
 * The iOS and React Native SDKs apply the same rules, and the three test
 * suites share one table of cases.
 */
internal fun founderHqNormalizePushToken(
    token: String,
    provider: FounderHQPushProvider,
): FounderHQPushTokenResult {
    val name = provider.wireValue
    val trimmed = token.trim(::isServerWhitespace)
    if (trimmed.length !in PUSH_TOKEN_MIN_LENGTH..PUSH_TOKEN_MAX_LENGTH) {
        return FounderHQPushTokenResult.Invalid(
            "a $name token has $PUSH_TOKEN_MIN_LENGTH to $PUSH_TOKEN_MAX_LENGTH characters",
        )
    }
    if (trimmed.any(::isServerWhitespace)) {
        return FounderHQPushTokenResult.Invalid("a $name token has no whitespace")
    }
    return when (provider) {
        FounderHQPushProvider.APNS ->
            if (APNS_PUSH_TOKEN.matches(trimmed)) {
                FounderHQPushTokenResult.Valid(trimmed.lowercase(Locale.ROOT))
            } else {
                FounderHQPushTokenResult.Invalid("an apns token is 64 to 200 hex characters")
            }
        FounderHQPushProvider.EXPO ->
            if (EXPO_PUSH_TOKEN.matches(trimmed)) {
                FounderHQPushTokenResult.Valid(trimmed)
            } else {
                FounderHQPushTokenResult.Invalid(
                    "an expo token looks like ExponentPushToken[...] or ExpoPushToken[...]",
                )
            }
        FounderHQPushProvider.FCM -> FounderHQPushTokenResult.Valid(trimmed)
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
    calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
        gregorianChange = Date(Long.MIN_VALUE)
    }
    isLenient = false
}
private val wireTimestampPattern = Regex(
    "^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.([0-9]+))?(Z|[+-][0-9]{2}:[0-9]{2})$",
)
internal fun isoTimestamp(nowMillis: Long): String = synchronized(wireTimestampFormatter) {
    wireTimestampFormatter.format(Date(nowMillis))
}

internal fun parseWireTimestamp(value: String): Long? {
    val match = wireTimestampPattern.matchEntire(value) ?: return null
    val zone = match.groupValues[3]
    val offsetMinutes = if (zone == "Z") 0 else {
        val hours = zone.substring(1, 3).toInt()
        val minutes = zone.substring(4, 6).toInt()
        if (hours > 23 || minutes > 59) return null
        (hours * 60 + minutes) * if (zone[0] == '-') -1 else 1
    }
    // Compare local calendar components before applying the offset. Truncate only
    // sub-millisecond precision for TTL; the event retains the original timestamp.
    val normalized = "${match.groupValues[1]}.${match.groupValues[2].take(3).padEnd(3, '0')}Z"
    return synchronized(wireTimestampFormatter) {
        val position = ParsePosition(0)
        wireTimestampFormatter.parse(normalized, position)
            ?.takeIf {
                position.index == normalized.length && wireTimestampFormatter.format(it) == normalized
            }?.time?.minus(offsetMinutes * 60_000L)
    }
}

private fun normalizeDistinctId(value: String): String? {
    val distinctId = value.trim()
    return distinctId.takeIf {
        it.isNotEmpty() && it.length <= 400 &&
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
    // A device removal waits for the server however long that takes: it has
    // no age limit, and it is the last event a full queue gives up. The
    // pending list bounds how many there are.
    fun isRemoval(event: JSONObject) =
        event.optString("event") == FounderHQProtocolConstants.PUSH_DEVICE_REMOVED_EVENT
    val retained = (0 until queue.length()).mapNotNull { index ->
        val event = queue.optJSONObject(index) ?: return@mapNotNull null
        val createdAt = parseWireTimestamp(event.optString("timestamp")) ?: return@mapNotNull null
        event.takeIf { createdAt >= cutoff || isRemoval(it) }
    }
    var overflow = retained.size - maxOf(1, maxQueueSize)
    if (overflow <= 0) return JSONArray(retained)
    // The oldest events go first, but a removal stays.
    return JSONArray(retained.filter { event ->
        if (overflow > 0 && !isRemoval(event)) {
            overflow--
            false
        } else {
            true
        }
    })
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
            "\$device_type" to deviceType(),
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

    // Canonical vocabulary shared with every FounderHQ SDK and the server:
    // "mobile" | "tablet" | "desktop" | "other" (apps/web/src/lib/event-context.ts).
    // Android has no tablet flag; a large or extra-large screen layout is the
    // conventional signal. Versions up to 1.0.0 sent "Mobile" for every
    // device; the server respells that.
    //
    // A television is checked first and on purpose: Android TV reports an
    // extra-large layout, so without this a TV counts as a tablet — inside
    // the vocabulary and wrong, which is worse than being outside it.
    private fun deviceType(): String {
        val uiMode = application.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            uiMode == Configuration.UI_MODE_TYPE_WATCH ||
            uiMode == Configuration.UI_MODE_TYPE_CAR
        ) {
            return "other"
        }
        val layout = application.resources.configuration.screenLayout and
            Configuration.SCREENLAYOUT_SIZE_MASK
        return if (layout >= Configuration.SCREENLAYOUT_SIZE_LARGE) "tablet" else "mobile"
    }
}
