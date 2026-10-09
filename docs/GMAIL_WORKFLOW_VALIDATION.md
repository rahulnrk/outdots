# Gmail workflow validation boundary

**Updated:** 2026-10-09
**Scope:** The Gmail connector work after commit `68e44c5`. This document separates locally verified behavior from live account and device evidence that is still required.

## Current implementation

- Google authorization uses a browser-based public OAuth client flow with PKCE. The Android app accepts only a client ID supplied through the Gradle property `mini.googleOAuthClientId`. There is no client-secret field, resource, or build value.
- The redirect is the app-owned URI `app.mini.hub:/oauth2redirect/google`. The callback validates the OAuth state, code verifier flow, scheme, and path before exchanging the code.
- Access and refresh tokens are stored in the Android Keystore-backed account token store. Account metadata stores the provider identity, account type, route, scopes, health, and required user action separately.
- Reconnecting the same Gmail provider identity reuses its stable local account ID. Disconnect removes that account's credential and metadata, not observations already stored under other account IDs.
- The Gmail API adapter is read-only and bounded. It discovers `users/me/profile`, lists a page of messages, fetches metadata, and returns a cursor. Coverage is shown as partial provider sync until history completeness and gap recovery are proven.
- The Accounts surface shows connected provider accounts and setup state. The Sources surface controls notification observation. A notification observation is not presented as an authorized mailbox.
- Leaving the foreground cancels local drafting, preserves the typed draft, and explains how to retry. Draft usefulness feedback is local and is not used as automatic model training. AI remains foreground-only and independent of background notification intake.

## Locally verified checks

These checks use fake HTTP transports, in-memory stores, deterministic identities, or compile-time inspection. They are not live Gmail or device evidence.

| Requirement | Local check | Evidence boundary |
|---|---|---|
| Public OAuth and no Android secret | Shared OAuth contract tests and Android build configuration | Proves request shape and secret exclusion, not Google consent or account access |
| Account isolation | Per-account metadata and token store tests | Proves store boundaries, not two real Gmail accounts |
| Duplicate handling | Same provider message produces the same account-scoped observation and notification key | Does not prove every Gmail history or notification duplicate case |
| Reconnection | Same provider identity keeps its account ID and clears reconnect action | Does not prove revoked-token recovery on a device |
| History preservation | Disconnect path removes only credential and account metadata | Full history and cursor-gap recovery still require provider fixtures and device testing |
| Connection status | Authorized, expired, degraded, and authorization-required states are explicit | Does not prove all provider error bodies map correctly |
| Notification independence | Existing listener path and source filtering do not call AI | Background OS delivery still needs a physical-device matrix |
| Interrupted drafts | Foreground lifecycle preserves typed text and reports cancellation | Low-memory process death and rotation need device testing |
| Draft usefulness | UI has useful, needs-editing, and not-useful outcomes | User editing effort and reply quality need representative evaluation |

Recommended local commands:

```text
cd C:\Users\nande\Downloads\Mini\native
gradlew.bat :shared:jvmTest --no-daemon --offline
gradlew.bat :androidApp:compileDebugKotlin :androidApp:assembleDebug --no-daemon --offline
```

## Reported findings versus verified defects

The Beeper/PocketPal evidence ledger reports missed background notifications, painful reconnection, unclear send status, and missing history as observed or reported product risks. The report does not independently reproduce those defects in MINI. MINI therefore treats them as acceptance requirements, not as claims that the current implementation has reproduced them.

The following are verified repository limitations:

- Gmail authorization cannot complete until a public client ID and approved Google Cloud configuration are supplied.
- Gmail sync is read-only and page-bounded. Reply and send are not implemented or proven.
- There is no live two-account Gmail result in this repository.
- No physical-device background notification, battery-restriction, process-death, or revoked-token recovery result is claimed.
- A notification-only source can lack provider account identity and complete history. Its provenance is shown separately.

## Exact setup required for live Gmail proof

1. Create or select a Google Cloud project and enable the Gmail API.
2. Configure the OAuth consent screen and add the two approved test accounts as test users when the app is in testing mode.
3. Register an Android OAuth public client for the actual application ID and signing certificate used by the test build. Confirm the redirect/deep-link design accepted by the selected Google client configuration.
4. Provide only the public client ID to the build, for example:

   ```text
   gradlew.bat :androidApp:assembleDebug -Pmini.googleOAuthClientId=<public-client-id>
   ```

   Never place a Google client secret in Android resources, source, Gradle properties committed to the repository, logs, or the APK.
5. Confirm the least-privilege Gmail read scope and complete any Google sensitive or restricted-scope review required for the selected account type.
6. Use two approved Gmail identities, ideally one consumer account and one Workspace account if both are in scope. Authorize both separately and record their provider IDs without storing credentials in test artifacts.
7. On a connected Android device, run the matrix for first authorization, two-account isolation, repeated delivery, bounded history, account revoke, reconnect, disconnect, app restart, battery restriction, screen-off notification delivery, and process death.
8. Treat successful read sync as read proof only. A separate send design and approval test is required before claiming replies work.

Until this setup is available, the honest status is independently testable connector code with live authorization and two-account sync blocked by environment configuration.

## Device verification run: 2026-10-09

The Android SDK `adb` tool was available and one connected Pixel 10 Pro was detected. The debug APK was built and installed on that device. The generated `BuildConfig.GOOGLE_OAUTH_CLIENT_ID` was empty, so no Google authorization flow was started and no Gmail account data was accessed.

Live device checks that did run:

- `AttentionWorkflowDeviceTest`: 2 of 2 passed on the Pixel 10 Pro. This is live device evidence for attention persistence and failure isolation, not Gmail account evidence.
- `CommunicationSourcePreferencesDeviceTest`: 1 of 1 passed on the Pixel 10 Pro. This is live device evidence for source preference persistence, not provider authorization.
- The complete four-test instrumentation suite produced 3 passes and 1 failure. `MemoryPressureDeviceTest` failed because the device returned `Failed(reason=Local AI paused because the device is low on memory)` where the test expected a `Pressured` result. This failure is unrelated to Gmail account access and is not being relabeled as a Gmail defect.

Gmail-specific live requirements remain unrun: two account authorizations, independent mailbox sync, account-scoped duplicate checks, restart and reconnect history checks, single-account revoke or disconnect, and visible recovery from provider failures. These require the missing public client configuration and approved test accounts below.

## Send qualification gate

Read-only Gmail sync must pass first. A separate send qualification must then verify the least-privilege grant change from `gmail.readonly` to the reviewed send scope, exact account and recipient review immediately before handoff, explicit user approval, provider response handling, and an idempotency or outbox key that prevents a retry from sending twice. No Gmail send or reply claim is made by this increment.
