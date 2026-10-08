# Test report

Only commands actually executed are recorded here. Results were independently rerun by the coordinator.

## 2026-10-09, Android end-to-end runtime reliability checkpoint

Run from `C:\Users\nande\Downloads\Mini` on Windows, JDK 17, Node, Android SDK 34/35.

| Command | Result |
|---|---|
| `npm test` | Pass: 3 files, 59 tests (13 domain, 33 security, 13 publisher) |
| `npm run typecheck` | Pass: domain, publisher, hub |
| `npm run build` | Pass: all workspaces, Vite bundle 192.88 kB JS / 60.40 kB gzip |
| `native\\gradlew.bat test` | Pass: 42 unit tests (37 JVM in shared, 5 Android unit tests in androidApp) |
| `native\\gradlew.bat assembleDebug` | Pass: debug APK built (29,720,000+ bytes) packaging Jetpack Compose UI, `MiniHubRuntime`, `MiniNotificationListenerService`, `SyntheticNotificationReceiver`, and `libsqlcipher.so` |
| `zipalign -c -P 16 4 androidApp-debug.apk` | Pass: 16 KB page alignment verified on all ELF binaries (`lib/arm64-v8a/libsqlcipher.so` aligned at 7,651,328 bytes % 16,384 = 0) |
| `llvm-readelf.exe -l libsqlcipher.so` | Pass: ELF LOAD segment alignment confirmed at 0x4000 (16,384 bytes) |
| `adb install -r androidApp-debug.apk` on `Pixel 10 Pro` (`56270DLCH000KZ`, Android 16 / API 36, arm64-v8a) | Pass: Streamed install succeeded; 16 KB incompatibility warning completely resolved |
| `adb shell cmd notification allow_listener ...` on Pixel 10 Pro | Pass: Notification listener permission and restricted settings access granted |
| `adb shell am start -n app.mini.hub/.android.MainActivity` on Pixel 10 Pro | Pass: MainActivity launched, Compose UI initialized, arm64 `libsqlcipher.so` loaded, Keystore encryption envelope verified, SQLCipher database keying code 0 |
| `adb shell dumpsys meminfo app.mini.hub` on Pixel 10 Pro | Pass: Live physical device memory footprint = 93.0 MB Total PSS (Java Heap 7.8 MB, Native Heap 8.0 MB, Graphics 33.4 MB), well within Tier 1 budget |
| Controlled live synthetic notification end-to-end test on `Pixel 10 Pro` | Pass: Broadcast `POST_SYNTHETIC_NOTIFICATION` processed via NotificationManager -> `MiniNotificationListenerService` -> `MiniHubRuntime` -> `BoundedIngestQueue` -> `SqlCipherObservationStore` -> Compose `InboxScreen` reactive update |
| Multi-notification background & foreground persistence | Pass: Multiple notifications processed in background, persisted to SQLCipher, and displayed with unread badges on app resume |
| Post-restart notification recovery on Pixel 10 Pro | Pass: After force-stop and cold restart, all 3 synthetic conversation records reloaded from encrypted SQLite |
| `adb install -r androidApp-debug.apk` on `emulator-5554` (API 34 x86_64) | Pass: Streamed install succeeded |
| `adb shell dumpsys meminfo app.mini.hub` on emulator-5554 | Pass: Total PSS = 58.2 MB (Java Heap = 9.4 MB, Native Heap = 14.8 MB) |

### Tests added in this phase

- Resolved human-friendly application names (`X`, `WHOOP`, `WhatsApp`, `Gmail`, `Messenger`, `Facebook`, `Slack`, `Android System`) without raw package IDs.
- Classified notification categories: `DirectMessage`, `GroupMessage`, `SocialUpdate`, `SystemStatus`, `OngoingService`, and `GeneralNotification`.
- Removed synthetic package substitution to prevent attributing test broadcasts to third-party packages.
- Added Compose category badges on inbox conversation cards.
- Unit tests in `ObservationPipelineTest` for app name resolution and category classification.

- Headless background notification ingestion via `MiniHubRuntime` without requiring `MainActivity` to be open.
- Queue overflow gap-tracking in `IngestCursor` and reflection of dropped event counts in `ConversationObservationSummary` coverage warnings.
- Event-driven reactive UI updates via `ObservationUiListener` replacing continuous timer polling.
- Comprehensive `HeadlessNotificationLifecycleTest` verifying background draining, queue backpressure, cursor gap accumulation, and reactive event notifications.
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
- Live Gmail API sync is blocked on Google Cloud OAuth Client ID configuration and consent.
- Playwright browser automation is not configured on this host.
- Thermal throttling measurements require physical hardware testing under continuous load.
- No iOS build, because this environment is Windows without Xcode.
