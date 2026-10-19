# Phone-local Meta/LinkedIn protocol feasibility (19 Oct 2026)

Scope: real upstream protocol packages, Android arm64 compile, gomobile bind,
and integration into the single coordinated runtime. No login, no network, no UI.
Official Business/Creator routes (`InstagramApiConnector`, `InstagramAccountConnector`)
are separate and were not touched.

## Upstream pins

| Repo | HEAD inspected | Release pinned | LICENSE | LICENSE.exceptions |
|---|---|---|---|---|
| mautrix/meta | `328376d7f551125ddc9cf53a9e3e65563b653db4` (2026-10-19) | `v0.2610.0` = `3c16226686c71af64df294df0803791685064e0b` | AGPL-3.0, SHA-256 `E0EEDBA6...9F2E` | Beeper (embed), Element (server bundles). Not MINI. |
| mautrix/linkedin | `1eec30ab980d19a7d0303c16e5701d787d564fcc` (2026-10-19) | `v0.2609.0` = `788444401499e504b81d50480ddb686cdb976281` (not linked) | AGPL-3.0, same hash | Same wording. |

`v0.2610.0..328376d` changes 10 files in messagix/instameow (dgw socket, sync groups,
web auth platform). The tag is pinned for reproducibility.

## Protocol packages

| Package | Role | Imports `bridgev2`? | Imports whatsmeow? | cgo |
|---|---|---|---|---|
| `pkg/messagix` | Messenger/Facebook client (`NewClient(cookies, logger, cfg)`, `LoadMessagesPage`, `Connect`, `DumpState`/`LoadState`, `MessengerLite.DoLoginSteps`) | yes (`messengerlite.go`, `bloks`, `loginerrors`) | **yes**: `whatsmeow.NewClient`, `store.Device`, `MessengerConfig` for E2EE (Armadillo) | no |
| `pkg/instameow` | Instagram DM client (`NewClient(ClientParams)`, `LoadIndex`, `Connect`, `SendMessage`, `GetInstagramNativeSession`/`SetInstagramNativeSession`, `CreateInstagramWebSession`, `DoInstagramCAALoginSteps`) | yes (login files only, for `LoginStep` types) | **no** (0 references) | no |
| `pkg/connector`, `pkg/igconnector` | Matrix bridgev2 glue, DB (`metadb`), Matrix event conversion | yes | connector: `sqlstore.Container` | no |
| `linkedin/pkg/linkedingo` | LinkedIn Voyager client (`NewClient(ctx, urn, jar, ...)`, `RealtimeConnect`, `SendMessage`, `NewPasswordLoginClient`) | `bridgev2/networkid`, `event` only | no | no |

`bridgev2` is pulled transitively as a Go dependency (types only). It does not start a
bridge, homeserver or appservice. No remote Matrix component runs.

## Choice: Instagram (instameow)

- `instameow` has no whatsmeow import. `messagix` E2EE Messenger needs a whatsmeow
  `store.Device`/`sqlstore` and pulls whatsmeow at `f16f9ca` (WhatsApp is qualified on `c386243`).
  Linking messagix would have forced a whatsmeow bump or a second device store.
- Instagram has a native Android login path upstream (`FlowIDAndroidNative = "android"`,
  `types.InstagramNativeSession` with `Authorization`, `Device{PhoneID, DeviceID, USDID...}`).
- Not equivalent to WhatsApp: WhatsApp is a linked companion device (QR/pair code,
  Signal keys). Instagram is a username/password + 2FA/checkpoint/CAPTCHA login that
  impersonates the official app and yields bearer tokens/cookies. Session also needs
  `DumpState` (24 h max age), `SeqID/SeqIDTS` and a persistent login device identity.

## Android experiment (scratch, `C:\Users\nande\scratch\igprobe`)

Toolchain: Go 1.26.0 (`tmp/go126root`), NDK 26.1.10909125, gomobile `x/mobile@v0.0.0-20261011034528-197770444867`.
Script: `probe.ps1`. Pins: `go.mau.fi/mautrix-meta v0.2610.0`, `replace github.com/imroc/req/v3 => github.com/beeper/req/v3 v3.0.0-20260925135712-b49593750e56`.

| Step | Command | Result |
|---|---|---|
| 1 | `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -buildmode=archive ./igfacade` | exit 0 |
| 2 | `CGO_ENABLED=1 CC=aarch64-linux-android26-clang go build -buildmode=c-shared -ldflags=-extldflags=-Wl,-z,max-page-size=16384 ./cmdlib` | exit 0, 21 825 656 B, AArch64, LOAD align 0x4000 x4 |
| 3 | `gomobile bind -target android/arm64 -androidapi 26 -javapkg app.mini.hub.probe ./igfacade` | first run: `missing golang.org/x/mobile dependency` (needs `go get -tool .../gobind`). After adding tool directives: exit 0, 10 279 854 B AAR, `libgojni.so` 30 170 560 B |

Clean rerun of all three steps at 21:50 IST after fixing a probe-script bug that left the
step-3 log from the failed first run in `RESULT.md`: 0 / 0 / 0.

Generated Java (javap): `Probe { native boolean hasNativeSession(); native boolean isAuthenticated(); native String platform(); }`.

## Coordinated runtime integration

- New package `native/whatsappRuntime/igruntime` (constructs a real `instameow.Client`
  with empty cookies, `zerolog.Nop`, `DisableTyping`. Exposes `Platform`, `State`
  = `unavailable`, `LoginAvailable` = false, `IsAuthenticated`, `GlobalLoggingDisabled`).
- Content-free logging gap found and closed: upstream `messagix/lightspeed` and
  `messagix/table` log via the zerolog global logger (`badGlobalLog`), bypassing the
  per-client logger, and `lightspeed/decode.go:47` attaches `block_data`. `igruntime`
  `init()` sets `log.Logger = Nop` and `SetGlobalLevel(Disabled)`. A test writes a
  `block_data` warning to a real buffer and asserts 0 bytes. waruntime already uses `waLog.Noop`.
- `go.mod`: adds `go.mau.fi/mautrix-meta v0.2610.0`, `replace` for beeper/req, and
  `replace go.mau.fi/whatsmeow => v0.0.0-20261007111105-c386243a72ba` (keeps WhatsApp's pin).
  `go list -m go.mau.fi/whatsmeow` confirms `=> c386243a72ba`.
- `go vet ./...` and `go test ./...`: `igruntime ok`, `waruntime ok`.
- `build-android.ps1` binds `./waruntime ./igruntime` into the one AAR.
- Combined AAR (with logging guard) `libgojni.so` 48 582 152 B (was 35 995 912 B, +12.6 MB), LOAD align 0x4000.
- WhatsApp generated API: `javap -public` of `waruntime.Runtime` + `Waruntime` before vs after,
  `fc /b` no differences, SHA-256 `91a00f0c...b777d` both (rechecked after the guard rebuild).
- Search of `native/**/*.kt[s]` for `igruntime|instameow|mautrix-meta|app.mini.hub.wa.ig`: 0 matches.
  No Kotlin loads the Instagram binding, so no connect UI is reachable.
- Debug APK `assembleDebug` exit 0 (131 816 085 B). Its `lib/arm64-v8a/libgojni.so` contains
  `igruntime.GlobalLoggingDisabled` and whatsmeow symbols. APK not installed.
- Device evidence (Pixel 7 Pro, Android 16, arm64-v8a): `go test -c` for android/arm64 with
  NDK clang, pushed to `/data/local/tmp`, run via `adb shell`, deleted after. Both igruntime
  tests PASS, exit 0. The MINI app and its WhatsApp session were not touched. This proves
  the protocol package initialises on the device. It is not provider or live qualification.
- In-app evidence (same Pixel): instrumented test
  `androidTest/.../connector/CoordinatedRuntimeDeviceTest.kt` run inside a real app
  process from the packaged APK. Built with a scratch Gradle init script
  (`applicationIdSuffix ".rtprobe"`, arm64-only because the device had 657 MB free), so
  it installed as `app.mini.hub.rtprobe` next to, not over, `app.mini.hub`.
  `am instrument -e class ...CoordinatedRuntimeDeviceTest`:
  - Combined runtime: `OK (3 tests)`. Covers `GomobileWhatsAppClient.load()` (the
    production WhatsApp load path) returning non-null, Instagram `platform=instagram`,
    `state=unavailable`, `loginAvailable=false`, `isAuthenticated=false`, global logging
    disabled, pin `3c16226`, exactly one `libgojni.so`, and WhatsApp loading after Instagram
    in one process.
  - Negative control, pre-integration runtime: `Tests run: 3, Failures: 2`, both with
    `ClassNotFoundException: app.mini.hub.wa.igruntime.Igruntime`. The WhatsApp test still
    passes. So the test can tell the two runtimes apart.
  - Afterwards both rtprobe packages were uninstalled. `dumpsys package app.mini.hub`
    (`versionCode=1`, `lastUpdateTime=2026-10-17 17:02:09`) is identical before and after.
  The real `app.mini.hub` debug build with this runtime has still not been installed,
  so WhatsApp pairing/sync in the installed app is not re-verified.

## Remaining authentication/session work (not started)

1. Login: upstream flows are password-based (`android` native, `instagram-password` web,
   CAA, 2FA) and return `bridgev2.LoginStep` structs. A gomobile facade must translate
   steps to plain JSON. No Selenium/headless browser is used (`bloks/selenium.go` is a
   pure-Go Bloks interpreter, 0 hits for webdriver/chromedp/exec.Command).
   **Concrete Android dependency: an in-app `android.webkit.WebView`** for two upstream
   step types, both `LoginStepTypeCookies`:
   - Web challenge (`igconnector/login_native.go:377`): load `CookiesParams.URL`
     (instagram.com challenge), wait until URL matches `instagramWebLoggedInURLPattern`
     (`^https://www.instagram.com/(direct/...)?`), then read cookies `sessionid`,
     `csrftoken`, `ds_user_id` (required) and `rur`, `shbid`, `shbts`, `mid`, `ig_did`
     (optional) from `CookieManager` for `instagram.com`, and submit them.
   - reCAPTCHA (`instameow/login_web_captcha.go:109`): load
     `https://www.instagram.com/accounts/login/` with upstream `UserAgent`, inject
     `ExtractJS` (`login_web_captcha.js`), which embeds the `fbsbx.com` iframe, checks
     `event.origin`, and resolves `{captcha_token}`. Needs `evaluateJavascript` with
     Promise results. Upstream says no password or session cookie enters this WebView.
   Safe on Android only if the WebView is app-private, JS bridges are limited to this
   step, cookies are cleared from `CookieManager` after extraction and moved into the
   encrypted store, and URLs are never logged (they carry verification tokens).
   The user has not chosen between in-app password entry and WebView.
2. Session persistence: cookies + `InstagramNativeSession` + `InstagramLoginDevice`
   (contains an ECDSA private key) + `DumpState` blob + SeqID. Must use the existing
   SQLCipher/Keystore snapshot path per account, never plaintext files.
3. Logging: per-client logger is `Nop` and the global zerolog logger is disabled in
   `igruntime`. Never enable `LogRedactedLoginResponses`. Recheck on every upstream bump
   for new `badGlobalLog`, `fmt.Print` or `os.Stderr` sites.
4. Events: `slidetypes.ClientEvent` to the observation pipeline, exact review for sends,
   qualification gates. No live qualification exists.
5. AGPL decision before any distribution (see `native/whatsappRuntime/NOTICE`).

Messenger: same engine family, but E2EE requires whatsmeow `f16f9ca` device store.
Needs either a verified whatsmeow bump for WhatsApp or an isolated device store.
LinkedIn: `linkedingo` is small (no utls/quic, no whatsmeow) and is the cheapest next
link. Login is cookie (`li_at`, `JSESSIONID`) or password.
