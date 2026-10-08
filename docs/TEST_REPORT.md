# Test report

Only commands actually executed are recorded here. Results were independently rerun by the coordinator.

## 2026-10-09, notification ingestion and encrypted observation persistence checkpoint

Run from `C:\Users\nande\Downloads\Mini` on Windows, JDK 17, Node, Android SDK 34/35.

| Command | Result |
|---|---|
| `npm test` | Pass: 3 files, 59 tests (13 domain, 33 security, 13 publisher) |
| `npm run typecheck` | Pass: domain, publisher, hub |
| `npm run build` | Pass: all workspaces, Vite bundle 192.88 kB JS / 60.40 kB gzip |
| `native\\gradlew.bat test` | Pass: 29 JVM tests in shared module (including 1000-event synthetic stress, deduplication, updates, removals, bounded queue, restart recovery, budgets, action core, and evaluation harness) |
| `native\\gradlew.bat assembleDebug` | Pass: debug APK built (29,692,208 bytes) packaging `libsqlcipher.so` and `MiniNotificationListenerService` |

### Tests added in this phase

- Ingestion of 1,000 synthetic notification events across WhatsApp, Gmail, Instagram, and Slack.
- Exact duplicate notification drop with idempotent preservation of state.
- Preservation of distinct messages sharing identical text content.
- Notification content updates and revision incrementing.
- Notification dismissal and removal handling.
- Group summary vs child message correlation confidence.
- Bounded queue non-blocking overflow defense.
- Crash and restart recovery with durable cursor tracking and uncorrupted state reconciliation.
- Encrypted Android SQLCipher observation store with Keystore key envelope.
- Safe dynamic probe of FTS5 capability in SQLCipher.
- Android `MiniNotificationListenerService` with explicit permission check and lifecycle handling.

### Historical tests passed in prior baseline

- Approval and dispatch race, restart interruption, invalid/rolled-back clock.
- New inbound evidence, tombstones, stale drafts and current source binding.
- Recipient and attachment digest drift, weak digest refusal, account ownership.
- Provider errors, missing receipts, native handoff truthfulness and grant/policy revocation.
- Retrieval account/compartment scope, deletion invalidation and context-budget omissions.
- Prompt-injection sender text, missing commercial decisions and self-identity handling.
- Publisher content schema, byte limits, loopback binding, invalid port and unsigned manifest truthfulness.
- Bounded local intelligence proposal validation, scope separation, cancellation, model reuse and memory pressure.
- Explicit device resource budgets (tiers 1-3), failure recovery circuit breaker and degraded state.
- Quality evaluation harness for summarization, drafting, adversarial prompt injection and budget overflow.
- Host-side benchmark runner emitting structured ledger metrics for latency, load and recovery.
- Native exact action approval, attachment/source binding, expiry, clock rollback, ambiguous outcomes, handoff truthfulness and restart recovery.
- Native persistence transaction boundary and explicit not-production-qualified in-memory repository.

### Not yet executed

- No live provider, account, OAuth flow or real outbound message.
- Runtime SQLCipher/Keystore persistence, key invalidation, migration and restart acceptance was blocked because `where adb` and `where emulator` found no Android SDK runtime tools or connected device on this Windows host. The implementation was compile-verified and the APK package was inspected, but no runtime result is claimed.
- No Playwright/UI automation. The browser bridge was installed but Edge extension connection was not available.
- No performance, memory, battery, thermal or three-device qualification measurements.
- No iOS build, because this environment is Windows without Xcode.
