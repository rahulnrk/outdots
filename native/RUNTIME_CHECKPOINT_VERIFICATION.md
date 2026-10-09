# Runtime integration checkpoint: verification record

Verified commit: `580a9a2b167554ef1cda03de2c0ab7e6cc8bef60` (parent `da8cc59`).

## Why this commit exists

`da8cc59` called `MediaPipeInferenceEngine.generationTimeoutMillis`, which existed only in
uncommitted work, so that commit did not build on its own. `580a9a2` adds the minimal runtime chain:

| File | Included content |
|------|------------------|
| `MediaPipeInferenceEngine.kt` | One `LlmInferenceSession` per task, `generationTimeoutMillis`, native `cancelGenerateResponseAsync` on cancel/timeout, drain before close, `unsafeToReuse` quarantine when native generation does not stop, single active task guard, `NativeGenerationWait` |
| `LocalDraftEvaluationReceiver.kt` (debug harness) | Uses the `MiniHubRuntime` single owner, `case_id` / `timeout_ms` / `verify_timeout_recovery` extras, `evaluateWithTimeout` |
| `LocalDraftRuntimeTest.kt` | Native stop on timeout, quarantine detection, one serial worker shared by UI and evaluator, cancellation before and during cold load |

Left out on purpose (still uncommitted in the working tree): the language-scope research hunks in
the receiver (romanized Hindi/Telugu cases 12-17, `english_only`, `evaluationScope`),
`README.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/MVP_LANGUAGE_SCOPE.md`,
`native/DRAFT_USEFULNESS_REVIEW.md`, `native/review-artifacts/*`, `scripts/model-diagnostics/`.

## Invariants and their checks

| Invariant | Check |
|-----------|-------|
| Native timeout cancellation | `timeoutRequestsNativeStopAndWaitsForTerminalCallback`, `nonStoppingRuntimeRemainsNonTerminalForQuarantine`, `completedGenerationDoesNotRequestCancellation` |
| Single-owner inference | `blockingEvaluatorAndUiDraftShareOneSerialWorker` (max concurrency 1); receiver uses `MiniHubRuntime.localDraftExperiment` |
| Foreground denial | `initialBackgroundRequestNeverLoadsOrRunsModel`, `backgroundRecoveryStaysBlocked` |
| Lazy recovery | `becomingForegroundDoesNotLoadModel`, `explicitRecoveryDoesNotLoadModel`, `nextRequestAfterRecoverySucceeds` |
| Repeated-failure protection | `repeatedFailuresBlockSubsequentDrafts`, `pressuredRecoveryWhileDegradedStaysBlocked`, shared `repeatedEngineFailuresTriggerDegradedStateAndRecovery` |

These are JVM unit tests with fake engines or futures. They do not run MediaPipe or a device.

## Commands (run from `native/`)

```
gradlew.bat :shared:jvmTest :androidApp:testDebugUnitTest --rerun-tasks --no-build-cache --no-daemon
gradlew.bat :androidApp:assembleDebug :androidApp:assembleRelease --no-build-cache --no-daemon
```

## Results

| Location | Shared JVM tests | Android unit tests | Debug APK | Release APK |
|----------|------------------|--------------------|-----------|-------------|
| Working tree (with uncommitted files present) | 75/75 passed | 19/19 passed | built | built (unsigned) |
| Clean clone at `580a9a2` | 75/75 passed | 19/19 passed | built, 84,617,821 bytes | built unsigned, 82,197,115 bytes |

Android breakdown: ForegroundRecoveryTest 7, LocalDraftRuntimeTest 6,
HeadlessNotificationLifecycleTest 3, InboxViewModelTest 3. No skips, failures, or errors.

Clean clone: `git clone --no-local --no-hardlinks` into `%TEMP%\mini-clean-580a9a2`, detached at
`580a9a2`. `git status --short` was empty. The only ignored file added was
`native/local.properties` (`sdk.dir`, machine-local SDK path, git-ignored).

## Not done in this milestone

- Explicit recovery UI (no production caller of `recoverFromDegraded()`).
- Real-device cancellation and recovery with MediaPipe.
- Reporting of asynchronous release completion.
- Genuine process-restart verification (quarantine currently asks the user to restart).
- Release signing (release APK is unsigned).
