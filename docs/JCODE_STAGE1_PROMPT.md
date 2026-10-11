# Paste into jcode — MINI Stage 1: Stabilize

You are continuing the existing MINI repository. **Do not rebuild anything. Add no new features.**
This milestone fixes seven defects found in an independent code review, proves each fix with a
test, and stops.

## Read first (and only these)

1. `CLAUDE.md` at the repo root — commands, architecture, conventions. Treat it as binding.
2. `docs/IMPLEMENTATION_STATUS.md` — current status. Keep its "fixture-tested" vs
   "qualified on device/live" distinction in everything you write.
3. The files named in each task below. Use agentgrep to locate symbols; do not re-read the whole repo.

Before editing, report in one short block: `jcode version`, current `git rev-parse HEAD`, whether
`adb devices` shows a phone, and the result of the baseline run:

```
npm test && npm run typecheck
cd native && gradlew.bat :shared:jvmTest :androidApp:testDebugUnitTest --rerun-tasks
```

Expected baseline: 59 TypeScript, 139 shared JVM and 37 Android unit tests passing. If the
baseline differs, stop and report instead of proceeding.

## Rules for every task

- **Failing test first.** Write a test that reproduces the defect and fails, then fix, then show it passing. If a defect cannot be reproduced, say so and do not "fix" it.
- **One task, one commit.** Small diffs. No refactors beyond what the fix needs. No new dependencies in `native/shared` (it is dependency-free by design).
- Keep all existing tests passing. Never weaken or delete a test to get green.
- Logs: local account IDs, states and counts only. Never message content, addresses or tokens.
- **Forbidden:** live sends to any real recipient; recording or clearing gate evidence; touching `GateEvidenceStore` contents; `connectedDebugAndroidTest` (it wipes stored authorizations — use `adb install -r` + `adb shell am instrument`); adding client secrets; cloud inference; editing `packages/`, `apps/` or `CLAUDE.md`; deleting connector code (hide it, see T6).
- A phone result may be claimed only if you ran it on a connected phone in this session. Otherwise write "not device-verified".

## Tasks, in order

### T1 — Gmail incremental sync gets stuck (High)
`native/shared/src/commonMain/kotlin/app/mini/hub/connector/GmailApiConnector.kt`, `syncIncremental`.
When more history exists than one bounded pass may fetch, `nextHistoryId` is set back to
`startHistoryId`. The next pass re-lists the same first messages (now all known), fetches
nothing, and is truncated again — the cursor never advances.

Done when, in `GmailApiConnectorTest` with a fake transport:
- 120 messages added across multiple history pages are all returned exactly once over repeated calls;
- the cursor strictly advances on every pass that reports `moreAvailable`, and the passes terminate;
- no message is skipped when a pass is cut off mid-page, and deletions are still honoured;
- the Android host (`AndroidGmailConnector`) requests a follow-up pass when `moreAvailable` is true instead of waiting 15 minutes.

### T2 — Silent fallback to memory-only storage (High)
`native/androidApp/src/main/kotlin/app/mini/hub/android/MiniHubRuntime.kt`, lines ~106–116.
If SQLCipher fails to open, the runtime silently uses `InMemoryObservationStore` /
`InMemoryActionStore`; data vanishes on restart with no warning.

Done when:
- a storage open failure is surfaced as an explicit runtime state, and the UI shows a blocking, plain-language error (reuse the existing `storageFailure` state and the `UnavailableAttentionStore` pattern);
- no in-memory store is reachable from non-test code paths;
- the notification listener and sync job do not crash-loop in that state — they do nothing and log a count-free state line;
- a unit test forces the failure and asserts all three.

### T3 — Direct send cannot work from the UI (High)
`InboxScreen.kt:287` → `InboxViewModel.approveAndDispatch` runs `actionStore.transaction { actionController.dispatch(...) }`
on the main thread, so the provider HTTP call throws `NetworkOnMainThreadException`, which is
swallowed into `OutcomeUnknown`. The network call also runs inside an open database transaction,
and `ActionController.recoverInterrupted` is never called at startup.

Done when:
- the provider call runs off the main thread and **outside** any database transaction;
- the command's `Dispatching` state and the consumed approval are durably committed **before** the network call starts, and the result is committed after;
- on process start, `recoverInterrupted` runs so a command left in `Dispatching` becomes `OutcomeUnknown` and is never re-sent automatically;
- a second tap while a dispatch is in flight produces no second request (test with a counting fake gateway);
- an expired access token is refreshed through the existing refreshers before the gateway call, and before `AndroidConnectorRegistry.fetchConversation`; a failed refresh is a definite rejection with the existing reconnect message;
- the approval/digest/expiry semantics in `ActionController` are unchanged and `ActionCoreTest` plus the TypeScript `security.test.ts` still pass;
- the UI shows an in-progress state while sending and the existing accepted / rejected / unknown wording afterwards.

Sending stays gated: do not clear `SendQualification`. Verify with fakes only.

### T4 — Background sync stops after reboot (Medium)
`GmailSyncJobService.schedulePeriodic` uses `setPersisted(false)`.
Done when the periodic job is persisted (with the manifest permission that requires), is not
double-scheduled, and a unit or Robolectric-free test covers the scheduling decision. If a phone is
connected: reboot, do not open the app, confirm a sync log line within 30 minutes; otherwise mark
"not device-verified".

### T5 — Database work on the screen thread; draft lost on rotation (Medium)
`InboxViewModel.refresh` reads every conversation's messages on the main thread on each incoming
notification; `MainActivity.onCreate` builds a new `InboxViewModel` on every configuration change.
Done when:
- `refresh` does its store reads off the main thread, applies results on the main thread, and drops stale results (use the existing generation-counter pattern in this file);
- the per-conversation read inside `refresh` no longer runs once per conversation per refresh;
- the view model and any typed or generated draft survive rotation;
- `InboxViewModelTest` covers stale-result dropping; existing tests pass.

### T6 — Hide what is not qualified (Medium)
`AndroidConnectorRegistry.routes()` offers every provider, including the Matrix-homeserver bridge
for WhatsApp / Instagram / LinkedIn, which routes plaintext through a third-party server and is
against product policy.
Done when:
- release builds offer only Gmail, Outlook and notification sources in the Accounts screen;
- Slack, Teams, Google Chat, Telegram and X remain available in debug builds only, clearly labelled "not live-qualified";
- the Matrix bridge route is unreachable from any UI in any build variant; its code and tests stay in the repo;
- a test asserts the release route list.

### T7 — One verification command and honest docs (Low)
- Add `scripts/verify.ps1` that runs the TypeScript tests, typecheck, and both Gradle test tasks, and exits non-zero on any failure. (There is no git remote yet, so no hosted CI.)
- Rewrite `README.md`, `docs/SETUP_AND_RUN.md` and `docs/SECURITY_REVIEW.md` to describe the repository as it is now: native Android app is the product, the TypeScript packages are a reference/demo. Remove claims the code contradicts.
- Update `docs/IMPLEMENTATION_STATUS.md` rows touched by T1–T6.

## How to use jcode for this

- **Coordinator only for T2, T3, T5, T6.** They share `MiniHubRuntime.kt`, `InboxViewModel.kt`, `MainActivity.kt` and `AndroidConnectorRegistry.kt`; do them sequentially yourself.
- **Light swarm, at most two workers,** only for the two file-isolated tasks, started after the baseline:
  - Worker A owns T1: `GmailApiConnector.kt`, `GmailApiConnectorTest.kt`. May not touch `androidApp/`. The coordinator does the small `AndroidGmailConnector` follow-up after merging.
  - Worker B owns T7 docs and `scripts/verify.ps1` only, and finalises status rows after T1–T6 land.
  Give each worker its owned files, forbidden files, the "done when" list and the exact test command. No deep swarm, no recursive spawning.
- A worker's "done" is not evidence. Re-run its tests yourself before committing its work.
- If a provider rate limit interrupts a task, checkpoint which files were touched and resume that task; do not restart the milestone.
- Do not store project decisions in jcode memory as a substitute for the repo docs, and do not treat a compacted summary as test proof.
- No ambient mode, no self-dev, no browser automation for this milestone.

## Stop condition and report

Stop after T7. Do not start search, summaries, model work or any Stage 2 item.

Final report, short:
1. HEAD before and after; one line per commit (task ID, SHA).
2. Test counts now: TypeScript / shared JVM / Android unit — and the new tests added per task.
3. For each task: the failing-then-passing test name, and "device-verified" or "not device-verified".
4. Anything you could not reproduce, could not fix, or chose not to do, with the reason.
5. Open risks you noticed but did not touch (list only; do not fix).
