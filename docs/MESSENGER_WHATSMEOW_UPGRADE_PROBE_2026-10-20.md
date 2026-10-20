# Messenger whatsmeow upgrade probe (isolated; 20 Oct 2026)

> Isolated investigation only. Production `whatsappRuntime` still replaces
> whatsmeow to `c386243a72ba`. No device sessions were opened or migrated.

## Verdict: COMPATIBLE (shared runtime keeps production pin)

Messenger messagix APIs and E2EE hooks work against the production whatsmeow
replace. Do **not** bump WhatsApp solely to unblock Messenger.

## Pins compared

| Role | Module version | Commit |
|---|---|---|
| Production WhatsApp (go.mod replace) | `v0.0.0-20261007111105-c386243a72ba` | `c386243a72ba` |
| Pulled by `mautrix-meta@v0.2610.0` / Messenger E2EE | `v0.0.0-20261014094007-f16f9ca30d6a` | `f16f9ca30d6a` |

Isolated module: `tmp/msgprobe` (`app.mini.hub/msgprobe`), not linked into the app.

## why Messenger wants a newer whatsmeow

`mautrix-meta/pkg/messagix` (Messenger path) imports:

- `whatsmeow.NewClient`
- `whatsmeow.MessengerConfig`
- `store.Device` / `SetDevice`
- Armadillo / ICDC protobuf helpers under `proto/waArmadilloICDC`, `proto/waAdv`

Instagram `instameow` does **not** import whatsmeow. LinkedIn `linkedingo` does not either.

## Session / store schema compatibility (file hash compare)

Changed paths between the two whatsmeow trees (**18 files**), including:

- `client.go`, `send.go`, `group.go`, `connectionevents.go`, `errors.go`, `armadillomessage.go`, `appstate/keys.go`
- Several `proto/wa*` generated files
- `store/clientpayload.go` (**only a WhatsApp web `waVersion` constant bump**)

Unchanged for the compared pin pair (same SHA-256):

- All `store/sqlstore/upgrades/*.sql` and `upgrades.go`
- `store/store.go`
- `store/sqlstore/container.go`
- `store/sqlstore/store.go`

Interpretation:

- SQL schema / migration chain is identical between these two commits.
- Production pin already exposes `MessengerConfig`, `RefreshCAT`, `GetClientPayload`,
  Armadillo decode, and `GenerateFacebookMessageID`.
- `DecodeFBApplication` exists only on the newer pin and is used by the Matrix
  bridge connector backfill path, **not** by phone-local messagix.

## Experiment results (no production change)

1. `tmp/msgprobe` with `replace => c386243` + beeper/req replace:
   - `TestSQLStoreUpgradeAndDeviceRoundTrip` PASS
   - `TestMessagixMessengerClientAndE2EEHooks` PASS
2. Shared runtime `msgruntime` offline cookie + encrypted device-store tests PASS
   against the same production replace.

## Recommendation (applied)

1. Keep production replace on `c386243a72ba`.
2. Ship Messenger phone-local auth/E2EE scaffolding in the **same** Go runtime
   (`msgruntime`) without lifting the WhatsApp pin.
3. Owner-operated sign-in stays on rtprobe until live verification succeeds.

## Follow-on

See [MESSENGER_PHONE_LOCAL_FEASIBILITY_2026-10-20.md](MESSENGER_PHONE_LOCAL_FEASIBILITY_2026-10-20.md).
