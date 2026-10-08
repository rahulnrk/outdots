# Test report

Only commands actually executed are recorded here. Results were independently rerun by the coordinator.

## 2026-10-08, web/native integration checkpoint

Run from `C:\Users\nande\Downloads\Mini` on Windows, JDK 17, Node, Android SDK 34/35.

| Command | Result |
|---|---|
| `npm test` | Pass: 3 files, 59 tests (13 domain, 33 security, 13 publisher) |
| `npm run typecheck` | Pass: domain, publisher, hub |
| `npm run build` | Pass: all workspaces, Vite bundle 192.88 kB JS / 60.40 kB gzip |
| `npm audit --audit-level=moderate` | Pass: 0 vulnerabilities (previously run at baseline) |
| `native\\gradlew.bat :shared:jvmTest` | Pass: 17 tests, including scaffold, action core, persistence boundary and intelligence safety/lifecycle tests |
| `native\\gradlew.bat :shared:jvmTest :androidApp:assembleDebug` | Pass: 17 JVM tests and debug APK built, including the Android SQLCipher/Keystore store compilation |
| APK archive inspection | Pass: debug APK is 29,609,054 bytes and packages `libsqlcipher.so` for arm64-v8a, armeabi-v7a, x86, and x86_64 |

### Tests added in this phase

- Approval and dispatch race, restart interruption, invalid/rolled-back clock.
- New inbound evidence, tombstones, stale drafts and current source binding.
- Recipient and attachment digest drift, weak digest refusal, account ownership.
- Provider errors, missing receipts, native handoff truthfulness and grant/policy revocation.
- Retrieval account/compartment scope, deletion invalidation and context-budget omissions.
- Prompt-injection sender text, missing commercial decisions and self-identity handling.
- Publisher content schema, byte limits, loopback binding, invalid port and unsigned manifest truthfulness.
- Bounded local intelligence proposal validation, scope separation, cancellation, model reuse and memory pressure.
- Native exact action approval, attachment/source binding, expiry, clock rollback, ambiguous outcomes, handoff truthfulness and restart recovery.
- Native persistence transaction boundary and explicit not-production-qualified in-memory repository.

### Not yet executed

- No live provider, account, OAuth flow or real outbound message.
- Runtime SQLCipher/Keystore persistence, key invalidation, migration and restart acceptance was blocked because `where adb` and `where emulator` found no Android SDK runtime tools or connected device on this Windows host. The implementation was compile-verified and the APK package was inspected, but no runtime result is claimed.
- No Playwright/UI automation. The browser bridge was installed but Edge extension connection was not available.
- No performance, memory, battery, thermal or three-device qualification measurements.
- No iOS build, because this environment is Windows without Xcode.
