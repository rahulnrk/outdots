# Phase 3A Acceptance Report: Conversation, Composer and "Why this draft?" Evidence

**Device**: Google Pixel 10 Pro, Android 16 (debug build, `adb install -r`; no uninstall, reset, or data wipe)
**Date**: October 15, 2026
**Status**: Phase 3A stays open for review. Phase 3B is on hold.

This report keeps four kinds of evidence separate. Each is labelled where it is used.

| Label | Meaning |
|:---|:---|
| **JVM unit test** | Runs on the build machine with a test-only fake engine (`TestInferenceEngine`, `src/test` only). Does not run on the phone. |
| **Static fixture** | Production Compose screen rendered by `PreviewExportActivity` (debug only) from fixed synthetic data. Shows layout only. No interaction. |
| **Device workflow** | Runs on the phone through `Phase3AWorkflowHarnessActivity` (debug only, details in §3). Uses production `InboxViewModel`, production screens, `LocalDraftExperiment`, the Android main looper and a real `SqlCipherActionStore`. Inference is a **fake gated engine**. |
| **Real model** | MediaPipe local model generating a draft. **Not verified in this report.** |

---

## 1. Source corrections in this pass

1. **2x evidence layout** (`WhyThisDraftView.kt`): sender/time and "Open original message" were side by side in a `Row`, and at 2x text the link was squeezed into a narrow column. They are now stacked as full-width rows, and the link has a 48 dp minimum touch height.
2. **Active version identity across restart** (`ConversationDraft.activeVersionId`, new `active_version_id` column in SQLCipher and JDBC stores, additive `ALTER TABLE`): the selected version is now restored by its stable ID. Before this, restore matched by body text, which picks the wrong version when two versions have identical text. Found during the device restart check (§3.6).
3. **No stale evidence on a manual version** (`InboxViewModel.applyGenerationResult`): if the user edited during generation while an older AI version was active, the manual version kept the older AI provenance. It now clears provenance and the AI baseline. Found by the device harness (§3.1).
4. **Offered result stays reachable** (`ChatUi.kt` composer): when generation finished, the AI panel collapsed, so after a cleared editor the only route to the offered result was hidden. Now the panel stays open, and a single offered version shows its chip when it is not in the editor. Found by the interactive keyboard check (§3.2).
5. **Debug-only harness** (`src/debug/.../Phase3AWorkflowHarnessActivity.kt`, debug manifest only).

The release APK was checked with a dex string scan: it contains neither `Phase3AWorkflowHarness` nor `PreviewExportActivity` (result `0`). The production `MainActivity`, FLAG_SECURE, release gates and send paths are unchanged.

---

## 2. Test results

| Suite | Command | Result |
|:---|:---|:---|
| Shared JVM | `:shared:jvmTest` | **222 tests, 0 failures** |
| Android unit (JVM) | `:androidApp:testDebugUnitTest` | **115 tests, 0 failures** |

New Android unit tests in this pass:
- `reopenedDraft_restoresActiveVersionByStableIdEvenWhenTextsMatch`
- `editsDuringGenerationOnRestoredAiDraft_clearStaleProvenanceForManualVersion`

`JdbcActionStoreTest` checks **plain SQLite** persistence and migration on the JVM. It does not check encryption. Android SQLCipher restoration is recorded separately in §3.6.

---

## 3. Device workflow results (Pixel 10 Pro)

**Harness**: `app.mini.hub/.android.ui.Phase3AWorkflowHarnessActivity`, started with `adb shell am start -n app.mini.hub/.android.ui.Phase3AWorkflowHarnessActivity --es step <step>`. Results come from logcat tag `MiniPhase3AHarness`.

**Isolation and safety**:
- The harness uses a separate SQLCipher file, `phase3a-harness.db`, opened with the same Keystore-wrapped key code. It never opens the production database `mini-hub-actions.db`.
- Accounts are synthetic: `gmail|harness-a` and `whatsapp|harness-b`.
- No account store or gateway is supplied, so the harness has no provider send path.
- Logs contain only counts, booleans, synthetic IDs, synthetic instruction labels and SHA-256 prefixes.
- The harness DB was emptied at the end (`CLEANUP remaining=0`).

**Inference**: `GatedFakeEngine` (fake). It blocks inside `run()` until released, so the edit, clear and discard steps genuinely happen while generation is in flight.

| # | Check | Path | Observed result |
|:---|:---|:---|:---|
| 3.1a | Edit during generation | Device workflow (programmatic edit) | `inFlightBeforeEdit=true editorKeptUserText=true versions=2 aiVersions=1 activeIsManual=true activeProvenanceNull=true` |
| 3.1b | Edit during generation on a restored AI draft | Device workflow | First run (before fix 3): `activeProvenanceNull=false`, a **defect**. After the fix: `restoredHadProvenance=true editorKeptUserText=true activeIsManual=true activeProvenanceNull=true` |
| 3.2a | Clear the editor during generation | Device workflow (programmatic clear) | `editorEmpty=true selectedIndex=-1 offeredVersions=1 offeredIsAi=true activeProvenanceNull=true` |
| 3.2b | Clear the editor through the on-screen keyboard (IME open), then late result | Device workflow, interactive (`device-interactive-clear.bat`: tap field, real DEL key events) | `editorBeforeReleaseSha=e3b0c44298fc` (SHA of empty text) and `editorAfterReleaseSha=e3b0c44298fc editorUnchangedByResult=true selectedIndex=-1 versions=1`. Screenshots: `device-keyboard-cleared-in-flight.png`, `device-cleared-after-result.png` (empty editor, "Version 1" chip offered). Tapping the chip loaded the proposal into the editor: `device-offered-version-selected.png`. |
| 3.3 | Discard, then late result | Device workflow | `editorEmpty=true versions=0 provenanceNull=true storedAfterDiscard=false storedAfterLateResult=false` |
| 3.4 | Version selection with matching evidence (two generations, identical text, different instructions) | Device workflow | `count=2 distinctIds=2 sameText=true v0Instruction=formal instruction v1Instruction=concise instruction v0MatchesStored=true v1MatchesStored=true` |
| 3.5 | Account switch drops evidence | Device workflow | Account A: `aHadProvenance=true aEvidenceOpen=true`. After switching to B: `bAccount=whatsapp bEditorEmpty=true bProvenanceNull=true bVersions=0 bEvidenceOpen=false bHighlightNull=true` |
| 3.6 | SQLCipher draft, version and provenance restoration after process termination | Device workflow plus process kill | See below |
| 3.7 | Open cited original message | Device workflow, interactive (tap on the real evidence screen) | `evidenceClosed=true highlightSet=true highlightMatchesCited=true citedInTimeline=true`. Screenshots: `device-evidence-interactive.png`, `device-after-open-original.png` (cited card outlined). |
| 3.8 | Manual exact review, no send | Device workflow, interactive (tap "Review reply", then "Cancel") | Open: `dialogOpen=true route=NativeHandoff bodyMatchesEditor=true accountMatches=true sending=false`. After Cancel: `dialogOpen=false outboxStatusNull=true`. The top resumed activity stayed on the harness (no Gmail handoff). Screenshot: `device-review-dialog.png`. Approve was never tapped. |

### 3.6 SQLCipher restart restoration

Sequence: `seed` step, then `run-as app.mini.hub kill <pid>` (pid 13969), then `am force-stop app.mini.hub`, then relaunch with the `verify` step (new pid 14496). The verify step opens a fresh `SqlCipherActionStore` and a fresh `InboxViewModel`.

| Field | Before termination (pid 13969) | After relaunch (pid 14496) | Match |
|:---|:---|:---|:---|
| Body SHA | `9df0e8f7dd9f` | `9df0e8f7dd9f` | yes |
| Version count | 2 | 2 | yes |
| Version IDs | `ai-1792070386534-b30153` and `ai-1792070386639-ce06c9` | same | yes |
| Active version | index 0, `ai-1792070386534-b30153` | index 0, `ai-1792070386534-b30153` | yes |
| Active provenance SHA | `e6ee4a2f14d2` (instruction label `seed formal`) | `e6ee4a2f14d2` (`seed formal`) | yes |
| Per-version provenance SHAs | `e6ee4a2f14d2` and `922d658f825a` | same | yes |
| AI baseline SHA | `9df0e8f7dd9f` | `9df0e8f7dd9f` | yes |

Two earlier attempts are recorded because they changed the code:
1. The first attempt, before the `activeVersionId` column existed, could not confirm the active version, because restore matched by body text.
2. The relaunch also redelivered the previous launch intent, which ran an `edits` step before `verify` and changed the data. The harness now ignores restored or redelivered intents (log `SKIP_REDELIVERED_INTENT`).

The table above is from the run after both fixes.

### Not verified on device
- **Real-model generation**: no MediaPipe draft was produced or qualified. All device workflows used the fake gated engine.
- **Production `MainActivity` flows**: the harness renders the production `ThreadView`, `WhyThisDraftView` and `ExactActionReviewDialog` composables, but not the production activity, its account store or its gateway.
- **Provider sends**: not attempted, by design.
- **Typed-text edit through the IME**: only clearing was done through the keyboard. Typed edits were checked programmatically through `updateLocalDraftText` (3.1a and 3.1b).

---

## 4. Static fixtures (layout only, no interaction)

Rendered by `PreviewExportActivity` on the Pixel 10 Pro (`capture-phase3a.bat`). These show the layout of fixed synthetic states. They do not show workflow behaviour.

| File | State | Font | Observed in image |
|:---|:---|:---|:---|
| `thread-light.png`, `thread-dark.png` | Thread with AI draft | 1.0x | Account label, email card, composer |
| `thread-large.png`, `thread-xlarge.png` | Thread | 1.3x, 2.0x | Controls wrap, Review reply reachable |
| `thread-generating.png` | Generating | 1.0x | AI panel open, Stop button, generating status |
| `thread-manual.png` | Manual draft, no provenance | 1.0x | No AI chips |
| `evidence-light.png`, `evidence-dark.png`, `evidence-large.png` | Populated evidence | 1.0x, 1.0x, 1.3x | Lock pill, cited message, instruction, previous draft, understanding, style, exclusions |
| `evidence-xlarge.png` | Populated evidence, top | 2.0x | Sender/time on one full-width row and "Open original message" on its own full-width row below. No squeezed column. |
| `evidence-xlarge-scroll2.png`, `evidence-xlarge-scroll3.png` | Populated evidence, lower sections | 2.0x | Instruction, previous draft, understanding, applied style, "Not included" attachment card, and the final "Back to my draft" button, all full width with no clipping |
| `evidence-unavailable.png` | No provenance | 1.0x | "Evidence unavailable" card |

Device workflow screenshots: `device-generating-armed.png`, `device-keyboard-cleared-in-flight.png`, `device-cleared-after-result.png`, `device-offered-version-selected.png`, `device-evidence-interactive.png`, `device-after-open-original.png`, `device-review-dialog.png`. All use synthetic harness data.

---

## 5. Device state after testing

- The notification listener was bound after the final force-stop: `MiniNotificationListenerService (user 0): INotificationListener$Stub$Proxy@a537102`. Notification capture is separate from authenticated provider sync, and this report makes no sync claims.
- Databases present: `mini-hub-actions.db` (production, not opened by the harness) and `phase3a-harness.db` (harness only, emptied).
- No uninstall, reset, permission change or live send was performed.

---

## 6. Scope

- **Phase 3A**: open pending review of this report.
- **Phase 3B**: on hold.
