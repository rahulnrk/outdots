# Delivery Accounting Correctness — Test Report

**Prior commit:** `6622327`
**Branch:** `master`
**Date:** 2026-10-09
**Device:** Pixel 10 Pro · serial `56270DLCH000KZ` · Android (debug build, app data preserved)

---

## 1. Bugs Fixed (prior session, commit 6622327)

| # | Location | Bug | Fix |
|---|----------|-----|-----|
| 1 | `NotificationDeduplicator.evaluate()` | Cache-before-write: entry added to dedup cache before DB write succeeded. Failed writes silently blocked retries. | `evaluate()` no longer caches. `confirmPersisted()` must be called after a successful write only. |
| 2 | `ObservationIngestCoordinator.drainAndProcessAll()` | Cursor count was `1` regardless of how many items were processed. 5 items from same package incorrectly checkpointed `processedCount=1`. | Per-source cursor accumulator increments by 1 per processed item before building merged cursor. |
| 3 | `ObservationIngestCoordinator.processNext()` | `DbCommitted` stage recorded before checking `PersistResult`. Failed write still logged `DbCommitted`. | Moved `DbCommitted` recording to after `persistResult.isError` check. |
| 4 | `SqlCipherObservationStore` | `persistBatchWithCursors()`, `persistObservationWithCursor()`, and `getObservationByNotificationKey()` were missing, forcing non-transactional callers. | Added all three methods using SQLite transactions. Extracted row helpers to remove 100+ lines of duplication. |
| 5 | `AndroidManifest.xml` (release) | `SyntheticNotificationReceiver` and `QUERY_DELIVERY_ACCOUNTING` were registered in the release manifest. | Both moved to `androidApp/src/debug/AndroidManifest.xml`. |

---

## 2. Pipeline Hardening (this session)

### Gap 1 — Correlation ID threading
- `NotificationRawPayload` carries optional `correlationId: String?`.
- `SyntheticNotificationReceiver` generates a UUID per broadcast (`corr_{id}_{uuid}`), embeds it as `mini_correlation_id` in notification extras, and records `SourcePublished` under that UUID only.
- `MiniNotificationListenerService` reads `mini_correlation_id` from extras, passes it as `payload.correlationId`, and records `ListenerReceived` under the **correlation ID when present, otherwise under the OS key** — never both. This was the dual-bucket inflation bug fixed in this session.
- `ObservationIngestCoordinator` uses `val lk = payload.correlationId ?: payload.key` for all ledger writes.

### Gap 2 — Honest accounting in this document
- `delivered=2` from the prior device run was correct in value, but the prior version of this report did not explain the mismatch: `SourcePublished` was recorded under a synthetic key (`app.mini.hub_{id}_synthetic_test`) while later stages were recorded under the OS sbn.key, so only 2 of 4 events matched correlation buckets. Gap 1 resolved this.
- This document clarifies:
  - **Operation count:** number of `processNext()` calls that returned non-Empty.
  - **Distinct notification key count:** number of unique `notificationKey` strings ever persisted or removed.
  - The two are equal only when every operation targets a distinct key.
- Latency percentiles (p50/p95/p99) use monotonic elapsed time (Gap 6). A sample of n=2 is not statistically meaningful; n≥20 is required for any production-quality interpretation.

### Gap 3 — Bounded automatic retry
- `ObservationIngestCoordinator` accepts `maxWriteRetries: Int = 2`.
- Both Insert and Update branches retry up to `maxWriteRetries` times on `PersistResult.Error`, recording an `Error` stage per attempt (`details = "attempt N retrying"`).
- `confirmPersisted()` is called only after a successful write.
- After exhausting retries: returns `IngestProcessResult.Error`, does not cache in the deduplicator, so the item remains retryable by re-enqueue.

### Gap 4 — Genuine same-batch multi-op tests (tests 15 and 16)

**Test 15 — `testMultiOpBatchPreservesOrderAndFinalState`:**
- Enqueues 5 operations (Insert, Update, Duplicate, distinct Insert, Remove) for two notification keys **before** any `drainAndProcessAll()` call.
- A single `drainAndProcessAll(10)` must report `processed=5`.
- Intra-batch dedup is resolved by the `resolveBatchAction()` helper in `ObservationIngestCoordinator`: Insert, Update, and Duplicate are tracked in `batchPending` before the committed deduplicator is consulted; Remove immediately removes the key from `batchPending` and writes removal to the store.
- Final DB state: key A absent from `getObservationByNotificationKey()` (removed), key B present with `isRemoved=false`.
- Ledger for key A: `DeduplicatedInsert`, `DeduplicatedUpdate`, `DeduplicatedDrop`, `DeduplicatedRemove`, and `DbCommitted` all present.

**Test 16 — `testThreeUpdatesInOneBatchProduceCorrectFinalRevision`:**
- Enqueues Insert + 2 Updates for the same key in a single batch (no `processNext()` between them).
- `drainAndProcessAll(10)` must report `processed=3`.
- The batch collapses the three operations to one slot: `store.recoverFromRestart().totalObservations == 1`.
- Readback: `isRemoved=false`, `revision=3` (Insert sets 1, each Update increments by 1 via intra-batch revision tracking).
- Ledger: `DeduplicatedInsert`, `DeduplicatedUpdate`, `DbCommitted`, `ReadbackVerified` all present.

> **What changed from the prior session:** Tests 15 and 16 previously used sequential `processNext()` calls to warm the deduplicator between operations and called `drainAndProcessAll()` only for the last two items. Those were not genuine batch tests. Both have been rewritten to use a single `drainAndProcessAll()` call for all operations.

### Gap 5 — Full DB readback with exact content verification (test 17)

**Test 17 — `testFullDbReadbackVerifiesEveryExpectedRecord`:**
- 3 separate `drainAndProcessAll()` batches: Insert 3 keys, Update key A, Remove key C.
- Verifies `notificationKey`, `isRemoved`, `revision` **and `textFragments` content** for every expected record.
- key A: `textFragments` must contain `"Updated A message"` after update, `revision=2`.
- key B: `textFragments` must contain `"Hello from B"`, `revision=1`.
- key C: `getObservationByNotificationKey("verify_C")` must return `null` (removed from readback).
- `store.recoverFromRestart().totalObservations == 3` (store retains removed records; readback hides them).

### Gap 6 — Monotonic clock for latency
- Added `expect fun monotonicMillis(): Long` to `Time.kt`.
- Android actual: `android.os.SystemClock.elapsedRealtime()`.
- JVM actual: `System.nanoTime() / 1_000_000L`.
- iOS actual: `NSProcessInfo.processInfo.systemUptime * 1000`.
- `record()` accepts optional `monotonicMs: Long = monotonicMillis()` so tests can supply controlled values.
- `generateReport()` uses `monotonicMs` for all latency deltas.

### Gap 7 — Atomic batch persistence and batch retry
- `ObservationStore.persistAtomicBatch()` persists observations, removals, and source cursors as one unit.
- The SQLCipher implementation uses one SQLite transaction. The in-memory test store snapshots observations, removals, and cursors and restores all three on failure.
- Observations are applied before removals, so an insert/update followed by a remove for the same key ends in the removed state while remaining atomic.
- `drainAndProcessAll()` retries the complete atomic batch up to `maxWriteRetries`; a retry never partially replays only one category of write.
- `testAtomicBatchRollbackPreservesAlreadyPersistedNotification` verifies rollback against an already-persisted active record and an unchanged cursor.
- `testBatchWriteRetriesAutomaticallyWithoutManualReenqueue` injects one transient failure and verifies automatic recovery, final rows, and `DbCommitted` without manually re-enqueuing.
- `testIdenticalContentWithNewerNotificationTimestampIsUpdate` verifies that identical content with a newer notification timestamp is an Update, not a duplicate delivery.

### Additional: Single-correlationId ledger key fix
- `MiniNotificationListenerService.onNotificationPosted()` previously called `ledger.record()` under both the raw OS key and the correlation ID, inflating `totalTrackedEvents` by 2x for every synthetic event.
- Fixed: single `ledger.record()` call using `correlationId ?: osKey`.

### Additional: `ACTION_CANCEL_SYNTHETIC` and `ACTION_REPOST_SYNTHETIC` (debug only)
- `SyntheticNotificationReceiver` now handles three distinct broadcast actions:

| Action | Intent | CorrelationId prefix | Pipeline outcome |
|--------|--------|----------------------|-----------------|
| `ACTION_POST_SYNTHETIC` | New or updated notification (distinct content) | `corr_{id}_{uuid}` | Insert or Update |
| `ACTION_CANCEL_SYNTHETIC` | Cancel (remove) a previously posted notification | `corr_cancel_{id}_{uuid}` | Removal; records `SourcePublished` in ledger; `notificationManager.cancel()` triggers `onNotificationRemoved` in listener |
| `ACTION_REPOST_SYNTHETIC` | Repost same notifId with **identical content** | `corr_repost_{id}_{uuid}` | Newer actual notification timestamp produces Update; only an identical timestamp and fingerprint produces `DeduplicatedDrop` |
| `ACTION_QUERY_ACCOUNTING` | Emit accounting summary to logcat | — | Read-only |

- `ACTION_CANCEL_SYNTHETIC` records a correlationId before calling `notificationManager.cancel()` and places it in a bounded, debug-only process-local side channel keyed by package, tag, and notification ID. `onNotificationRemoved` consumes that ID because Android does not preserve notification extras in removal callbacks. The side channel is capped at 64 pending cancellations.
- The final Pixel run observed the cancellation callback under the same operation accounting bucket: `SourcePublished=5` and `ListenerReceived=5` for five operations.
- All synthetic actions, including `ACTION_QUERY_ACCOUNTING` and `ACTION_DUMP_SYNTHETIC_STATE`, are registered in `androidApp/src/debug/AndroidManifest.xml` only.

---

## 3. Automated Test Results

### 3a. Shared JVM Tests (`./gradlew :shared:jvmTest`)

Current session results:

| Suite | Tests | Failures | Skipped |
|-------|-------|----------|---------|
| `DeliveryCorrectnessTest` | **20** | 0 | 0 |
| `DeliveryAccountingLedgerTest` | 1 | 0 | 0 |
| `ObservationPipelineTest` | **11** | 0 | 0 |
| `IntelligenceTest` | 10 | 0 | 0 |
| `ActionCoreTest` | 7 | 0 | 0 |
| `AdaptiveStyleEngineTest` | 4 | 0 | 0 |
| `ScopedRetrievalEngineTest` | 2 | 0 | 0 |
| `ObservationStoreMigrationTest` | 2 | 0 | 0 |
| `ActionStoreTest` | 3 | 0 | 0 |
| `ScaffoldSmokeTest` | 1 | 0 | 0 |
| **All shared** | **61** | **0** | **0** |

Run command: `./gradlew :shared:jvmTest`
Exit code: 0, BUILD SUCCESSFUL.

### 3b. Android unit tests (`./gradlew :androidApp:testDebugUnitTest`)

Exit code: 0, BUILD SUCCESSFUL (this session).

### 3c. Web checks

- `npm run typecheck` completed successfully for `@mini/domain`, `@mini/publisher`, and `@mini/hub`.
- `npm run build` completed successfully for all three workspaces, including the Vite production build.

---

## 4. Operation Counts vs Distinct Key Counts

| Metric | Definition |
|--------|-----------|
| `totalTrackedEvents` | Number of distinct correlation IDs (or OS keys when no correlationId) for which at least one stage was recorded |
| `fullyDeliveredCount` | Number of those IDs that reached `DbCommitted` or `DeduplicatedDrop` |
| `droppedCount` | Count of `QueueDropped` stage events |
| `p50/p95/p99` | Percentile of `(monotonicMs(DbCommitted) − monotonicMs(SourcePublished))` across fully-delivered events |

These are **not** equivalent to:
- Number of `processNext()` calls (includes duplicates, errors, removes)
- Number of distinct `notificationKey` strings in the store (includes removed records)
- Number of DB rows written (updates overwrite; removals set `isRemoved=true`)

---

## 5. Prior Device Run Interpretation (from commit 6622327)

```
ACCOUNTING_SUMMARY: total=4, delivered=2, dropped=0, p50=153.0ms, p95=153.0ms, p99=153.0ms
```

| Field | Value | Honest interpretation |
|-------|-------|-----------------------|
| `total=4` | 4 distinct correlation or OS keys had at least one stage recorded | Not 4 distinct notification keys |
| `delivered=2` | 2 of those 4 reached `DbCommitted` or `DeduplicatedDrop` | The other 2 had `SourcePublished` under a synthetic key that did not match the OS listener key (pre-Gap-1 dual-key bug) |
| `dropped=0` | No queue-full events | Correct |
| `p50=153ms` | Median latency, n=2 sample | Not statistically meaningful (n<20) |

---

## 6. Final Pixel Lifecycle Acceptance

**Target:** Pixel 10 Pro, serial `56270DLCH000KZ`. The debug APK was rebuilt and installed with `adb install -r`. App data and encryption keys were preserved. No `pm clear`, uninstall, database deletion, or emulator command was used.

The final controlled run used shell-safe IDs 9921 and 9922 and five operations: POST 9921 with `Lifecycle_v1`, POST 9921 with `Lifecycle_v2`, REPOST 9921 with identical `Lifecycle_v2`, POST 9922 with `Distinct_v1`, and CANCEL 9921. The repost created a newer Android notification timestamp, so it was classified as an Update rather than a duplicate drop.

Observed accounting summary:

```
ACCOUNTING_SUMMARY: total=5, delivered=5, dropped=0,
stages=SourcePublished=5,ListenerReceived=5,QueueAdmitted=5,
Normalized=5,DeduplicatedInsert=2,DeduplicatedUpdate=2,
DeduplicatedRemove=1,DbCommitted=5,ReadbackVerified=4
```

`p50=402ms`, `p95=561ms`, and `p99=561ms` were also emitted for the five-event sample. These values are recorded for reproducibility only and are not performance claims.

| Metric | Observed value | Meaning |
|--------|----------------|---------|
| Operation count | **5** | Four posts plus one cancellation broadcast |
| Distinct notification key count | **2** | OS keys for notification IDs 9921 and 9922 |
| Ledger tracked events | **5** | One correlation ID per operation, with no raw-key duplicate bucket |
| Fully delivered | **5** | Every operation reached `DbCommitted` or its terminal removal path |
| Queue dropped | **0** | No queue admission loss |

Exact database readback before restart:

| Notification ID | Exact notification key | Revision | Removed | Exact text |
|-----------------|------------------------|----------|---------|-------------|
| 9921 | `0|app.mini.hub|9921|synthetic_test|10381` | 3 | `true` | `Lifecycle_v2` |
| 9922 | `0|app.mini.hub|9922|synthetic_test|10381` | 1 | `false` | `Distinct_v1` |

After `am force-stop app.mini.hub`, both records were read again from the preserved SQLCipher store. The exact keys, revisions, removal flags, post times, and text values were unchanged:

- 9921: revision 3, removed `true`, postTime `1791507173800`, text `Lifecycle_v2`.
- 9922: revision 1, removed `false`, postTime `1791507176377`, text `Distinct_v1`.

Unverified or intentionally limited stages:

- The ledger emitted `ReadbackVerified=4`, not 5, because the removal path does not emit an observation readback stage. The final debug database dump independently verified the removed 9921 row and active 9922 row.
- No `DeduplicatedDrop` was expected or observed in this run. The identical-content repost had a newer actual notification timestamp and correctly produced `DeduplicatedUpdate`.
- The five-event percentile sample is too small for a latency claim. The Kotlin Multiplatform and AGP compatibility warning remains informational: AGP 8.5.1 is newer than the Kotlin plugin's tested maximum of 8.2.

---

## 7. Known Limitations

| # | Limitation | Severity | Mitigation |
|---|-----------|----------|------------|
| L1 | **p50/p95/p99 sample size:** In test runs with n<20 events, percentile values are single data points, not distributions. Do not use as performance claims. | Low | Run 100+ event soak tests for production-quality percentiles. |
| L2 | **`ReadbackVerified` sampled in batch mode:** `drainAndProcessAll()` verifies only a subset of observations, not every item. | Low | Increase sample rate in a future pass if needed. |
| L3 | **No cross-process restart recovery test.** Force-stop during in-flight write is untested. The deduplicator is in-memory only; on restart a previously-committed observation re-evaluates as Insert (which will succeed via DB unique-key upsert). | Medium | Add restart test; consider persisting deduplicator state. |
| L4 | **iOS and JVM lack SqlCipher store.** `InMemoryObservationStore` satisfies the interface for tests only. | Low (scope) | iOS implementation tracked separately. |
| L5 | **Bounded retry does not persist across process restarts.** Failed items that exceed `maxWriteRetries` are dropped from the in-memory queue on restart. | Medium | Persist retry queue to SQLite or recover from cursor checkpoint on restart. |
| L6 | **Cancellation correlation side channel is process-local and bounded.** A delayed callback after process death or more than 64 pending cancellations cannot recover its correlationId. | Low (debug only) | Keep the mechanism debug-only, bounded, and report callback coverage from the accounting summary. |

---

## 8. Files Changed (this session)

```
androidApp/src/debug/AndroidManifest.xml                              (synthetic actions, accounting query, readback)
androidApp/src/main/kotlin/.../MiniNotificationListenerService.kt     (single ledger key: correlationId ?? osKey)
androidApp/src/main/kotlin/.../SyntheticNotificationReceiver.kt       (cancel side channel, repost semantics, accounting/readback debug commands)
shared/src/commonMain/kotlin/.../ObservationModels.kt                 (correlationId field)
shared/src/commonMain/kotlin/.../ObservationIngestCoordinator.kt      (atomic batch, maxWriteRetries, lk key, intra-batch dedup)
shared/src/commonMain/kotlin/.../ObservationStore.kt                   (atomic batch and exact removed-row readback interface)
shared/src/androidMain/kotlin/.../SqlCipherObservationStore.android.kt (SQLite transaction and exact removed-row readback)
shared/src/commonMain/kotlin/.../Time.kt                              (monotonicMillis expect)
shared/src/commonMain/kotlin/.../DeliveryAccountingLedger.kt          (monotonicMs param)
shared/src/commonMain/kotlin/.../ObservationPipeline.kt               (monotonicMs in StageEvent)
shared/src/androidMain/kotlin/.../Time.android.kt                     (elapsedRealtime actual)
shared/src/jvmMain/kotlin/.../Time.jvm.kt                             (nanoTime actual)
shared/src/iosMain/kotlin/.../Time.ios.kt                             (systemUptime actual)
shared/src/commonTest/kotlin/.../DeliveryCorrectnessTest.kt           (tests 15-18 rewritten: genuine drainAndProcessAll, exact content readback)
shared/src/commonTest/kotlin/.../DeliveryAccountingLedgerTest.kt      (recordAt controlled-time helper)
```
