# Gmail workflow validation boundary

**Updated:** 2026-10-09
**Scope:** The Gmail connector work after commit `68e44c5`. This document separates locally verified behavior from live account and device evidence that is still required.

## Current implementation

- Google authorization uses a browser-based public OAuth client flow with PKCE. The Android app accepts only a client ID supplied through the Gradle property `mini.googleOAuthClientId`. There is no client-secret field, resource, or build value.
- The redirect is derived from the Android public client ID as `com.googleusercontent.apps.<client-id-prefix>:/oauth2redirect/google`. The callback validates the OAuth state, code verifier flow, derived scheme, and path before exchanging the code.
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
2. Configure Google Auth Platform branding and audience. Add the two approved test accounts as test users while the app is in testing mode.
3. Create an OAuth client of type **Android**, not Web application. Use these exact values for the current debug APK:
   - Package name: `app.mini.hub`
   - SHA-1 signing certificate: `67:1E:79:1E:44:3B:1E:5A:0D:20:05:BB:5B:6D:D1:15:EF:60:6E:E0`
   - Client secret: not used and must not be sent to MINI
4. In that Android client's **Advanced Settings**, enable **Custom URI scheme** because this implementation uses Google's documented installed-app custom-scheme redirect. Google disables this method by default for new Android clients and recommends AuthorizationClient when a migration is practical.
5. Copy only the generated Android client ID. If it is `1234567890-abcdef.apps.googleusercontent.com`, MINI derives this exact redirect URI and Android scheme:
   - Redirect URI: `com.googleusercontent.apps.1234567890-abcdef:/oauth2redirect/google`
   - Manifest scheme: `com.googleusercontent.apps.1234567890-abcdef`
   Do not replace this with a Web client ID or an arbitrary redirect URI. The scheme is derived from the client ID so the manifest and token exchange match the same Android client.
6. Provide only the public client ID to the build, for example:

   ```text
   gradlew.bat :androidApp:assembleDebug -Pmini.googleOAuthClientId=1234567890-abcdef.apps.googleusercontent.com
   ```

   Never place a Google client secret in Android resources, source, Gradle properties committed to the repository, logs, or the APK.
7. Confirm the least-privilege Gmail read scope `https://www.googleapis.com/auth/gmail.readonly` and complete any Google sensitive or restricted-scope review required for the selected account type.
8. Use two approved Gmail identities, ideally one consumer account and one Workspace account if both are in scope. Authorize both separately and record their provider IDs without storing credentials in test artifacts.
9. On the connected Pixel, run the matrix for first authorization, two-account isolation, repeated delivery, bounded history, account revoke, reconnect, disconnect, app restart, battery restriction, screen-off notification delivery, and process death.
10. Treat successful read sync as read proof only. A separate send design and approval test is required before claiming replies work.

These values follow Google's current [native-app OAuth guidance](https://developers.google.com/identity/protocols/oauth2/native-app), [Android authorization guidance](https://developer.android.com/identity/authorization), [Android client authentication guidance](https://developers.google.com/android/guides/client-auth), [OAuth client management guidance](https://support.google.com/cloud/answer/15549257), and Google's [custom URI scheme restriction notice](https://developers.googleblog.com/improving-user-safety-in-oauth-flows-through-new-oauth-custom-uri-scheme-restrictions/).

Until this setup is available, the honest status is independently testable connector code with live authorization and two-account sync blocked by environment configuration.

## Device verification run: 2026-10-09

The Android SDK `adb` tool was available and one connected Pixel 10 Pro was detected. The debug APK was built and installed on that device. In the earlier run, `BuildConfig.GOOGLE_OAUTH_CLIENT_ID` was empty, so no Google authorization flow was started and no Gmail account data was accessed. In the current retry, the installed debug APK was deliberately built with the documentation placeholder `1234567890-abcdef.apps.googleusercontent.com` to verify the guard. `apksigner` confirmed the debug SHA-1 `67:1E:79:1E:44:3B:1E:5A:0D:20:05:BB:5B:6D:D1:15:EF:60:6E:E0`, and the APK was reinstalled with `adb install -r`, preserving app data.

Live device checks that did run:

- `AttentionWorkflowDeviceTest`: 2 of 2 passed on the Pixel 10 Pro. This is live device evidence for attention persistence and failure isolation, not Gmail account evidence.
- `CommunicationSourcePreferencesDeviceTest`: 1 of 1 passed on the Pixel 10 Pro. This is live device evidence for source preference persistence, not provider authorization.
- The complete four-test instrumentation suite previously produced 3 passes and 1 failure. `MemoryPressureDeviceTest` exposed a stale assertion: production intentionally returns `Failed(reason=Local AI paused because the device is low on memory)` for the user-facing low-memory refusal, while the test looked for the internal `Pressured` state name. The assertion now checks the low-memory reason and confirms the result is neither a cancellation nor a repeated-failure recovery state. The corrected isolated test passed on the connected Pixel on the current retry.
- `GmailOAuthConfigurationDeviceTest` passed on the connected Pixel with the documentation placeholder. It proved the placeholder is not configured, the setup status begins with `Google connection setup incomplete`, and the browser launch path is blocked. The rebuilt APK's merged manifest fell back to `app.mini.hub` and did not register the placeholder Google scheme. This is configuration-guard evidence, not Gmail authorization evidence.

Gmail-specific live requirements remain unrun: two account authorizations, independent mailbox sync, account-scoped duplicate checks, restart and reconnect history checks, single-account revoke or disconnect, and visible recovery from provider failures. These require the missing public client configuration and approved test accounts below.

## Send qualification gate

Read-only Gmail sync must pass first. A separate send qualification must then verify the least-privilege grant change from `gmail.readonly` to the reviewed send scope, exact account and recipient review immediately before handoff, explicit user approval, provider response handling, and an idempotency or outbox key that prevents a retry from sending twice. No Gmail send or reply claim is made by this increment.
