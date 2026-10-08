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
| `native\\gradlew.bat :shared:jvmTest` | Pass: 7 tests, including scaffold and 6 intelligence safety/lifecycle tests |
| `native\\gradlew.bat :shared:jvmTest :androidApp:assembleDebug` | Pass: JVM tests and debug APK built |

### Tests added in this phase

- Approval and dispatch race, restart interruption, invalid/rolled-back clock.
- New inbound evidence, tombstones, stale drafts and current source binding.
- Recipient and attachment digest drift, weak digest refusal, account ownership.
- Provider errors, missing receipts, native handoff truthfulness and grant/policy revocation.
- Retrieval account/compartment scope, deletion invalidation and context-budget omissions.
- Prompt-injection sender text, missing commercial decisions and self-identity handling.
- Publisher content schema, byte limits, loopback binding, invalid port and unsigned manifest truthfulness.
- Bounded local intelligence proposal validation, scope separation, cancellation, model reuse and memory pressure.

### Not yet executed

- No live provider, account, OAuth flow or real outbound message.
- No SQLCipher/Keystore persistence or restart test on an Android emulator/device.
- No Playwright/UI automation. The browser bridge was installed but Edge extension connection was not available.
- No performance, memory, battery, thermal or three-device qualification measurements.
- No iOS build, because this environment is Windows without Xcode.
