# Pixel 7 Pro stabilization checkpoint — 17 October 2026

Physical device: Pixel 7 Pro, Android 16, API 36. This tests an older hardware generation with the current OS, not an older Android API level. MINI was absent at the initial package check. Both APKs were installed with `adb install -r`; no uninstall, app-data clear, live send, owner-gate change or user-content export occurred.

## Fresh evidence

- `:androidApp:assembleDebug` and `:androidApp:assembleDebugAndroidTest`: passed after correcting instrumentation compilation errors.
- Explicitly selected physical-device instrumentation: **3 tests passed**, 26.492 seconds total. This is the duration of the test batch, not app latency.
- `adb shell am start -W -n app.mini.hub/.android.MainActivity`: `Status: ok`, `LaunchState: COLD`, `TotalTime: 528 ms`, `WaitTime: 530 ms`. This is one Android Activity launch sample. It does not measure inbox readiness, P95, model latency or StrictMode compliance.
- `git diff --check`: passed.
- Host suites were not rerun for these instrumentation-only edits. Earlier reported totals (265 shared, 119 Android unit, 59 TypeScript) are separate historical evidence.

## What passed

1. `app.mini.hub.android.persistence.AndroidSqlCipherMigrationTest#encryptedDatabaseV6ToV7MigrationInterruptedRollbackAndCredentials`
   - Uses a uniquely named synthetic SQLCipher database with the production Keystore envelope. Reconstructs populated v6 observation, summary, cursor and FTS tables.
   - Applies actual shared v7 schema changes and version advancement in a transaction, forces a SQL failure, rolls back, and checks that the version and new columns reverted. Then runs the production store's successful migration.
   - Checks observation content, legacy unproven metadata, summary, scoped lookups, search, version 7 and two actual handle-close/reopen cycles.
   - Separately reopens the real Android Keystore token store and connected-account store, using prefixed synthetic preferences; checks credential/account preservation and absent cross-account lookups. These are not in-memory stores.
   - Separately reopens a uniquely named production SQLCipher action store and checks the saved draft/version and empty outbox. This does not prove preservation of populated approvals/outbox entries.
2. `app.mini.hub.android.ui.AndroidComponentIntegrationTest#syntheticComponentIntegrationStartupSwitchingDraftLoadingSearchFailureAndExpiry`
   - Uses synthetic memory stores and deterministic queued work on the Android main Looper. Checks typing during loading, sibling-draft preservation during rapid selection, real ViewModel search-error handling, and expired approval rejection.
   - The gateway is a fixture; no provider request or external handoff is invoked.
3. `app.mini.hub.android.ui.AndroidRealMainActivityInstrumentationTest#realMainActivityLaunchesAndSurvivesRecreationPreservingDrafts`
   - Launches the actual Activity and waits for asynchronous storage bootstrap. Stores a uniquely scoped synthetic observation, selects it, writes and waits for a persisted draft, recreates the Activity and checks the retained ViewModel, selection and exact draft text.
   - Removes its successful run's synthetic observation/search/summary and draft. No user account is connected and no send is performed.

## Corrections discovered during execution

The incoming instrumentation source did not compile: it referenced an unavailable SQLCipher loader, a private key helper, nonexistent token/account members and incorrect draft constructor arguments. The previously claimed instrumentation compilation was not reproducible. These test references were corrected without exposing production keys or weakening release gates.

The original credential assertions used in-memory stores; the original transaction failed before any migration mutation; the original Activity assertions only checked non-null Activity instances; the original search assertion tested a local `runCatching` expression. Those claims were replaced with the narrower real assertions above.

The initial strengthened device batch failed all three cases: a main-callback synchronization error in the component test, a fixture mismatch with the action store's legacy draft-version normalization, and Activity selection of a conversation absent from storage. The fixtures/synchronization were corrected and the final complete selected batch passed. No production behavior was changed during this checkpoint.

## Reproduction

Build from `native/`:

```powershell
.\gradlew.bat :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
```

Install from repository root with the intended authorized device selected:

```powershell
adb -s <authorized-device> install -r native/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s <authorized-device> install -r native/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s <authorized-device> shell am instrument -w -e class app.mini.hub.android.persistence.AndroidSqlCipherMigrationTest,app.mini.hub.android.ui.AndroidComponentIntegrationTest,app.mini.hub.android.ui.AndroidRealMainActivityInstrumentationTest app.mini.hub.test/androidx.test.runner.AndroidJUnitRunner
```

Run only these reviewed classes, not every instrumentation test; other classes can require live accounts. The real Activity test uses production runtime initialization and scheduling, so this run's absence of connected accounts matters. Before running on a populated installation, assess connector side effects and isolate the test appropriately.

## Acceptance still open

- Migration of an actual preexisting user installation, true OS/process interruption during migration, key-envelope damage/invalidation and populated pending approvals/outbox preservation.
- Notification-listener capture, real message shapes, reused notification keys, restart replay and ambiguous legacy reconciliation on the phone.
- Slow IME editing, handoff crash/recovery, storage-failure screens, StrictMode/traces and repeated measured responsiveness/resource budgets.
- Controlled live-provider refresh rotation, disconnect/reconnect, revoke during review and provider acceptance, subject to the existing authorization and qualification gates.
- WP05 persistence, revocation/lineage lifecycle and integration acceptance. The new matter core alone does not complete WP05.

WP01, overall stabilization acceptance and pilot readiness remain open. No complete-history, delivery, model-quality or overall security qualification follows from these three tests.
