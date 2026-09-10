# FounderHQ Events for Android

## Installation

Add `mavenCentral()` to your dependency repositories, then:

```kotlin
implementation("com.getfounderhq:events:1.0.0")
// Optional Jetpack Compose integration:
implementation("com.getfounderhq:events-compose:1.0.0")
```

Requires Android API 24 or later. Kotlin imports continue to use `com.founderhq`.

## Usage

`com.getfounderhq:events` captures application/activity lifecycle, activity
screens, app/device/OS context, deep-link campaigns, install referrer data,
sessions, identity, consent, and queued events. `com.getfounderhq:events-compose`
adds a Navigation Compose observer. Neither artifact collects advertising IDs.

Version 1.0.0 sends PostHog-aligned protocol v2 requests to `POST /i/v2/e`, emits
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
social security number. Tooltips, view tags and touch coordinates are never
read into an event; the touch coordinates only resolve the view and are then
discarded. Tag a view `android:tag="fhq-no-capture"` to exclude it and its
children.

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

Run `./gradlew testDebugUnitTest assembleRelease --max-workers=2` to validate both libraries.
Release tags publish signed artifacts through the SDK release workflow.

## Release notes

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
