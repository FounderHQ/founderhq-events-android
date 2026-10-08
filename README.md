# FounderHQ Events for Android

## Installation

Add `mavenCentral()` to your dependency repositories, then:

```kotlin
implementation("com.getfounderhq:events:1.3.0")
// Optional Jetpack Compose integration:
implementation("com.getfounderhq:events-compose:1.3.0")
```

Requires Android API 24 or later. Kotlin imports continue to use `com.founderhq`.

## Usage

`com.getfounderhq:events` captures application/activity lifecycle, activity
screens, app/device/OS context, deep-link campaigns, install referrer data,
sessions, identity, consent, and queued events. `com.getfounderhq:events-compose`
adds a Navigation Compose observer. Neither artifact collects advertising IDs.

Version 1.2.0 sends protocol v2 requests to `POST /i/v2/e`, emits
`$session_start`, uses UUIDv7 sessions and screen-scoped `$screen_id` values,
reports screen/viewport dimensions in physical pixels, and never writes a null
`person_id` or duplicate identity keys. Automatic facts use the canonical `$`
taxonomy and deep links recognize all 24 campaign keys.

Pass `account` in `FounderHQEventsConfig` to install context before the first
automatic event. `setAccount`, `setAccountProperties`, `clearAccount` (or
`resetAccounts`) update the app-scoped context, while the optional `account`
argument on `identify` changes identity and account atomically. Account
changes rotate only the account span; queued snapshots and the person session
remain unchanged. `reset()` clears both identity and account context.

`FounderHQEventsDependencies` injects clock, UUID, storage, transport, and
platform facts. The Kotlin suite consumes the shared capability matrix,
executes every applicable fixture action exactly, and compares every captured
request plus normalized persistence/directive state to the canonical golden.
Remote config can disable and re-arm mobile session, screen, and application-
lifecycle capture. The SDK fetches `GET /i/v1/analytics/config` with the
publishable key on its initialization executor, caches the last valid response
in injected storage, and exposes `refreshRemoteConfig()`; set
`remoteConfig = false` to disable fetching.

## Delivery and queueing

The SDK flushes every 10 seconds (`flushIntervalSeconds`) and holds up to 1000
events on disk (`maxQueueSize`) while delivery fails.

A failed event follows a retry ladder: the SDK tries it at once, then after
30s, 30s, 2min and 5min, and then once more the next time the app starts in a
new process. Short first rungs recover a phone that was in a lift or a tunnel.
The growing waits and the bounded count stop an event the server will never
accept from retrying forever. The attempt count and the next eligible time are
stored beside the queue, so the ladder survives the process dying.

A waiting event never blocks the batch: the flush sends the events that are
due and leaves the waiting ones queued. A `flush()` you call yourself asks for
a send now, so it sends waiting events too.

`maxRetries` (5) sets the retries after the first send, and a lower value
truncates the ladder from the front: 2 means 30s, 30s and then give up. The 24
hour TTL always wins, so an event older than `eventTtlMillis` is dropped even
with attempts left. Set `debug = true` to log the drops.

## Element interactions

Set `captureElementInteractions = true` to capture `$autocapture` on button and
control taps, and `$rageclick` when the user taps one control three times in a
second. It is off by default.

The SDK wraps the activity's window callback to observe touches, resolves the
touched view, and stores semantic facts: the view class, the resource entry
name, the content description, and the view hierarchy path.

It stores one more thing: the label of a control. A `Button` and its
subclasses — a `MaterialButton`, a `Switch`, a `CheckBox`, a `RadioButton` —
report their own text, and a Material `TabLayout` reports the label of the
selected tab. Every other view reports no text at all. A plain `TextView` is a
label the app wrote for the user, not a control the user pressed, so it stays
out of the event, and an `EditText`, its contents, and its hint are never read.
A view's own text is used, never a descendant's.

Captured labels have their whitespace normalised, are truncated at 255
characters, and lose any token that looks like a payment card number or a US
social security number. Tooltips, arbitrary view tags, and touch coordinates
are never read into an event. Explicit `setFhqProperties` values are included.
Touch coordinates only resolve the view and are then discarded. Tag a view
`android:tag="fhq-no-capture"` to exclude it and its children.

Remote config switches element capture on and off for a key with
`autocapture`, and rage clicks off with `capture_rageclicks`.
`captureElementInteractions` is the default your app ships with, not a ceiling:
a shipped app cannot be rebuilt on demand, so a wrong default has to be fixable
from the dashboard. Either direction takes effect at once. Switching off stops
reporting, drops what is queued, and hands every wrapped window callback back to
the app. Switching on wraps the window already on screen, without waiting for
the next activity.

Only a tap counts. A finger that travels further than the platform's touch
slop, a second finger, or a cancelled touch stream is a scroll or a gesture,
so lifting a finger over a row at the end of a scroll records nothing.

## Adding properties to automatic events

Use `autoProperties` to add properties to `$screen`, `$autocapture`, and
`$rageclick`:

```kotlin
val config = FounderHQEventsConfig(
    autoProperties = { context ->
        mapOf(
            "section" to (context.screenName ?: "unknown"),
            "control" to context.elementDescription?.get("view_class"),
        )
    },
)
```

`FounderHQAutoPropertiesContext` contains `event`, nullable `screenName`,
`element` (the interactive Android `View` that owns the tap, which may be
an ancestor of the touched view), and `elementDescription` (the same facts
as `$elements[0]`). Both element fields are null for screens. The
callback runs for automatic activity screens, Navigation Compose screens,
every enabled `screen()` call, and each captured tap or rage tap. Return a
map or `null`. If it throws, the event still sends without callback
properties, and the SDK logs one warning per client.

Lifecycle, session, identity, push, and your own `capture()` calls do not
call this hook, even when you pass a reserved event name. Remote config
updates keep your callback. `autoProperties` runs outside the SDK lock:
on the UI thread for taps, automatic activity screens, and Navigation
Compose screens. An explicit `screen()` runs it on the calling thread
(normally the UI thread). Keep it fast and non-blocking.

For taps, attach properties to a view or a container:

```kotlin
import com.founderhq.events.setFhqProperties

productCard.setFhqProperties(mapOf("product_id" to "sku_1"))
buyButton.setFhqProperties(mapOf("action" to "buy"))
```

A tap on the view or anything inside it carries these as top-level
properties. The declarative walk starts at the touched view, even when
`element` is an ancestor that owns the tap, and continues through every
ancestor.
The nearest valid value wins. Keys stay as given; snake_case is the
convention. Pass `null` to clear a view's properties. The
`android:tag="fhq-no-capture"` marker still skips a whole subtree.

Later values win in this order: automatic and registered properties →
`autoProperties` → view properties → explicit `screen(name, properties)`
values. Both new sources use one set of limits:

- Keys must have 1–64 characters and must not start with `$`.
- Values must be strings, finite numbers, or booleans. Strings are trimmed
  and cut to at most 200 UTF-16 code units without splitting a surrogate
  pair. Other values are dropped.
- At most 20 extra keys survive per event. View properties come first,
  nearest view first, then callback properties. Each map keeps its
  iteration order. The first valid value for a key wins; invalid values
  do not use a slot.

These limits do not change explicit `screen()` or `capture()` properties.
The SDK does not capture Compose taps or dead clicks. `events-compose`
only observes navigation, so it has no `Modifier.fhqProperties`.

## beforeSend

Use `beforeSend` to change or drop a finished event:

```kotlin
val config = FounderHQEventsConfig(
    beforeSend = { event ->
        if (event.optString("event") == "debug_action") {
            null
        } else {
            event.getJSONObject("properties").remove("internal_note")
            event
        }
    },
)
```

The callback receives an `org.json.JSONObject` after all properties have
been merged. Return that object or another event object, or `null` to
drop it. A throw also drops the event. `$mobile_purchase_prepared` and
`$mobile_purchase_claim` bypass the hook and keep the SDK-built event so
purchase attribution cannot be silently dropped or reshaped.

New `$identify` events synthesized after an identity-rotation directive do
run through the hook. The hook can see an identify it already transformed
once, so make transformations idempotent. Ordinary delivery retries reuse
the queued snapshot.

For hook results, the SDK rebuilds and snapshots only `uuid`, `event`,
`distinct_id`, `timestamp`, `properties`, `session_id`, `window_id`, and
`options.process_person_profile`. All other top-level and options keys,
including `cookieless_mode`, are stripped. Validation follows these rules:

- `event` is trimmed and must not be empty. Keeping the original event's
  exact name is allowed, including internal names. A renamed event must
  pass the public event-name check after trimming: at most 200 characters,
  and no `$` prefix unless it is a reserved name apps may send.
- `uuid` must use the RFC 4122 8-4-4-4-12 hexadecimal form, version 1–8,
  and variant 8, 9, a, or b (case-insensitive).
- `distinct_id` is trimmed, must have 1–400 characters, and must pass the
  SDK's illegal-distinct-ID check. The trimmed name and ID are queued.
- `timestamp` must be a real calendar instant in RFC 3339 form:
  `YYYY-MM-DDTHH:MM:SS`, optional fractional seconds, then `Z` or `±HH:MM`.
  Whole seconds are valid; impossible dates and missing zones are rejected.
- `properties` must be a JSON object and `options.process_person_profile`
  must be a boolean. Invalid required fields drop the event.
- Optional `session_id` is kept only when it is a UUIDv7 with variant 8–b.
  Optional `window_id` is kept only when it is a string of at most 200
  characters. Invalid optional fields are removed; the event is kept.

Without a hook, SDK-built events are queued untouched. Changing automatic
identity or session events can affect those features.

`beforeSend` runs synchronously **under the SDK's lock**, on the thread
that builds the event: the caller for explicit `capture()`, `screen()`,
identity, and push calls; the UI thread for activity lifecycle and screen
events (including Navigation Compose); the SDK executor for captured taps,
rage taps, and initialization events. A synthesized identity retry runs on
the thread processing `flush()` (its caller for an explicit flush, or the
SDK's background thread for automatic delivery). Session events run on the
thread of the capture that created them. The hook must be fast, must not
block, and must not call back into the SDK.

## Automatic screen properties

Each `$screen` includes `$screen_name` and a new `$screen_id`. It also
includes `$prefers_color_scheme`: `"dark"` or `"light"` from the current
activity's configuration, or the application's when no activity is resumed.
The value is read at capture time and omitted when appearance is undefined
or unavailable. Other events do not automatically receive it.

## Options for automatic properties

| Option | Type | Default | What it does |
| --- | --- | --- | --- |
| `autoProperties` | `((FounderHQAutoPropertiesContext) -> Map<String, Any?>?)?` | `null` | Adds properties to screens and captured taps |
| `beforeSend` | `((JSONObject) -> JSONObject?)?` | `null` | Changes or drops finished events except purchase and push device controls |

## Session tracing headers and push notifications

List your own API hostnames in `tracingHeaders` (exact hostnames: no protocol,
path, port, or wildcard) and add the interceptor to your OkHttp client:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(FounderHQOkHttpInterceptor(events))
    .build()
```

Requests to a listed host then carry `x-founderhq-session-id`, which joins a
backend event to the visit that caused it. Only the session id travels in the
header; a distinct id never does, because a header is forgeable and the server
ignores one. OkHttp is an optional dependency: reference the interceptor only
from an app that already uses OkHttp.

Android delivers notification taps to the app, not to this SDK, so call
`capturePushNotificationOpened(properties)` from the code that handles the
notification intent. It emits `$push_notification_opened` unless
`capturePushNotificationOpened = false`.

## Push notifications

Available from version 1.2.0.

Register the device so FounderHQ Sequences can send it a push. The SDK has no
Firebase dependency, never asks for the notification permission, and never
gets a token itself.

Create the `FounderHQEvents` instance in `Application.onCreate`, not in an
activity. Firebase can start your app in a new process only to give it a new
token, and no activity exists then. The SDK supports one process: do not put
the messaging service, or other code that calls the SDK, in a separate
`android:process`.

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    events.registerPushToken(token) // provider defaults to FounderHQPushProvider.FCM
}
// Also call it from FirebaseMessagingService.onNewToken.

events.setPushEnabled(false)   // your app's own switch for this device
events.unregisterPushToken()   // remove the device and forget the token
```

The SDK always stores the token. A device is registered only for an
identified person: the SDK sends the registration after `identify` and until
`reset()`, and sends nothing for a guest. While a person is identified, the
SDK sends the registration again on every app start, on a new token, and
after `setPushEnabled`. A `registerPushToken` call that changes nothing sends
no second registration in the same app start, so you can call it on every
launch.

With the same provider, a call that gives no `appId` or no `environment`
keeps the stored value. Thus `onNewToken` can pass only the token.

`reset()` removes the device from the person who signs out. It keeps the
token on the phone and clears the `setPushEnabled` switch. An `identify` for
a different person without a `reset()` also clears the switch. If the phone
is offline, the SDK keeps the removal and sends it first on the next app
start, with the time of the sign-out. If the same person signs in again
before the removal goes out, the SDK drops the removal and the device stays.
While the person is opted out, registrations wait, but a removal is still
sent. The two device events (`$push_device_registered` and
`$push_device_removed`) bypass `beforeSend`.

The SDK does not store or send a token that FounderHQ refuses. It logs a
warning with the reason, and never the token.

The SDK reads `NotificationManager.areNotificationsEnabled()`, which shows no
prompt, and sends `authorized` or `denied`. Before your app asks on Android
13 and later, the system reports notifications off, so the SDK sends
`denied`. Pass `permission` to set the state yourself.

Pass the intent from `onCreate` (only when `savedInstanceState == null`) and
from `onNewIntent`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Not after a rotation: the same intent comes back then.
    if (savedInstanceState == null) reportPushOpen(intent)
}

// The activity was already open when the person tapped.
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    reportPushOpen(intent)
}

private fun reportPushOpen(intent: Intent?) {
    val link = events.capturePushNotificationOpened(intent)
    if (link != null) openLink(link)
}
```

The SDK reads two extras, `fhqOutboundMessageId` and `fhqLink`, and nothing
else. It sends the first as `$push_message_id` and returns the second. It
never opens the link. An intent with neither extra is not a FounderHQ
notification: the SDK sends nothing and returns `null`, so you can pass every
intent. `FounderHQPushPayload.from(remoteMessage.data)` reads the same two
keys from a data map.

The call never throws. If Android cannot read the extras (an intent from
another app can hold a class your app does not know), the SDK sends nothing
and returns `null`. It does the same when the person starts the app from
Recents: Android gives the old notification intent again
(`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`), but the person did not tap again.

### A push that arrives in the foreground

FounderHQ sends a `notification` part and a `data` part. The result depends
on the state of your app:

- **Background or closed:** the system tray shows the notification. A tap
  starts your launcher activity, and the `data` keys are String extras on its
  intent. FounderHQ sets no `click_action`, so the tap always goes to the
  launcher activity.
- **Foreground:** Firebase calls `onMessageReceived` and shows nothing. Your
  app must build the notification. Put the two FounderHQ keys on its content
  intent as String extras, then the open is reported the same way.

```kotlin
class AppMessagingService : FirebaseMessagingService() {
    private val events get() = (application as MyApplication).events

    // Firebase can call this in a new process, with no activity.
    override fun onNewToken(token: String) {
        events.registerPushToken(token)
    }

    // Firebase calls this for a push that arrives while your app is in the
    // foreground, and for a removal FounderHQ sends from the server.
    override fun onMessageReceived(message: RemoteMessage) {
        // A removal shows nothing: the SDK cancels the notification it names.
        if (events.handlePushMessage(message.data)) return
        val notification = message.notification ?: return
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // The same two String extras that the system tray gives.
        message.data["fhqOutboundMessageId"]?.let { launch.putExtra("fhqOutboundMessageId", it) }
        message.data["fhqLink"]?.let { launch.putExtra("fhqLink", it) }
        // The tag the system tray gives a FounderHQ push: a later push with
        // the same key replaces this one, and dismissPushNotification finds it.
        val key = FounderHQPushPayload.from(message.data).notificationKey
        val tap = PendingIntent.getActivity(
            this, key.hashCode(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Needs your notification channel and the notification permission.
        NotificationManagerCompat.from(this).notify(
            key,
            0,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }
}
```

Run `./gradlew testDebugUnitTest assembleRelease --max-workers=2` to validate both libraries.
Release tags publish signed artifacts through the SDK release workflow.

### Replace or remove a notification

A FounderHQ push has a notification key: the key the sender set, or else the
message id. Android shows the push with that key as the notification's tag,
so a later push with the same key replaces it. `FounderHQPushPayload.from`
gives the key as `notificationKey`; a notification your app builds itself
(see above) uses it as its tag.

Remove a notification when the person has seen what it is about:

```kotlin
events.dismissPushNotification("order-1042") // returns how many it cancelled
```

FounderHQ can also ask from the server (`POST /api/v1/push/remove`). It sends
a data message that shows nothing. Call `handlePushMessage` first in
`onMessageReceived`: it returns `true` for a removal and cancels the
notification it names, and `false` for every other message.

## Release notes

### 1.3.0

- New: `dismissPushNotification(key)` cancels this app's notifications with
  that notification key (or message ID). Call it when the person has seen
  what the notification is about.
- New: `handlePushMessage(data)` removes a notification when FounderHQ asks
  from your server with a data message.
- `FounderHQPushPayload` has `notificationKey`: the tag Android gives a
  FounderHQ notification. Use it for a notification your app builds itself.
- Both artifacts and the SDK version sent with requests are now `1.3.0`.

### 1.2.0

- New: push devices for FounderHQ Sequences. `registerPushToken`,
  `setPushEnabled`, and `unregisterPushToken` register this device for the
  identified person. `reset()` removes it from the person who signs out.
- New: `capturePushNotificationOpened(intent)` and
  `capturePushNotificationOpened(payload)` report the open of a FounderHQ
  notification and return its link. `FounderHQPushPayload.from` reads the two
  FounderHQ keys from an intent, a `Bundle`, or `RemoteMessage.data`.
- After `close()` returns, a client sends and stores nothing more. Before, a
  flush that was already scheduled could run after `close()`.
- Both artifacts and the SDK version sent with requests are now `1.2.0`.

### 1.1.1

- Documentation only; no change in behavior. Both artifacts and the SDK
  version sent with requests are now `1.1.1`.

### 1.1.0

- `autoProperties` adds app context to screens, taps, and rage taps.
- `View.setFhqProperties` adds properties to taps anywhere inside a view.
  Nearest values win, with one shared 20-key limit across both new sources.
- `beforeSend` can change or drop finished events except purchase controls.
  Throws and invalid results drop the event.
- Screens include `$prefers_color_scheme` when Android reports an appearance.
- The no-capture marker now checks every ancestor, including deeply nested views.
- Both artifacts and the SDK version sent with requests are now `1.1.0`.

### 1.0.2

- `$device_type` is now `mobile` or `tablet`, the same spelling as the web,
  iOS, and React Native SDKs. A large-screen layout reports `tablet`; every
  device reported `Mobile` before. The server reads the earlier spelling the
  same way, so older app versions keep working.

### 1.0.0

- Element interactions match the web, iOS, and React Native SDKs: the same
  events, the same fields, the same scrubbing, and the same rage-tap rule.
- A control's own label now travels with the event: a `Button` and its
  subclasses, and the selected tab of a Material `TabLayout`. An `EditText`'s
  contents and hint are never read, and a plain `TextView` never contributes.
- A tap on an `EditText` now records nothing at all. An `EditText` is clickable
  by default, so it used to report itself as a control.
- Remote config wins in both directions. `autocapture` can switch element
  capture on for an app that shipped with it off, and off for one that shipped
  with it on. Switching on wraps the window already on screen.
  `captureElementInteractions` is the default until settings arrive, not a
  ceiling.
- New: `captureElementInteractions`, `capturePushNotificationOpened`,
  `tracingHeaders`, `maxQueueSize`, `eventTtlMillis`, `maxRetries`, and
  `debug`. `flushIntervalSeconds` now defaults to 10.
- `FounderHQOkHttpInterceptor` adds the session id to requests going to the
  hosts you list in `tracingHeaders`. OkHttp stays an optional dependency.

### 0.8.0

- The default host is now `https://i.getfounderhq.com`. `app.getfounderhq.com` no longer serves SDK ingest.
