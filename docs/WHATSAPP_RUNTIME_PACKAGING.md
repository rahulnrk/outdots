# WhatsApp Android runtime packaging

**Date:** 11 October 2026. Debug-only. This is not live qualification.

Pinned whatsmeow: `c386243a72bacad080ba2938b37e1e4e0f1ca3b2` (`go.mau.fi/whatsmeow v0.0.0-20261007111105-c386243a72ba`). MPL-2.0. See `native/whatsappRuntime/NOTICE`.

## Tooling

- Go 1.26.0 windows/amd64 (portable under `tmp/go126root/go`, gitignored)
- Android NDK `26.1.10909125`
- `gomobile bind -target android/arm64 -androidapi 26`
- Script: `native/whatsappRuntime/build-android.ps1`

## Measured artifacts

| Artifact | Size |
|---|---|
| `whatsapp.aar` | 11 566 708 bytes (~11.0 MiB) |
| `jni/arm64-v8a/libgojni.so` | 35 857 056 bytes (~34.2 MiB) |
| Debug APK after packaging (`androidApp-debug.apk`) | 124 599 734 bytes (~118.8 MiB) |

The previous debug APK on this machine was not archived, so the APK figure is **after** including MediaPipe plus this runtime. The native library itself is 34.2 MiB.

## 16 KB ELF alignment

`llvm-readelf -l libgojni.so` LOAD align is `0x4000` (16384) on every segment:

```
LOAD  ... R    0x4000
LOAD  ... R E  0x4000
LOAD  ... RW   0x4000
LOAD  ... RW   0x4000
```

arm64-v8a only. No x86_64 native library.

## Session storage

The Go runtime uses **in-memory** sqlite (`mode=memory`). It does not write a plaintext `.db` beside the app. Snapshots of that memory DB are stored in SQLCipher (`mini-whatsapp-session.db`) whose key is wrapped in Android Keystore. Process death restore decrypts the snapshot and calls `Reconnect()`.

## App wiring

- Debug Accounts offers “WhatsApp personal (not live-qualified)” with a same-phone pairing code (WhatsApp → Linked devices → Link with phone number). QR remains available in the runtime but cannot be scanned by this phone.
- Release does not offer WhatsApp.
- Matrix homeserver connect remains refused.
- `UnprovisionedWhatsAppClient` is used only when `libgojni.so` / gomobile classes are missing.

## Not done

- No owner-scanned Linked Devices session on a physical phone in this milestone.
- No ingested live WhatsApp message.
- No process-death reconnect evidence from a device.
- Messenger, Instagram, LinkedIn unchanged.
