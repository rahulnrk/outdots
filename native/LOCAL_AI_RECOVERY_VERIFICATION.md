# Local AI cancellation and recovery verification

## Scope

This checkpoint extends `580a9a2`. It keeps one `LocalDraftExperiment` owner, native timeout cancellation and quarantine, foreground denial, encrypted storage, `FLAG_SECURE`, and English-first production scope. The romanized Hindi/Telugu fixtures remain uncommitted in the working tree.

## Requirement and evidence checklist

| Requirement | Evidence | Result |
|---|---|---|
| Explicit Retry local AI control after repeated failures | `LocalDraftExperiment.retryLocalAi`, `InboxViewModel.retryLocalAi`, and `InboxScreen` retry button. `LocalAiLifecycleTest` covers the async worker and lazy next request. | Passed in code and unit tests |
| Recovery is asynchronous, foreground-only, pressure-aware, and lazy | Recovery runs on the experiment worker. Background and pressured requests return blocked results. Recovery resets to `Unloaded` without calling model load. | Passed in unit tests |
| Cancellations do not count as failures | `ResourceSupervisor` cancellation path leaves failure count unchanged. | Passed in shared tests and real-device trace |
| Repeated failures stop ordinary retries | Sticky `Degraded` state blocks ordinary draft requests until explicit recovery. | Passed in shared and Android tests; real device reached paused UI |
| Confirmed asynchronous release signal | `ReleaseTicket` distinguishes queued, released, quarantined, and failed states and rejects blocking await on the Android main thread. | Passed in Android unit tests |
| Evaluator waits before memory-after-release measurement | Evaluator runs on its worker thread, awaits `ReleaseTicket`, reports status and completion time, and only records `memoryAfterReleasePssKb` after confirmed release. | Included in staged implementation and build verified |
| Fresh shared and Android unit tests | `gradlew.bat :shared:jvmTest :androidApp:testDebugUnitTest --rerun-tasks --no-build-cache --no-daemon` | Shared 78/78, Android 32/32, zero failures/errors |
| Fresh debug and release APK builds | `gradlew.bat :androidApp:assembleDebug :androidApp:assembleRelease --rerun-tasks --no-build-cache --no-daemon` | Passed. Release APK is unsigned |

## Exact-commit clean-checkout verification

Commit verified: `441333f6c60fbb1c2bd9d8f3d880736ffdca8a61`.

The commit was cloned with `git clone --no-local --no-hardlinks`, checked out detached, and verified with an empty `git status --short` before the build. In that checkout, the following single fresh command passed:

```text
gradlew.bat :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease --rerun-tasks --no-build-cache --no-daemon
```

Clean-checkout results: shared JVM 78/78, Android unit 32/32, zero failures/errors, debug APK built, and unsigned release APK built. The only machine-local file used for the build was ignored `native/local.properties`.

## Connected-phone evidence

Device: Pixel 10 Pro, serial `56270DLCH000KZ`. The Qwen model was present at the documented path and its device SHA-256 was `e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2`.

- Real MediaPipe generation emitted `GENERATION_STARTED` before cancellation.
- Backgrounding during an active draft emitted `CANCEL_REQUESTED`, `LIFECYCLE background cancelledActiveDraft=true`, and a cancelled draft result with zero failure count.
- The background release completed asynchronously with `RELEASE_DONE ... status=RELEASED`; the observed release completion was about 99 ms in the active-draft run.
- Returning to the foreground and drafting again produced `GENERATION_STARTED` and `DRAFT_END outcome=SUCCESS`; observed generation latency was about 2245 ms.
- Three controlled debug failures reached the real paused UI: `Retry local AI` was enabled and the ordinary draft control was disabled. The UI displayed the repeated-failure explanation.
- Main-thread trace entries on the phone reported `mainThread=true`. Observed lifecycle/UI callback bodies were sub-millisecond in the captured runs, including `onActivityStopped` at 228 to 415 microseconds and UI result/release callbacks below 521 microseconds. Dispatch delay is recorded separately and is not treated as callback work.

The final tap-through of the recovery button and subsequent successful real-device draft could not be completed after the app task was restarted because the synthetic conversation was no longer discoverable in the visible inbox. This is reported as a device-verification gap, not a successful recovery claim. The fake-engine and Android unit tests do cover that path.

Any memory-pressure broadcast used by the debug harness is simulation only. It must not be reported as genuine OS memory pressure. Genuine process restart and real OS pressure verification remain unfinished.

## Remaining gaps

- Final explicit recovery tap-through on the connected phone was blocked by synthetic-conversation discoverability after task restart.
- Genuine Android memory pressure was not induced.
- Genuine process restart and quarantined-engine recovery were not verified on device.
- Release signing and production asynchronous-release reporting remain outside this checkpoint.
- No model replacement, reminders, or broader lifecycle claims are included.
