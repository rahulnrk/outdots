# Gmail workflow validation boundary

**Updated:** 2026-10-10
**Scope:** The Gmail connector work after commit `68e44c5`. This document separates locally verified behavior from live account and device evidence that is still required.

## Current implementation

- Google authorization uses a browser-based public OAuth client flow with PKCE. The Android app accepts only a client ID supplied through the Gradle property `mini.googleOAuthClientId`. There is no client-secret field, resource, or build value.
- The redirect is derived from the Android public client ID as `com.googleusercontent.apps.<client-id-prefix>:/oauth2redirect/google`. The callback validates the OAuth state, code verifier flow, derived scheme, and path before exchanging the code.
- Access and refresh tokens are stored in the Android Keystore-backed account token store. Account metadata stores the provider identity, account type, route, scopes, health, and required user action separately. Since `d21ab4c` both stores write synchronously (`commit()`) and log state and credential changes under the `MiniGmailAudit` tag with local IDs only.
- Reconnecting the same Gmail provider identity reuses its stable local account ID. Since `908edbb`, Disconnect removes only that account's credential and keeps its metadata in the `Disconnected` state, so a later reconnect reuses the same local ID and existing history. Before `908edbb`, Disconnect deleted the metadata, so a reconnect created a new local account and would have stored every message a second time.
- Expired access tokens are refreshed with the stored refresh token (public-client grant, no secret). A rejected refresh (`invalid_grant`) marks the account `Revoked` and drops the dead credential. A 403 for missing scope marks it `AuthorizationRequired`. Other failures mark it `Degraded`. One account's failure never changes another account's state.
- Background sync: `GmailSyncJobService` (JobScheduler) runs every 15 minutes (Android minimum) and on demand when the listener sees a Gmail-app notification. It stores messages only. MINI does not post its own OS notification for new Gmail mail.
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
| History preservation | Disconnect removes only the credential and marks metadata `Disconnected` (since `908edbb`) | Full history and cursor-gap recovery still require provider fixtures and device testing |
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

The Gmail reliability matrix status is tracked in the reliability run section below.

## Live two-account Gmail run: 2026-10-09

Pixel 10 Pro, debug APK built with a real Android OAuth client ID (no client secret). The app was installed with `adb install -r` and the tests were run with `adb shell am instrument`, because the Gradle connected-test task uninstalls the app and would wipe the stored authorizations.

Defects found and fixed during this run:

- `003136f`: the OAuth callback ran the token exchange and profile request on the Android main thread. The resulting exception had no message, so the connection failed silently and no account was stored.
- `555271b`, `51c4de6`: callback outcomes are now logged with non-sensitive details only (scheme, path, Google error and reason codes, never tokens or addresses). A Gmail 403 now names its reason. The Accounts dialog reopens after the return from Google so the result is visible.
- Environment: the Google Cloud project initially had the Gmail API disabled (`accessNotConfigured`). After it was enabled, both authorizations completed and were stored.

Live results (counts only, no mailbox content recorded):

| Check | Result as originally recorded | Corrected status |
|---|---|---|
| Two Gmail accounts authorized and stored with distinct local IDs and distinct provider identities | Passed | Still valid |
| Each account returns at least one message through the official Gmail API | Passed | Still valid, but weaker than it read. Each sync fetched a 50-message page and stored only 1 (see parser defect below). |
| Replaying the same first page adds no duplicate observations for either account | Passed | Invalidated as stated. Only 1 message per account was stored, so the check compared 1 row with 1 row. Re-proven with 50 rows per account after `908edbb`, see below. |
| Every stored summary stays under its owning account | Passed | Still valid for the 1-row data, re-proven for 50 rows |
| Per-account history counts unchanged after `am force-stop` and process restart | Passed | Invalidated as stated. The counts compared were the summary `observation_count`, which was always written as 1. Re-proven with true counts after `908edbb`, see below. |

## Defects that invalidated earlier claims (fixed in `908edbb`)

- **Message list parser kept only one message.** The Gmail list response is pretty-printed (`},\n {`), and the parser split on the literal `},{`. A 50-message page parsed as one message. Fix: depth-aware object walk plus a regression test with a pretty-printed multi-message response.
- **Conversation counts were constant.** The encrypted store wrote `observation_count = 1` and `unread_count = 0|1` on every insert, regardless of stored rows. Fix: counts are derived from `notification_observations` rows inside the same transaction.
- **Disconnect broke reconnect identity.** Disconnect deleted the account metadata, so a reconnect created a new local account ID. Every message would then have been stored again under the new ID. Fix: Disconnect keeps the metadata in `Disconnected` state. `forget()` exists separately for permanent removal.
- **Expired access caused a forced reconnect.** An expired access token immediately set `Expired` and asked the user to reconnect, although a refresh token was stored. Fix: silent refresh. Only `invalid_grant` leads to `Revoked`.
- **Background scheduling could crash the listener.** A network-constrained job needs `ACCESS_NETWORK_STATE`. Without it, `JobScheduler.schedule` threw a `SecurityException` inside `onNotificationPosted`. Fix: permission added and scheduling wrapped so a failure is logged, not thrown.
- **Account and credential writes were asynchronous** (`apply()`). After the first reconnect attempt on 2026-10-09 23:27 IST, the OAuth log said the account was stored, but the next read showed `Disconnected` with no credential. The log buffer had been cleared by the step runner, so the cause could not be proven. `d21ab4c` switches both stores to `commit()` and adds the `MiniGmailAudit` trail so a repeat is diagnosable. This item stays open as an unexplained one-time failure. Attempt 2 passed on the audited build.

## Reliability run: 2026-10-09 to 2026-10-10

Account A = local `gmail-13a6451f…`, account B = local `gmail-de284ee8…`. Steps are methods of `GmailReliabilityDeviceTest`, run one at a time with `am instrument`. Logs (`MiniGmailLive`, `MiniGmailSync`, `MiniGmailOAuth`, `MiniGmailAudit`) contain local IDs, states and counts only.

| Check | Evidence | Status |
|---|---|---|
| Baseline after parser and count fixes | `GmailLiveSyncDeviceTest`: each account stored 50 messages, replay added 0. `GmailHistoryAfterRestartDeviceTest`: 50 and 50 after `force-stop`. | Passed live |
| Disconnect A, B unaffected | `step1`: A state `Disconnected`, credential removed, A skipped with `disconnected`, A history 50 → 50. B `Healthy`, fetched 50, inserted 0. | Passed live (data layer). The Accounts UI after disconnect was not inspected because the phone was locked. |
| B baseline before reconnect | `step1b`: B 50 messages, 50 stored rows, 0 count mismatches | Passed live |
| Reconnect A reuses record, keeps history, no duplicates | Attempt 1 (2026-10-09 23:27 IST, pre-audit build): OAuth log `stored account (new=false)`, but `step2` then read A as `Disconnected` with no credential. The accounts file was last modified at 23:28:51, after the callback, and only `disconnect()` sets that state, so a second write occurred that could not be attributed because the log had been cleared. Attempt 2 (2026-10-10 10:25 IST, audited build `d21ab4c`): audit shows `credential stored hasRefresh=true` and `Disconnected -> Authorized` from `GmailOAuthClient.handleRedirect`, then `Authorized -> Healthy` from sync. `step2`: same local ID, 2 accounts total, 50 stored rows before sync, fetched 50, inserted 0 then 0, 50 rows in 44 conversations, 0 count mismatches. B: same ID, `Healthy`, credential present, 50 → 50, 0 mismatches, 0 rows crossed accounts. | Attempt 1 failed live, cause unproven. Attempt 2 **passed live**. |
| Simulated expiry | `step4`: A's stored expiry rewritten to the past. A real refresh against Google succeeded, state `Healthy`, new token, future expiry. | Passed live. This is **simulated**: Google had not expired the token. |
| Natural expiry | `step5` requires B's token to be past Google's own expiry, then proves Google returns 401 for it before the refresh. At 23:29 IST B had 58 minutes left. | Not yet run |
| Revoke A at Google, B unaffected | User removed the app's access in A's Google Account at about 10:30 IST. `step3` sync: Gmail returned 401 for A's access token, the refresh was rejected `HTTP 400 (invalid_grant)`, audit `credential removed` then `Healthy -> Revoked`, A skipped `refresh_failed_after_401`, A history 50 → 50. B in the same run: `Healthy`, fetched 50, inserted 0. Accounts dialog (labels read from the screen, address masked): A `Access removed` with "Google access for this account was removed. Tap Connect Gmail and choose this account to restore syncing. Local history is preserved."; B `Connected`. | Passed live |
| Re-authorize A after revoke | `step3b` | Not yet run |
| Background trigger from Gmail-app notification | 10:22:08 IST: listener saw a Gmail-app notification, job started 23 ms later with `screenInteractive=true`, skipped A (`Disconnected`), synced B (fetched 50, inserted 0). | Passed live for the trigger path. Screen on, so not screen-off evidence. |
| Background new-mail sync, screen off | `step6`, `step7` with a user-sent synthetic email | Not yet run |

## Send qualification gate

Read-only Gmail sync must pass first. A separate send qualification must then verify the least-privilege grant change from `gmail.readonly` to the reviewed send scope, exact account and recipient review immediately before handoff, explicit user approval, provider response handling, and an idempotency or outbox key that prevents a retry from sending twice. No Gmail send or reply claim is made by this increment.
