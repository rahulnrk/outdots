# Test report

Only commands actually executed are recorded here. Results were independently rerun by the coordinator.

## 2026-10-09, native Android Compose inbox and UI review checkpoint

Run from `C:\Users\nande\Downloads\Mini` on Windows, JDK 17, Node, Android SDK 34/35.

| Command | Result |
|---|---|
| `npm test` | Pass: 3 files, 59 tests (13 domain, 33 security, 13 publisher) |
| `npm run typecheck` | Pass: domain, publisher, hub |
| `npm run build` | Pass: all workspaces, Vite bundle 192.88 kB JS / 60.40 kB gzip |
| `native\\gradlew.bat test` | Pass: 37 tests (35 JVM in shared, 2 Android unit tests in androidApp) |
| `native\\gradlew.bat assembleDebug` | Pass: debug APK built (29,720,000+ bytes) packaging Jetpack Compose UI, `MiniNotificationListenerService`, and `libsqlcipher.so` |

### Tests added in this phase

- Android Jetpack Compose `InboxScreen` with conversation grouping, unread badges, and honest coverage warning cards.
- User permission flow banner and notification listener connection status indicator.
- `ConversationDetailView` with message timeline, OS truncation indicators, and style profile summaries.
- `ExactActionReviewDialog` enforcing user-gated review and payload binding before dispatch.
- `AndroidNativeHandoffLauncher` with system `Intent.ACTION_SEND` chooser and truthful `OutboxState.HandoffOpened` recording.
- `InboxViewModelTest` verifying conversation selection, draft preparation, approval transaction, and handoff execution.
- Adaptive style recipient confidence threshold (0.40 minimum) preventing premature overfitting on cold contacts.

- Scoped conversation retrieval with token budget bounds and honest context omission warnings.
- Compartment isolation and evidence reference linking for context bundles.
- Adaptive style intelligence with explicit opt-in consent gating and per-category permissions.
- Rejection of inbound messages and unedited AI drafts from teaching user style.
- Prompt injection defense: malicious instruction text is treated as data, preventing trait corruption.
- Sensitive number redaction (card numbers, PINs, secrets) from style example snippets.
- Immediate profile purging on consent revocation.
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
