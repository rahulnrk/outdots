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

1. rtprobe `:mslogin` WebView opens `https://m.facebook.com/login/` with a mobile Chrome UA.
2. After an authenticated Facebook/Messenger surface, harvest cookie header (never logged/shown).
3. `msgruntime.ImportOwnerSession` → `VerifyIdentity` via `LoadMessagesPage`.
4. Open the account-local **sealed** E2EE device store → `PrepareEncryptedClient` → `RegisterEncryptedDevice` only if the restored device has no Messenger JID.
5. Persist cookie snapshot in SQLCipher (`Provider.FACEBOOK` wire key) and persist the sealed device snapshot.

If a verified probe session already exists, rtprobe restores it instead of opening login.

## Encrypted session and device handling

This is application-level protection. App-private file paths are **not** equivalent.

| Secret | Durable form | Key wrapping |
|---|---|---|
| Owner cookies + messagix cached state | SQLCipher table `fb_session` in `mini-messenger-session.db` | Android Keystore wraps the SQLCipher key (`mini.hub.sqlcipher.messenger-session`) |
| E2EE device identity, noise/signed-prekey private keys, prekeys, Signal sessions, sender keys, app-state keys, decrypted-event buffer, inbound-id ledger | In-memory whatsmeow sqlstore. Durable copy is an AES-256-GCM sealed file `files/messenger-e2ee/<userId>.sealed` | 32-byte random per-account key stored in SQLCipher table `fb_device_key`. GCM AAD binds `facebook\|<userId>` so a copied file cannot be opened under another account |

Sealed file layout: `MINIMSGRDEV1 | nonce(12) | GCM(payload)` where payload is `formatVersion | generation | dumpSQL JSON` (same dump/restore approach as WhatsApp `waruntime` snapshots). Writes are tmp+fsync+rename. A crash before rename leaves the previous complete file.

**What this is not:**

- The previous `OpenEncryptedDeviceStore` opened ordinary modernc SQLite on `files/messenger-e2ee/<userId>.db`. That file, plus `-wal`/`-journal`, held device private keys in plaintext. App-private storage does not replace SQLCipher/Keystore.
- Cookie SQLCipher encryption alone does not protect the E2EE device store.
- Renaming the method does not encrypt anything.

Legacy plaintext `.db` files are migrated once into a sealed snapshot and then overwritten+unlinked. Flash storage may still retain old blocks; that is a documented limit, not a remaining live SQLite file.

WebView cookies are scrubbed after harvest. The `:mslogin` WebView profile directory is not a durable E2EE store.

## Tests (Go, `./msgruntime`)

| Case | Evidence |
|---|---|
| Encrypted persist + reopen | Sealed file is not `SQLite format 3`; identity, registration and a Signal session survive Close/Open |
| Device identity/key continuity | Unregistered identity fingerprints unchanged across reopen; no silent regeneration |
| Per-account isolation | Distinct keys; wrong key / wrong account AAD / copied file → `device_state_auth_failed` and no device installed |
| Corruption / wrong key | Tampered file fails open, is not overwritten, original identity restores after the good file is put back |
| Interrupted persist | Hook fails after tmp write; previous sealed bytes and generation stay; reopen keeps identity |
| No plaintext DB/journal/WAL | Directory walk finds no `.db`/`.sqlite`/`-wal`/`-journal`/`-shm` and no SQLite headers |
| Legacy migration | Plaintext whatsmeow `.db` with a registered device migrates without regenerating identity; plaintext file is removed |
| Inbound dedup | Seen message-id hashes survive reopen |

Shared runtime compatibility: `go test ./msgruntime ./waruntime ./igruntime ./liruntime` PASS. Production WhatsApp pin unchanged.

## Status

| Surface | State |
|---|---|
| Accounts / qualification gates | Unchanged; Messenger unavailable |
| Cookie SQLCipher | Implemented (probe) |
| E2EE device store | Sealed in-memory + AES-GCM snapshot (probe). Not plaintext SQLite. |
| rtprobe owner sign-in UI | Restores an existing verified session when present; otherwise WebView login |
| Live owner login / encrypted inbound on device | Recorded after the rtprobe install in this package, separately below if obtained |
| Send / mark-read / qualification | Not started; not authorized |
| LinkedIn | Still BLOCKED (Cloudflare) |

## Limits

- Sealed snapshots use dump/restore JSON, not SQLCipher, because the live store must stay in modernc sqlite for whatsmeow. Confidentiality is AES-GCM + Keystore-wrapped key, not SQLCipher page encryption.
- `dumpSQL` is the same family of snapshot as WhatsApp. It covers every user table in the in-memory DB, including pending identity and inbound-id hashes.
- Unregistered devices persist identity material in `mini_pending_identity` so a crash before `RegisterE2EE` does not mint a new keypair.
- Registration is not proof of encrypted-message receiving. Receiving evidence is an `FBMessage` event (unique inbound count), not merely `EncryptedDeviceRegistered`.
- Production `app.mini.hub` WhatsApp/Instagram sessions are not opened or modified by this probe.

## Build / test evidence

- `go test ./msgruntime ./waruntime ./igruntime ./liruntime` (shared runtime)
- `go test` in `tmp/msgprobe` against production whatsmeow replace (compatibility session)
- Production pin unchanged: `replace go.mau.fi/whatsmeow => ...c386243a72ba`
