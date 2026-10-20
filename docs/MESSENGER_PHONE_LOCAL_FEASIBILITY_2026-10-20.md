# Messenger phone-local feasibility (20 Oct 2026)

> Probe-only. Manage Accounts stays unavailable. No live sends. No cloud bridge.
> LinkedIn remains **BLOCKED** separately.

## Compatibility gate (finished)

Isolated experiment in `tmp/msgprobe` + shared-runtime `msgruntime` tests:

| Check | Result |
|---|---|
| SQL upgrade files `c386243` vs `f16f9ca` | Identical |
| Store tree difference | Only `waVersion` constant bump in `clientpayload.go` |
| `MessengerConfig` / `RefreshCAT` / `GetClientPayload` on production pin | Present |
| messagix Messenger client + `PrepareE2EEClient` against production replace | PASS (`tmp/msgprobe`) |
| Production `go.mod` replace | Still `whatsmeow => c386243a72ba` |

**Verdict: COMPATIBLE** for one shared Go runtime at the current WhatsApp pin.
No production WhatsApp/Instagram dependency pin or device-session change.

## Auth model (owner-operated)

Required cookies (upstream messagix): `xs`, `c_user`, `datr`.

1. rtprobe `:mslogin` WebView opens `https://www.messenger.com/login` with upstream Chrome UA.
2. After Messenger success URL, harvest cookie header (never logged/shown).
3. `msgruntime.ImportOwnerSession` → `VerifyIdentity` via `LoadMessagesPage`.
4. Open account-local whatsmeow SQL device DB → `PrepareEncryptedClient` → `RegisterEncryptedDevice`.
5. Persist cookie snapshot in SQLCipher (`Provider.FACEBOOK` wire key).

## Encrypted session handling

- Cookie envelope: SQLCipher + Android Keystore (`mini-messenger-session.db`).
- E2EE device DB: app-private `files/messenger-e2ee/<userId>.db` via whatsmeow sqlstore.
- Shared runtime packages: `waruntime` + `igruntime` + `liruntime` + `msgruntime` (one `libgojni.so`).

## Status

| Surface | State |
|---|---|
| Accounts / qualification gates | Unchanged; Messenger unavailable |
| rtprobe owner sign-in UI | Prepared (`MessengerLoginActivity`) |
| Live owner login on device | Not yet executed this session |
| Inbox read / send | Not started |
| LinkedIn | Still BLOCKED (Cloudflare) |

## Build / test evidence

- `go test ./msgruntime ./waruntime ./igruntime ./liruntime` (shared runtime)
- `go test` in `tmp/msgprobe` against production whatsmeow replace
- Production pin unchanged: `replace go.mau.fi/whatsmeow => ...c386243a72ba`
