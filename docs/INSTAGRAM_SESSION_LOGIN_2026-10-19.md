# Instagram session and login slice (19 Oct 2026)

## Scope and non-claims

This slice adds the real upstream Instagram login adapter, wires a successful
upstream authentication into the encrypted-session store, and adds an isolated
owner-operated sign-in screen on the separate `app.mini.hub.rtprobe` application
ID. The login machine no longer stops at `login_transport_unavailable`.

What it still does not do: it does not enable Instagram in normal Accounts, does
not perform any live send, does not mark messages read, and does not change any
qualification gate. The first real sign-in must be performed by the owner; this
agent does not obtain or submit the owner's credentials.

The accepted prior milestone (Android runtime compatibility) still holds.
WhatsApp accounts, the generated API, dependency pins, session files, and the
normal `app.mini.hub` application ID are untouched. All device checks for this
slice use the separate `app.mini.hub.rtprobe` application ID. No combined build
is published or installed over `app.mini.hub`.

## Pinned upstream evidence

- `mautrix/meta` tag `v0.2610.0`, commit `3c16226686c71af64df294df0803791685064e0b`.
- `mautrix/linkedin` tag `v0.2609.0`, commit `788444401499e504b81d50480ddb686cdb976281`.
- The inspected `mautrix/meta` HEAD was `328376d7f551125ddc9cf53a9e3e65563b653db4`.
- The inspected `mautrix/linkedin` HEAD was `1eec30ab980d19a7d0303c16e5701d787d564fcc`.
- Both repositories carry AGPL-3.0 `LICENSE`, SHA-256 `e0eedba615d5cd1b986afb6c5b3a4b1ae33713e7e9dc74d19daec5e3221f9d2e`. `mautrix/meta/LICENSE.exceptions` SHA-256 is `e09a7610a4053bb779b6d524462bdc57e1c3bc3675c63af235c0f574008345f7`; `mautrix/linkedin/LICENSE.exceptions` SHA-256 is `3b40d512518a715fb47599474ff299e294e694b4132985265bc9e2dc0ebccb0a`.
- Both exception files grant embedding/distribution exceptions only to Beeper and Element, subject to keeping modifications publicly available under AGPL-3. MINI is not named in either exception.

The selected package is `go.mau.fi/mautrix-meta/pkg/instameow`. It is preferable to `pkg/messagix` for this phone-local slice because `instameow` has no `whatsmeow` import and exposes a native Instagram client. Messenger's `pkg/messagix` imports `whatsmeow` for Armadillo E2EE and would add a second WhatsApp-device-store compatibility problem.

Pinned `instameow` exports `NewClient`, `LoadIndex`, `Connect`, `Disconnect`, `SendMessage`, `GetInstagramNativeSession`, `SetInstagramNativeSession`, `DumpState`, `LoadState`, `SetSeqID`, `HasSeqID`, and `IsAuthenticated`. `ClientParams` accepts Instagram cookies, logger, HTTP settings, native session, mobile login device, and a device-save callback. The native session includes authorization, user ID, routing claims, and `InstagramLoginDevice`, which contains the USDID private key.

The pinned login implementation in `pkg/igconnector/login_native.go` uses the native `android` flow and the web `instagram-password` flow. It can emit credential, web two-factor, web challenge, cookies, CAPTCHA, display-and-wait, and complete steps. `pkg/instameow/login_web_captcha.go` supplies an Instagram login URL, user-agent, and `ExtractJS` that waits for the reCAPTCHA result from the `fbsbx.com` iframe. This is not a complete WebView-first login API.

## Implemented storage boundary

`igruntime/session.go` defines a versioned `MINI-IG-SESSION` envelope with a length and SHA-256 checksum. It contains only:

- account key and schema version
- allowlisted Instagram cookies
- `InstagramNativeSession`, including authorization and the persistent login-device identity/private key
- post-auth cached state
- sequence ID and timestamp

The envelope rejects unknown cookie names, account/user mismatches, missing native authorization, malformed state, and cached fields named `password`, `captcha_token`, `challenge_url`, `challenge_payload`, request body, or response body. The envelope is not an encryption boundary. The Android store is.

`Runtime.ExportSessionEnvelope`, `RestoreSessionEnvelope`, `SetSequence`, and `Logout` are in-memory operations only. Restore has no network side effect. Malformed cached state is converted into an error, including a guard for the pinned upstream `LoadState` panic path. `Logout` only clears the in-memory runtime. Android must clear the matching account row separately.

`SqlCipherInstagramSessionStore.kt` uses a separate `mini-instagram-session.db`, `ig_session` table, and Keystore alias `mini.hub.sqlcipher.instagram-session`. The SQLCipher database key is random and wrapped with Android Keystore AES-GCM. The account key is the primary key, so one account cannot load another account's snapshot. Invalid or checksum-corrupt blobs are rejected before becoming session objects.

## Implemented login adapter

`igruntime/login.go` defines the sanitized states `credentials`, `2fa`,
`select`, `web_challenge`, `captcha`, `display_wait`, `authenticated`,
`cancelled`, and `failed`. Its JSON snapshot contains only state, pinned step
ID, instructions, safe field IDs, and for a select step the upstream option
value and display label. It also carries a `webview_step` flag, and a fixed
failure code. It omits WebView URLs, user-agents, extraction scripts, cookies, CAPTCHA
results, challenge payloads, and credentials. Separate `WebViewURL`,
`WebViewUserAgent`, `WebViewExtractJS`, and `WebViewWaitPattern` accessors form a
narrow handoff for the dedicated WebView controller only. HTTPS Instagram origin
validation is applied before accepting a WebView step.

`igruntime/login_controller.go` is the real adapter. It drives the pinned
`mautrix-meta` native (`android`) Instagram login against the exported
`instameow.Client` methods (`DoInstagramCAALoginSteps`, `LoadIndex`,
`CancelInstagramCAALoginStep`, `ClearInstagramCAALoginState`,
`GetInstagramNativeSession`), reproducing the logic of
`pkg/igconnector/login_native.go` without importing `pkg/igconnector`.
`go list -deps` confirms the reason: `pkg/instameow` and `bridgev2` pull in zero
whatsmeow packages, but `pkg/igconnector` pulls 22 (Messenger Armadillo E2EE),
which would contaminate the single coordinated runtime. Every request the
controller makes is a genuine upstream request; no authentication is invented and
no cookie extraction is reproduced by hand.

Upstream `bridgev2.LoginStep` results are translated into the sanitized machine:
user-input steps become `2fa`/`select`, and select fields carry their exact
upstream values plus labels into the JSON snapshot. The rtprobe renders those
choices as radio rows and submits the selected upstream value unchanged. Empty
choice lists fail with `selection_options_missing`; empty, invalid, or stale
selections fail with `invalid_or_stale_selection`. Cookies steps with a wait
pattern become `web_challenge`, cookies steps with a special-source token field
become `captcha`, and a complete step triggers the finalizer. Upstream errors
map to fixed, secret-free failure codes (`credentials_rejected`, `checkpoint_unsupported`,
`rate_limited`, `account_suspended`, `network_error`, `upstream_error`); the raw
error text, which can carry URLs or account detail, is dropped. `context.Canceled`
is preserved as `cancelled`.

Success is only reached after a harmless authenticated read. `complete()`
requires a native session with authorization and user ID, runs `LoadIndex`
(the inbox-index read), and binds the stored account to the upstream-verified
identity: the native session user ID, the inbox viewer ID, and the `ds_user_id`
cookie must all agree, or it fails with `verified_identity_mismatch`. The stored
account key is derived from the verified user ID (`instagram|<user_id>`), never
from the provisional slot key or any screen-supplied username. Passwords are
consumed only for the duration of the upstream call and never stored, logged, or
placed in a snapshot.

A WebView-handoff generation counter (`attempt`) is incremented whenever a new
challenge/CAPTCHA step is issued and again on cancellation. A WebView result
tagged with a stale generation is rejected (`stale_callback`), and a result may
only carry the exact field IDs the step declared.

## Dedicated-process WebView login (Android)

The owner sign-in runs in a dedicated `:iglogin` process declared in the
manifest (`InstagramLoginActivity`, `exported=false`). The process sets
`WebView.setDataDirectorySuffix("mini_ig_login")` once at process start. This is
the correct design for a process-global API: the suffix cannot change after a
WebView exists, so a per-account suffix is impossible; one fixed isolated profile
with one active attempt is used instead. Because it is a separate process, its
`CookieManager` is independent of every other MINI WebView, so cookie cleanup
here cannot sign out another flow.

`InstagramWebViewLoginController` is created and destroyed per attempt. It:

- allows only HTTPS top-level navigation to `instagram.com`/`www.instagram.com`,
  with `fbsbx.com` permitted solely as the CAPTCHA iframe origin the upstream
  script uses; userinfo and any other host are rejected;
- for a web challenge, harvests only the declared Instagram cookies once the
  logged-in URL pattern matches, then submits them to the upstream step;
- for a CAPTCHA, runs the pinned upstream `ExtractJS` asynchronously via
  `evaluateJavascript` and forwards only the resulting token;
- tags every result with the upstream attempt id so a result produced after
  cancellation or step advance is rejected as stale by the Go controller;
- scrubs this process's cookies after extraction and on cancellation;
- never logs URLs, cookies, tokens, credentials, or page content.

`InstagramLoginSession` serializes upstream turns on a single background thread,
routes WebView steps to the controller, and on success saves the verified
session into `SqlCipherInstagramSessionStore` (account-isolated, Keystore-wrapped
SQLCipher) bound to the verified account key. `InstagramSessionRestore` restores
a stored session into a fresh runtime and calls `Runtime.VerifyAuthenticatedRead`
to prove a harmless authenticated read on restart.

The rtprobe-only `InstagramLoginProbeLauncher` (present only in the debug source
set) opens the flow with a fresh provisional slot key. Nothing is exported in a
release build, Accounts is never enabled, and no send path is reachable.

No non-zerolog output sink is reachable from the linked `instameow` package in
the audit. `fmt.Print`/`os.Stdout` calls are in command-only packages not
imported by the runtime. The runtime sets the global zerolog logger to
`zerolog.Nop` and disables all levels before constructing any client, and the
login client is built with `LogRedactedLoginResponses` false, so no login
request/response, URL, or cookie is logged. This remains a shared-process guard
that must be re-audited on every upstream bump.

## Read-only Instagram ingestion

`igruntime/read.go` uses the pinned `instameow.Client.LoadIndex` path after the
encrypted session has been restored and the upstream viewer identity has been
verified. It requests a bounded thread and message window, maps only upstream
thread/message data, and does not call send, mark-read, or pagination-mutating
operations. The Go result preserves verified account identity, upstream IDs,
timestamps, sender IDs, and inbound/outbound direction.

`InstagramReadIngestor` rejects a verified-identity mismatch, maps the result to
the encrypted canonical observation store, and uses an account-scoped stable
notification key for repeated-sync deduplication. It stores only a digest for
raw message text in observation metadata; the rtprobe screen renders the
bounded local timeline. Partial coverage is shown as partial rather than
presented as complete history. Missing or expired accounts return the safe
`session_expired` code. The read database is separate from the normal app and
from the login session database.

The current device test confirms synthetic mapping, direction, partial coverage,
account isolation, live bounded sync, repeated-sync idempotency, and persisted
observation IDs. The test does not print account names, message text, URLs,
cookies, or page contents. It does not fabricate an inbound event if the bounded
upstream window contains no incoming message. Session restart restoration was
previously confirmed by `LiveInstagramRestoreDeviceTest`; read synchronization
is currently manual from the authenticated rtprobe screen.

### READ_FAILED investigation

The owner-reported refresh failure reproduced on the preserved session as
`read_failed_upstream_read_nosuchmethodexception`. This identified an application
defect, not authentication expiry, throttling, network failure, unsupported
response data, or encrypted persistence: the Go mobile binding exposes Go
`int` parameters as Java `long`, while the Kotlin reflection bridge requested
Java `int` parameters. The upstream read was therefore never invoked.

The fix requests the generated Java `long,long` signature and passes bounded
values as longs. The adapter now also classifies upstream failures using only
fixed stage/class/status diagnostics such as `session_expired`, `throttled`,
`network_error`, `response_decode_failed`, and `upstream_http_status`; raw
exceptions, URLs, response bodies, cookies, headers, message text, and account
identifiers are not exposed.

After an in-place `adb install -r` preserving the current session and encrypted
observations, the pending read passed. The bounded test observed an inbound
message, completed the repeated-sync uniqueness check, and recorded the safe
arrival-latency bucket `over_60m` using manual refresh. After force-stop and
reopen, the same read/deduplication test reported `OK (1 test)` again.

### Foreground automatic receiving

The next rtprobe package adds `InstagramForegroundReceiver`. While the
authenticated probe Activity is visible, it performs an immediate bounded read
and then polls at a 30-second success interval. A single-thread scheduler plus
the ingestor lock prevents overlapping automatic and manual reads. Leaving the
Activity stops scheduling and invalidates pending callbacks. Network and
throttling failures back off to a five-minute maximum; session expiry stops the
receiver until the owner restores authentication. This is foreground polling
over the supported upstream `LoadIndex` behavior, not a new request protocol.

Background receiving is not implemented or claimed. There is no background
service, wake lock, push receiver, or realtime daemon in this package. The owner
must keep the rtprobe screen visible for automatic receiving evidence.

The foreground package compiled and the signature, mapping, isolation, and live
read instrumentation checks reported `OK (4 tests)`. The installed rtprobe was
updated with `adb install -r`, its existing session and observations were kept,
and the screen was left open. A new owner-generated message still needs a
known send timestamp and an observed automatic appearance to establish
foreground arrival latency; no automatic-arrival claim is made yet.

## Tests and limitations

Go `go test ./igruntime ./waruntime` passes. `igruntime` coverage includes:
envelope round-trip and checksum corruption, forbidden challenge data rejection,
account-bound restore/export with cross-account and truncated-fixture rejection
(a rejected restore leaves no authenticated account), logout, all login machine
states and secret-free snapshots, synthetic multi-choice selection snapshots
preserving labels and values, WebView origin rejection, real
`bridgev2.LoginStep` translation (CAPTCHA vs challenge vs 2FA), cancellation
cleanliness, and fixed upstream failure-code mapping that never surfaces raw
error text.

Android instrumentation reported `OK (4 tests)` for the synthetic selection test
plus encrypted-store, WebView-origin, and request-parsing checks. The APK and
test APK were updated in place with `adb install -r`; `app.mini.hub` was not
modified. The owner completed sign-in on the corrected selection screen. The current live
verification passed without exposing identity data: the verified-account marker
and encrypted SQLCipher session were present, rtprobe was force-stopped and
reopened, and `LiveInstagramRestoreDeviceTest` reported `OK (1 test)`. That test
restored the encrypted session into a fresh runtime and completed the harmless
authenticated inbox read. No message was sent and no qualification gate or
normal Accounts binding changed.

Fresh read-only validation after the ingestion build reported `OK (3 tests)`:
synthetic mapping and account-isolation checks plus the live bounded-sync and
repeated-sync idempotency check. The updated rtprobe and test APKs were again
installed with `adb install -r` only. This run does not claim a newly-arrived
incoming message was generated, because the owner-controlled inbox was not
modified for testing. The live check avoids printing private content and the
read screen remains manual-sync only. The existing Kotlin/AGP compatibility
warning is non-fatal.

After that run, rtprobe was force-stopped and relaunched. The live read test
again reported `OK (1 test)`, demonstrating that the encrypted session and
persisted observation state remained available after process restart.

## First real sign-in (owner)

The agent does not have and will not submit the owner's credentials. To perform
the first real sign-in on a phone with the rtprobe build installed:

1. Build and install the probe (side-by-side, never over `app.mini.hub`):
   - `powershell -File native/whatsappRuntime/build-android.ps1`
   - `gradlew :androidApp:assembleDebug --init-script <rtprobe.init.gradle>`
   - `adb install -r -t androidApp-debug.apk`
2. Launch the isolated sign-in:
   - `adb shell am start -n app.mini.hub.rtprobe/app.mini.hub.android.connector.InstagramLoginProbeLauncher`
   - or tap "Mini IG Probe" on the device launcher.
3. Enter the Instagram email/username and password in the screen. If Instagram
   asks for a 2FA code, enter it. If Instagram shows a verification or CAPTCHA,
   complete it in the embedded browser; the login continues automatically.
4. On success the screen shows the verified `@username` and user ID and confirms
   the encrypted session was stored. To confirm the restart path, kill and
   relaunch; the restore+verify helper performs a harmless inbox read and reports
   the same verified user ID.
5. Cancelling at any step, or a failed login, leaves no authenticated account
   stored.

Do not install this build over `app.mini.hub`, and do not enable the account in
Accounts; this probe is for verifying the real login path only.
