# WhatsApp phone-local runtime

In-process companion-device client built from [whatsmeow](https://github.com/tulir/whatsmeow)
(MPL-2.0). Session SQL stays in memory. Android persists snapshots with SQLCipher + Keystore.

This directory is **outside** the dependency-free Kotlin shared module.

## Pin

- Module: `go.mau.fi/whatsmeow v0.0.0-20261007111105-c386243a72ba`
- Git: `c386243a72bacad080ba2938b37e1e4e0f1ca3b2`

## Build (Windows)

Requires:

- Portable Go 1.26 at `tmp/go126root/go`
- Android NDK `26.1.10909125`
- `ANDROID_HOME`

```powershell
powershell -File native/whatsappRuntime/build-android.ps1
```

Output:

- `native/whatsappRuntime/build/whatsapp.aar`
- `native/whatsappRuntime/build/PACKAGING.md` (size + ELF LOAD headers)

The debug Android app links the AAR when that file exists. Release still does not offer WhatsApp.

## Coordinated runtime (one libgojni.so)

The AAR binds `./waruntime` and `./igruntime` together. `igruntime` links the real
`go.mau.fi/mautrix-meta/pkg/instameow` client (`v0.2610.0`, AGPL-3.0) and exposes no
login, session or network surface. Instagram on-device stays unavailable. WhatsApp
keeps whatsmeow `c386243` via a `go.mod` replace.

Check WhatsApp's generated API is unchanged after any rebuild:

```powershell
powershell -File native/whatsappRuntime/dump-api.ps1 -Jar native/whatsappRuntime/build/aar-extract/classes.jar -Out api.txt
```

See `NOTICE` (AGPL distribution requirements) and
`docs/PHONE_LOCAL_META_FEASIBILITY_2026-10-19.md`.
