# Instagram session and login slice (19 Oct 2026)

## Scope and non-claims

This slice adds encrypted-session storage plumbing and a login-step boundary. It does not sign in to Instagram, does not extract cookies from a browser, does not enable Instagram in normal Accounts, and does not claim provider qualification. The owner must perform the first real sign-in after the WebView and upstream transport handoff are implemented.

The accepted prior milestone remains Android runtime compatibility only. WhatsApp accounts, generated API, dependency pin, session files, and normal application ID are untouched. Device checks for this slice must use the separate `app.mini.hub.rtprobe` application ID. No combined build is published or installed over `app.mini.hub`.

## Pinned upstream evidence

- `mautrix/meta` tag `v0.2610.0`, commit `3c16226686c71af64df294df0803791685064e0b`.
- `mautrix/linkedin` tag `v0.2609.0`, commit `788444401499e504b81d50480ddb686cdb976281`.
- The inspected `mautrix/meta` HEAD was `328376d7f551125ddc9cf53a9e3e65563b653db4`.
- The inspected `mautrix/linkedin` HEAD was `1eec30ab980d19a7d0303c16e5701d787d564fcc`.
- Both repositories carry AGPL-3.0 `LICENSE`, SHA-256 `E0EEDBA6...9F2E`, and `LICENSE.exceptions` limited to Beeper and Element. MINI is not named in the exceptions.

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

## Implemented login boundary

`igruntime/login.go` defines the explicit states `credentials`, `2fa`, `web_challenge`, `captcha`, `authenticated`, `cancelled`, and `failed`. Its JSON snapshot contains only state, pinned step ID, instructions, safe field IDs, and a fixed failure code. It omits WebView URLs, user-agents, cookies, CAPTCHA results, challenge payloads, and credentials. `WebViewPending` plus the separate `WebViewURL`, `WebViewUserAgent`, and `WebViewWaitPattern` accessors form a narrow handoff for a dedicated WebView controller. This shape is gomobile-compatible. HTTPS Instagram origin validation is applied before accepting a WebView step.

The machine deliberately fails with `login_transport_unavailable` after transient credential, 2FA, or CAPTCHA completion because the upstream network login process has not yet been wired into this facade. It never treats supplied credentials as authentication. This keeps the eventual credential-entry screen independent and avoids inventing cookie extraction.

## WebView-first investigation and required Android design

Upstream supports two WebView-assisted entry points, not one complete flow:

1. Web challenge: load the upstream Instagram URL, wait for the exact logged-in Instagram URL pattern, read only the required/optional Instagram cookie names, and submit them to the upstream step.
2. CAPTCHA: load the upstream Instagram login URL with its user-agent and run the supplied JavaScript asynchronously. The script checks `event.origin` and uses the `fbsbx.com` iframe. The token must be handed directly to the upstream login process and must not enter ordinary UI state.

Before implementing either flow:

- Call `WebView.setDataDirectorySuffix("mini_ig_<account_hash>")` before creating any WebView. Because the suffix is process-global, serialize profile initialization or use a dedicated login process. Do not clear the shared default `CookieManager`.
- Allow only HTTPS Instagram top-level navigation. Treat `fbsbx.com` as an iframe origin required by the CAPTCHA script, not as a general navigation allowlist. Reject userinfo, unexpected hosts, and redirects outside the allowlist.
- Use a temporary controller and narrow asynchronous `evaluateJavascript`/result handling. Set a timeout, support cancellation, destroy the WebView, remove callbacks, and clear only the account-specific profile after handoff.
- Never log URLs, cookies, CAPTCHA tokens, credentials, challenge payloads, or WebView HTML.

CookieManager is shared within a WebView profile. Clearing it globally could sign out another MINI account or flow. Profile isolation is therefore a prerequisite, not an implementation detail.

No non-zerolog output sink is reachable from the linked `instameow` package in the audit. The pinned source has only generated `var _ = fmt.Printf` references under `pkg/instameow/thrift`, not calls. `fmt.Print` and `os.Stdout` calls are in command-only packages (`cmd/dgwparse`, `cmd/lscli`, `pkg/messagix/bloks/cmd*`), which are not imported by the Android runtime. `pkg/connector/chatinfo.go` and the `messagix/lightspeed` and `messagix/table` files import global zerolog. The runtime sets the global logger to `zerolog.Nop` and disables all levels before constructing a client. This is a shared-process guard, not a substitute for auditing future upstream changes. No login response redaction option is enabled, and no content-bearing logger is attached to the client.

## Tests and limitations

Go `go test ./...` passes for `igruntime` and `waruntime`, covering envelope round-trip, checksum corruption, forbidden challenge data, account-bound restore/export, logout, all login states, WebView origin checks, and secret-free snapshots. Android instrumentation coverage has been added for SQLCipher/Keystore save/reopen, account isolation, logout clear, and invalid/corrupt envelope framing. It still needs to run on the Pixel through the rtprobe build.

The current login facade does not call upstream authentication and does not persist anything by itself. Normal Accounts has no Instagram binding. No live account, challenge, CAPTCHA, session, message, or qualification evidence exists. AGPL notices remain a distribution checkpoint before any combined artifact is shared.

## Concrete next step

Implement the dedicated per-account WebView controller and a non-UI upstream `igconnector` adapter that consumes only the sanitized step boundary. Run it with the owner present on `app.mini.hub.rtprobe`; only after that evidence exists may the runtime transition from unavailable to an authenticated session for that account.
