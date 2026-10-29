# LinkedIn phone-local feasibility (20 Oct 2026)

> **20 October source review correction:** The current probe remains blocked and no LinkedIn authentication is qualified. However, the blanket conclusion below that no phone-local path exists is too broad. Our pinned `v0.2609.0` is cookie-only; newly inspected upstream commit `ad282448fee3472678394f9a5478f2696fa011ca` adds native password/checkpoint login and browser-header handling. Separately, local probe defects exist even if Cloudflare clears. See the follow-up review at the end. Do not infer that fixing these defects will resolve the browser block.

> **LinkedIn probe authentication: BLOCKED (not completed).**
> Cloudflare blocked the embedded Android WebView before any LinkedIn login,
> cookie harvest, identity verify, or encrypted session save could succeed.
> Runtime compile/session scaffolding remains useful scaffolding only.
> LinkedIn stays unavailable in Manage Accounts. Do not retry the embedded
> WebView login path until a supported Android auth alternative exists.

## Status summary

| Area | Status |
|---|---|
| Coordinated Go compile (`linkedingo` in one `libgojni.so`) | Done |
| Encrypted session store scaffolding | Done (unused by Accounts) |
| Isolated probe UI packaging (`app.mini.hub.rtprobe`, `:lilogin`) | Done |
| Owner authentication / verified session | **BLOCKED** |
| Accounts / Inbox / send | Unavailable / disabled |
| Qualification | Unchanged |

## Upstream pin

| Item | Value |
|---|---|
| Module | `go.mau.fi/mautrix-linkedin` |
| Tag | `v0.2609.0` |
| Commit | `788444401499e504b81d50480ddb686cdb976281` |
| Package | `pkg/linkedingo` |
| LICENSE | AGPL-3.0-or-later |
| LICENSE SHA-256 | `0D96A4FF68AD6D4B6F1F30F713B18D5184912BA8DD389F86AA7710DB079ABCB0` |
| LICENSE.exceptions | Beeper embed / Element server bundles only (not MINI) |

## Upstream login flow (verified against pinned `pkg/connector/login.go`)

Auth model: **browser session cookies + browser-identity request headers**, not OAuth
and not an official member-inbox API. There is no password login client in this pin.

| Step | Upstream behavior | Our probe alignment |
|---|---|---|
| Start URL | `https://linkedin.com/login` with `linkedingo.UserAgent` | Opens login with the same UA |
| Wait | Keep UI open through 2FA until `^https://www\.linkedin\.com/feed.*$` | Same wait pattern |
| Cookie header | Captured from LinkedIn HTTPS requests after browsing; pattern requires `JSESSIONID=` | Harvest Cookie after feed; also require `li_at` (session validity) |
| `X-LI-Track` | Captured from LinkedIn HTTPS **request headers** (not before sign-in) | Captured via `shouldInterceptRequest` during/after navigation; not required to open WebView |
| `X-LI-Page-Instance` | Same: from request headers after activity | Same timing |
| Proof | `GetCurrentUserProfile` after submit | Same; cookies alone do not mark authenticated |

`JSESSIONID` alone is **not** proof of authentication. Upstream invalidates when `li_at`
is deleted/`delete me`. Browser-identity headers are obtained from post-login traffic,
not assumed before opening sign-in.

## Compatibility results (compile; 20 Oct 2026)

Toolchain: Go 1.26.0 (`tmp/go126root`), NDK 26.1.10909125, gomobile from module tools.

| Step | Result |
|---|---|
| `go get go.mau.fi/mautrix-linkedin@v0.2609.0` | ok; whatsmeow replace remains `c386243a72ba` |
| `go test ./liruntime ./igruntime ./waruntime` | ok |
| `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildmode=archive ./liruntime` | ok |
| `build-android.ps1` bind `./waruntime ./igruntime ./liruntime` | ok |
| WhatsApp Java API (`waruntime`) | unchanged across LinkedIn bind |
| Kotlin Accounts wiring | none |

## Authentication attempt result: BLOCKED

Device evidence (`app.mini.hub.rtprobe` only; production `app.mini.hub` untouched):

1. Probe Activity launched on `:lilogin`.
2. Owner Start opened the embedded WebView to LinkedIn login.
3. LinkedIn/Cloudflare returned **Attention Required / Sorry, you have been blocked**
   (`You are unable to access linkedin.com`) before feed/login completion.
4. No cookie harvest, no `GetCurrentUserProfile`, no encrypted session write for a
   verified account.
5. Probe did not bypass the block, rotate proxies, or disable security checks.
6. Blocked attempt was force-stopped and temporary WebView/prefs/session files under
   `app.mini.hub.rtprobe` were cleared. Production account counts remained
   `gmail=2`, `whatsapp=1`, `instagram=1`.

## Upstream-supported alternatives investigated

| Alternative | What it is | Secure Android phone-local fit? |
|---|---|---|
| Embedded WebView cookie login (bridgev2 Cookies flow) | Upstream's in-app/manager WebView path | **Blocked here** by Cloudflare on this device/WebView |
| mautrix-manager (Electron) | Desktop helper that uses correct UA and captures cURL/cookies for a **bridge provisioning API** | Not an Android SDK. Requires a Matrix bridge/host and paste/handoff of cookies to that bridge. Does **not** document a supported way to inject a session into a phone-local MINI client without a remote/local bridge service |
| Bot `login cookies` + paste cURL | Same cookie submit as manager, via Matrix bot | Same bridge dependency; not phone-local Accounts |
| Official LinkedIn member inbox API | Not established for broad personal inbox access | Still unavailable for this product route |

No working Android phone-local login path has been demonstrated by this probe.
That is an observed implementation limit, not proof of upstream impossibility.
The newer upstream password/checkpoint path needs a pinned compatibility experiment.
Cookie import from the owner's ordinary browser is not implemented; the application
cannot obtain another browser's HttpOnly session cookies through a normal redirect.

## Demo requirements note

Keep LinkedIn on the unified demo requirements list with explicit blocker:

> **Blocker:** Embedded Android WebView LinkedIn login is Cloudflare-blocked before
> session establishment. Upstream-supported alternatives depend on mautrix-manager /
> bridge cookie paste, which is not a phone-local MINI Accounts path.

Avoid repeated embedded-login retries until the blocker changes.

## Instagram unfinished work (tracked)

- Instagram sending remains disabled / unqualified for phone-local
- Instagram background receiving remains unimplemented

## Instagram read milestone

Closed separately in `docs/INSTAGRAM_PHONE_LOCAL_PRODUCTION_2026-10-20.md`.

## Follow-up source review: direction and concrete corrections

Reviewed local `liruntime/runtime.go`, its tests, `LinkedInWebViewLoginController.kt`,
`LinkedInLoginSession.kt`, the pinned upstream cookie connector/client, and current
upstream login sources. No live LinkedIn request, cookie extraction, device install
or dependency change was performed during this review.

### Version distinction

Pinned [v0.2609.0 login.go](https://github.com/mautrix/linkedin/blob/v0.2609.0/pkg/connector/login.go)
advertises only cookie login. Current inspected commit
[`ad282448fee3472678394f9a5478f2696fa011ca`](https://github.com/mautrix/linkedin/tree/ad282448fee3472678394f9a5478f2696fa011ca)
also implements password login through `linkedingo.NewPasswordLoginClient`, with
email/SMS/TOTP codes, app approval and browser fallback in
[`login_password.go`](https://github.com/mautrix/linkedin/blob/ad282448fee3472678394f9a5478f2696fa011ca/pkg/connector/login_password.go).
The cookie flow now collects browser headers instead of requiring one fixed
desktop identity. These are candidates for an isolated upgrade, not evidence
that Android compilation or owner authentication succeeds. Never update production
to moving `main` or promise the new path defeats a provider restriction.

### Confirmed local mismatches

1. `FeedURLPattern` in Go is a raw string with doubled backslashes before dots.
   Upstream uses a single backslash in its raw string. The local pattern fails
   to match `https://www.linkedin.com/feed/`; a synthetic regex comparison
   reproduced false for the local expression and true for the upstream expression.
   Fix with Go and Kotlin regression coverage through the generated facade.
2. `harvest()` runs only after matching `onPageFinished`. If required XHR headers
   arrive later, `captureBrowserIdentity()` updates fields but never reevaluates
   completion. No further navigation is guaranteed in a single-page application.
   Reevaluate on relevant state changes with a bounded timeout, thread-safe
   handoff to the WebView thread and cancellation/attempt checks.
3. `captureBrowserIdentity()` accepts a URL through string-prefix tests. An unrelated
   host such as `www.linkedin.com.example.invalid` passes that test. Parse URLs and
   validate HTTPS, userinfo, port policy and exact approved hosts before capture;
   validate redirect destinations separately. This is a validation defect, not
   evidence of an observed credential leak or the cause of Cloudflare.
4. `completeWithSession()` checks the attempt before network work, but does not
   recheck it after `VerifyIdentity()` and immediately before storing the session
   and publishing success. A cancellation/new attempt can race with persistence.
   Use a cancellation/generation barrier covering the commit, not merely UI delivery.

These defects would matter after a successful browser login. They do not explain
why the owner also saw a block in the ordinary browser. Keep that observation
and its unknown cause separate from the application defects.

### Recommended next package for Jcode

Fix and test the local mismatches without requiring another owner sign-in. Compare
the current pinned module with the inspected newer SHA in a scratch Go module;
compile/bind the real password-login dependencies for Android arm64, retaining one
Go runtime and protecting WhatsApp/Instagram/Messenger pins and sessions. Inspect
new logging, serialization, session format and licensing before integration.

If compatible, expose upstream login/checkpoint states in rtprobe, bind successful
identity with a harmless authenticated read, persist only session material through
SQLCipher/Keystore and prove restart recovery. Passwords/codes stay transient.
Render select choices explicitly, support cancellation and timeouts, and respect
provider rejection/challenges without retry loops or bypasses. Keep Accounts and
sending unavailable until live evidence exists. A user-controlled local session
handoff is a separate product/security design, not an automatic need for a cloud
message bridge or permission to extract ordinary-browser cookies.
