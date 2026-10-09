# Foreground eligibility and lazy recovery: requirement and evidence checklist

Base: commit 432ed61. Verified with fake-engine JVM unit tests only (no device run).

| # | Requirement | Change | Observable evidence (ForegroundRecoveryTest) |
|---|-------------|--------|-----------------------------------------------|
| 1 | Foreground eligibility starts FALSE | `isForeground = AtomicBoolean(false)` | `initialBackgroundRequestNeverLoadsOrRunsModel`: Refused, loads=0, runs=0 |
| 2 | No automatic `recoverFromFailure()` in `executeDraft` (incl. Degraded branch) | Branch deleted | `repeatedFailuresBlockSubsequentDrafts`: after 3 failures, next draft Failed and runs unchanged |
| 3a | Explicit recovery foreground-only | `recoverFromDegraded()` returns false when not foreground | `backgroundRecoveryStaysBlocked`: false initially and after backgrounding, loads=0, runs=0 |
| 3b | Explicit recovery blocked under memory pressure | returns false when `Pressured` | `pressuredRecoveryWhileDegradedStaysBlocked`: false, next draft Failed "pressured", loads/runs unchanged |
| 3c | Explicit recovery lazy (no model load) | `ResourceSupervisor.recoverFromFailure()` no longer calls `ensureReady()` | `explicitRecoveryDoesNotLoadModel`: loads unchanged after recovery |
| T2 | Becoming foreground does not load | (existing lazy resume) | `becomingForegroundDoesNotLoadModel`: worker drained, loads=0 |
| T5 | Next valid request generates | lazy load on submit | `nextRequestAfterRecoverySucceeds`: Success, exactly one new load |

Recovery runs on the serial worker, so it sees any queued release or pressure transition first.

## Test runs (fresh, cleaned test tasks)

- `:shared:jvmTest`: 75/75 passed, including IntelligenceTest 10/10 (recovery assertion updated to Unloaded-then-lazy-load).
- `:androidApp:testDebugUnitTest`: 19/19 passed, including ForegroundRecoveryTest 7/7.

## Remaining gaps

- No on-device verification of these behaviors.
- Processes started without an Activity (LocalDraftEvaluationReceiver, notification listener) now stay background-ineligible, so the debug evaluation harness will refuse drafts unless an Activity is started. Not changed here.
- No production caller invokes `recoverFromDegraded()` yet. Degraded is permanent until something calls it, or until `release()`/backgrounding resets the supervisor.
- LocalDraftRuntimeTest (untracked, depends on uncommitted MediaPipe work) got `setForeground(true)` added but is not part of this commit.
