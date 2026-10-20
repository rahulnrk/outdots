# LinkedIn phone-local feasibility (20 Oct 2026)

> **Milestone progress: coordinated runtime compile + session scaffolding.**
> Covered: `linkedingo` pinned, android/arm64 archive build, gomobile bind into the
> one `libgojni.so` with WhatsApp + Instagram, cookie jar import/export, SQLCipher
> per-account store scaffolding. Not covered: live LinkedIn login, message sync,
> Accounts UI, send, qualification, or distribution (AGPL undecided).

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

Auth model (upstream docs + `linkedingo`): **browser session cookies**, not OAuth and
not an official member inbox API. Logged-in proof is presence of `JSESSIONID`.
Cookie reuse requires the desktop Chrome user agent from `linkedingo.UserAgent`.
There is no `NewPasswordLoginClient` in `v0.2609.0`; earlier notes naming one are stale.

## Compatibility results (20 Oct 2026)

Toolchain: Go 1.26.0 (`tmp/go126root`), NDK 26.1.10909125, gomobile from module tools.

| Step | Result |
|---|---|
| `go get go.mau.fi/mautrix-linkedin@v0.2609.0` | ok; whatsmeow replace remains `c386243a72ba` |
| `go test ./liruntime ./igruntime ./waruntime` | ok |
| `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildmode=archive ./liruntime` | ok (`tmp/liruntime-android-arm64.a`, 227696 B) |
| `build-android.ps1` bind `./waruntime ./igruntime ./liruntime` | ok |
| AAR | `whatsappRuntime/build/whatsapp.aar` 16 951 666 B |
| `libgojni.so` | one file, 52 442 208 B, LOAD align `0x4000` x4 |
| WhatsApp Java API (`waruntime.Runtime` + `Waruntime`) | **unchanged** SHA-256 `6F2FF693…459DB` before/after |
| New Java classes | `app.mini.hub.wa.liruntime.Runtime`, `Liruntime` |
| Kotlin Accounts wiring | **none** (LinkedIn stays unavailable in Manage Accounts) |

## Facade (`whatsappRuntime/liruntime`)

- Constructs real `linkedingo.NewClient` with empty jar (no network).
- `LoginAvailable() == false` until an isolated cookie WebView probe is proven.
- Cookie session helpers (no network): `ImportCookieHeader`, `ImportCookiesJSON`,
  `ExportCookiesJSON`, `BrowserUserAgent`, `RequiredCookieName`.
- Global zerolog silenced like `igruntime`.

## Encrypted session scaffolding (Android)

- `LinkedInSessionEnvelope` / `LinkedInSessionEnvelopeCodec`
- `SqlCipherLinkedInSessionStore` (`mini-linkedin-session.db`, Keystore-wrapped SQLCipher key)
- Not referenced from Manage Accounts / Inbox

## Isolated owner login probe (implemented)

Authentication requirements (pinned upstream `pkg/connector/login.go` + `linkedingo`):

| Requirement | Role |
|---|---|
| `li_at` cookie | Session token; deleted/`delete me` invalidates access |
| `JSESSIONID` cookie | CSRF token source; **not** sufficient alone |
| `X-LI-Page-Instance` | Browser identity header (`urn:li:page…`) |
| `X-LI-Track` | Browser identity JSON containing `clientVersion` |
| Desktop `UserAgent` | Must match `linkedingo.UserAgent` |
| `GetCurrentUserProfile` | Harmless authenticated proof; binds `AccountId` |

Probe packaging:

- Build with `-Pmini.rtprobe=true` → `applicationIdSuffix=.rtprobe` (does not touch `app.mini.hub`)
- `LinkedInLoginActivity` in process `:lilogin` with WebView data-dir suffix `mini_li_login`
- HTTPS LinkedIn top-level allowlist; owner completes 2FA/challenges in-page
- Cookies/headers never logged or shown; scrubbed after handoff
- Stale attempt callbacks ignored; cancel clears in-memory session

Install/run (leaves sign-in screen open):

```bat
cd native\whatsappRuntime
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-android.ps1
cd ..
gradlew.bat :androidApp:assembleDebug -Pmini.rtprobe=true
adb install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk
adb shell am start -n app.mini.hub.rtprobe/app.mini.hub.android.connector.LinkedInLoginActivity
```

### Device result (20 Oct 2026)

- `app.mini.hub.rtprobe` installed beside existing `app.mini.hub` (production package path unchanged).
- LinkedIn probe Activity opened on `:lilogin` with Start/Restore UI.
- After Start, LinkedIn served a **Cloudflare “Attention Required / Sorry, you have been blocked”** interstitial in the embedded WebView (`You are unable to access linkedin.com`).
- Probe did **not** bypass the block. Owner challenge remains on-screen for manual handling/cancellation.
- No cookies were displayed or logged by the probe UI.

## Instagram unfinished work (tracked)

- Instagram sending remains disabled / unqualified for phone-local
- Instagram background receiving remains unimplemented

## Instagram read milestone

Closed separately in `docs/INSTAGRAM_PHONE_LOCAL_PRODUCTION_2026-10-20.md` with device evidence
and `InstagramPhoneLocalSyncHealthTest` regressions.
