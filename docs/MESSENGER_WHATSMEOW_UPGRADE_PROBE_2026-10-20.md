# Messenger whatsmeow upgrade probe (isolated; 20 Oct 2026)

> Isolated investigation only. Production `whatsappRuntime` still replaces
> whatsmeow to `c386243a72ba`. No device sessions were opened or migrated.

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
- `store/clientpayload.go` (**store package not fully identical**)

Unchanged for the compared pin pair (same SHA-256):

- `store/store.go`
- `store/sqlstore/container.go`
- `store/sqlstore/store.go`

Interpretation:

- Core SQL store container/store entrypoints look stable between these two commits.
- `store/clientpayload.go` **did** change, so a blind whatsmeow bump is still a
  session/device-payload risk for existing WhatsApp phone-local snapshots.
- Client API surface also changed (e.g. new `groupMetaCache.CanSend` in the newer pin).

## Recommendation (no production change)

1. Keep production replace on `c386243a72ba` until a dedicated WhatsApp
   reconnect/regression plan exists.
2. For Messenger, prefer an isolated facade that either:
   - vendors/links messagix E2EE against the newer whatsmeow in a **separate**
     gomobile package/process (hard: one `libgojni.so` constraint), or
   - stages Messenger without Armadillo E2EE first if product allows, or
   - plans an explicit dual-pin strategy with migration tests before lifting the
     WhatsApp replace.
3. Do not upgrade the shared runtime whatsmeow pin solely to unblock Messenger.

## Not done

- No production `go.mod` change
- No AAR rebuild for Messenger
- No device install / session open
