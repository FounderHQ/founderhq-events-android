# FounderHQ Events for Android

## Installation

Add `mavenCentral()` to your dependency repositories, then:

```kotlin
implementation("com.getfounderhq:events:0.8.0")
// Optional Jetpack Compose integration:
implementation("com.getfounderhq:events-compose:0.8.0")
```

Requires Android API 24 or later. Kotlin imports continue to use `com.founderhq`.

## Usage

`com.getfounderhq:events` captures application/activity lifecycle, activity
screens, app/device/OS context, deep-link campaigns, install referrer data,
sessions, identity, consent, and queued events. `com.getfounderhq:events-compose`
adds a Navigation Compose observer. Neither artifact collects advertising IDs.

Version 0.8.0 sends PostHog-aligned protocol v2 requests to `POST /i/v2/e`, emits
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

Run `./gradlew testDebugUnitTest assembleRelease --max-workers=2` to validate both libraries.
Release tags publish signed artifacts through the SDK release workflow.

## Release notes

### 0.8.0

- The default host is now `https://i.getfounderhq.com`. `app.getfounderhq.com` no longer serves SDK ingest.
