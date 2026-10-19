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
| 3 | `gomobile bind -target android/arm64 -androidapi 26 -javapkg app.mini.hub.probe ./igfacade` | first run: `missing golang.org/x/mobile dependency` (needs `go get -tool .../gobind`). After adding tool directives: exit 0, 10 279 855 B AAR, `libgojni.so` 30 170 560 B |

Generated Java (javap): `Probe { native boolean hasNativeSession(); native boolean isAuthenticated(); native String platform(); }`.

## Coordinated runtime integration

- New package `native/whatsappRuntime/igruntime` (constructs a real `instameow.Client`
  with empty cookies, `zerolog.Nop`, `DisableTyping`. Exposes `Platform`, `State`
  = `unavailable`, `LoginAvailable` = false, `IsAuthenticated`).
- `go.mod`: adds `go.mau.fi/mautrix-meta v0.2610.0`, `replace` for beeper/req, and
  `replace go.mau.fi/whatsmeow => v0.0.0-20261007111105-c386243a72ba` (keeps WhatsApp's pin).
  `go list -m go.mau.fi/whatsmeow` confirms `=> c386243a72ba`.
- `go vet ./...` and `go test ./...`: `igruntime ok`, `waruntime ok`.
- `build-android.ps1` binds `./waruntime ./igruntime` into the one AAR.
  Combined AAR 15 781 797 B, `libgojni.so` 48 576 832 B (was 35 995 912 B, +12.6 MB), LOAD align 0x4000.
- WhatsApp generated API: `javap -public` of `waruntime.Runtime` + `Waruntime` before vs after,
  `fc /b` no differences, SHA-256 `91a00f0c...b777d` both.
- No Kotlin references `igruntime`. No connect UI added.

## Remaining authentication/session work (not started)

1. Login: upstream flows are password-based (`android` native, `instagram-password` web,
   CAA, 2FA, checkpoints, CAPTCHA via `bloks`) and return `bridgev2.LoginStep` structs.
   A gomobile facade must translate steps to plain strings/JSON. Upstream marks some
   checkpoints/CAPTCHA unsupported (`FI.MAU.META_WEB_CHECKPOINT_CAPTCHA`).
   No cookie extraction is offered. If a WebView route is chosen, it needs
   `CookieManager` reads of `sessionid`, `ds_user_id`, `csrftoken`, etc. inside the app's
   own WebView, which is a separate decision (password entry into MINI vs WebView cookies).
2. Session persistence: cookies + `InstagramNativeSession` + `InstagramLoginDevice`
   (contains an ECDSA private key) + `DumpState` blob + SeqID. Must use the existing
   SQLCipher/Keystore snapshot path per account, never plaintext files.
3. Logging: upstream logs through zerolog. Keep `Nop` or a content-free sink. Never
   enable `LogRedactedLoginResponses`.
4. Events: `slidetypes.ClientEvent` to the observation pipeline, exact review for sends,
   qualification gates. No live qualification exists.
5. AGPL decision before any distribution (see `native/whatsappRuntime/NOTICE`).

Messenger: same engine family, but E2EE requires whatsmeow `f16f9ca` device store.
Needs either a verified whatsmeow bump for WhatsApp or an isolated device store.
LinkedIn: `linkedingo` is small (no utls/quic, no whatsmeow) and is the cheapest next
link. Login is cookie (`li_at`, `JSESSIONID`) or password.
