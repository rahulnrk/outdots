# Phase 3A Acceptance Report: Conversation Details, Authoritative Composer, and Truthful “Why this draft?” Provenance

**Device Under Test**: Google Pixel 10 Pro (Android 16, build `BP2A.250305.020.A2`, serial `56270DLCH000KZ`)  
**Package**: `app.mini.hub` (Debug build, installed via `adb install -r`, preserving all user data, OAuth tokens, and keystore envelopes)  
**Date**: October 15, 2026  
**Status**: Phase 3A bounded corrections and synthetic workflows verified. Phase 3B remains strictly on hold.

---

## 1. Scope and Architecture Delivered

Phase 3A delivers conversation details, authoritative single-body drafting with race and edit preservation, and truthful "Why this draft?" AI input provenance matching Paper & ink design requirements:

### 1.1 Conversation Clarity (`ThreadView.kt`)
- **Account Identity**: Header surfaces the active sending identity and account name (`SourceAppNames · Account`), avoiding confusion across multi-account environments.
- **Message Headers**: Email messages render From, To, Subject, and Date headers with clean layout and expandable message content.
- **Inbound vs Outbound Direction**: Inbound and outbound message bubbles are differentiated with alignment and color styling (outbound labeled "You").
- **Media Attachment Previews**: Renders attachments with file kind, formatted byte size, and open/preview actions.
- **History Coverage**: Coverage warning banners explicitly distinguish full provider sync, partial synced history (`PartialApiSync`), notification-only history (`NotificationOnly`), and load errors with retry actions.
- **Source Highlighting**: Navigating from "Why this draft?" cited message references targets the exact message, auto-expands collapsed cards, and smoothly highlights the message; if outside loaded history, an inline notification banner is shown.

### 1.2 Authoritative Composer with Edit & Race Protection (`InboxViewModel.kt`, `ChatUi.kt`)
- **Single Authoritative Body**: `localDraftText` acts as the single source of truth for manual typing, free instructions, prompt refinement chips ("Shorter", "Warmer", "More polite", "More casual", "Say no politely", "Ask a question", "Try again"), and in-flight cancellation.
- **Preserve Every Edit (Including Cleared Editor)**:
  - Edit revision tracking (`localDraftEditRevision`) records any user modifications made while background generation is in flight.
  - If a user types manual edits during generation, their live text is retained in the editor, and the new AI proposal is stored as an alternate version in `draftVersions`.
  - If a user deletes/clears the entire draft (`""`) during generation, their empty editor is preserved (`localDraftText == ""`), active provenance is cleared, `selectedVersionIndex == -1`, and the incoming AI proposal is offered separately in `draftVersions`.
- **In-Flight Invalidation on Discard**: Clicking "Discard" immediately clears draft text, deletes saved draft from the database, and increments generation token `localDraftGeneration`. Stale background callbacks are discarded and cannot resurrect the discarded draft.
- **Coupled Version Architecture**: Every draft version is stored as a `DraftVersion` structure binding a unique `id`, `text`, `provenance` (`DraftProvenance`), `originalAiText`, and `isAiGenerated`.
- **Preserve Version Identity**: Removed text-only `distinctBy { it.text }`. Generations with identical text but different user instructions or source references maintain stable IDs, separate chip entries, and distinct provenance.
- **Authorship Baseline Protection**: Tracking `originalAiText` ensures unedited AI proposals are never misclassified as user-edited writing during adaptive style learning when switching versions.
- **Account Isolation**: Saved drafts, new email compose drafts, and active provenance strictly validate against `selectedConversation.accountId`. Switching accounts purges in-flight state and clears evidence from the previous account.
- **Send & Review Contract**: "Review reply" preserves exact-review/send contracts without regression.

### 1.3 Truthful Provenance Evidence (`WhyThisDraftView.kt`)
- **Truthful Provenance Model**: `DraftProvenance` records active `accountId`, `accountLabel`, `userInstruction`, `sourceMessageReferences` (message ID, sender hint, timestamp, text snippet), `appliedStyleSummary`, `coverageWarning`, `excludedAttachments`, `previousDraft`, and `understandingNote`.
- **Canonical Account Validation**: Uses canonical `AccountId.key` format (`provider|localId`), ensuring strict account isolation lock while correctly matching fixture accounts.
- **Truthful Authorship Copy**: Prototype claims were removed. When earlier provenance is missing, the view displays: *"Evidence unavailable: earlier generation evidence is no longer available."*
- **Attachment Honesty**: Attachments not read by local AI (such as PDFs or images) are displayed under "Not included" with clear notice: *"Attachment contents were not read by AI for this draft."* Prototype marketing copy claiming the draft does not approve prices was removed.
- **Direct Source Navigation**: Clicking "Open original message ↗" scrolls directly to the cited message in the conversation thread.

### 1.4 Typography & Accessibility Reflow
- Replaced low-contrast `outline` tokens with accessible `onSurfaceVariant` across headers, chips, and timestamps.
- Added responsive layout branching (`fontScale > 1.4f`) so action chips, version selectors, and buttons flow onto additional rows without horizontal truncation at 130% and 200% font sizes.

---

## 2. Test Verification

The test suites are reported separately:

### 2.1 Shared JVM Suite (222 Tests Passing)
Executed via `:shared:jvmTest` (222 tests completed, 0 failures):
- **Plain SQLite Restart Persistence & Schema Migration (`JdbcActionStoreTest.kt`)**:
  - `sqliteRestartRestoration_preservesDraftVersionsAndTruthfulProvenance`: Verified plain SQLite database close and reopen on disk; confirms draft body, version history, and detailed provenance survive process restarts.
  - `schemaMigration_addsMissingColumnsWithoutLosingExistingDrafts`: Verified SQLite schema evolution adds `provenance_json` and `versions_json` to older database tables without data loss.
- **Connector, Attention, Action & Retrieval Engine Suites**: 220 additional tests verifying account isolation, token budget boundaries, idempotency, and state machine contracts.

### 2.2 Android App Unit Suite (113 Tests Passing)
Executed via `:androidApp:testDebugUnitTest` (113 tests completed, 0 failures):
- **Phase 3A Conversation & Composer (`Phase3AConversationComposerTest.kt` - 10 tests)**:
  1. `accountIsolation_retrievalAndProvenanceUseOnlySelectedAccount`: Proves retrieved observations and draft context strictly isolate the selected account.
  2. `productionPath_staleCallbackDiscardsResultAfterSwitchingConversations`: Proves asynchronous generation completing after switching conversations is discarded.
  3. `productionPath_discardCancelsPendingGenerationAndLateResultCannotRecreateDraft`: Proves discarding cancels generation and late results cannot recreate drafts.
  4. `productionPath_editsDuringGenerationAreRetainedAndOfferedAsVersion`: Proves live typing during generation is preserved and incoming AI draft is added as an alternate version.
  5. `versionAndEvidenceAlignment_neverFallsBackAndProtectsBaselineAuthorship`: Proves switching draft versions restores exact provenance and preserves original AI text baseline.
  6. `standaloneDraft_accountSwitchingClearsAndIsolatesEvidence`: Proves switching accounts during email compose isolates evidence and clears previous account state.
  7. `openSavedDraft_restoresVersionsAndValidatesAccount`: Proves loading saved drafts restores version history and provenance.
  8. `clearedEditorDuringGeneration_preservesEmptyEditorAndOffersGeneratedResultSeparately`: Proves deleting the entire draft during generation preserves the empty editor and offers the incoming proposal in version chips.
  9. `identicalTextGenerations_preserveDistinctVersionIdentityAndProvenance`: Proves multiple generations producing identical text retain distinct version IDs and switch provenance accurately.
  10. `editingAndRestoringTextDuringGeneration_detectsEditRevisionAndPreservesUserWriting`: Proves edit revision tracking catches edits even when restored to baseline text.
- **Listener Recovery (`MiniNotificationListenerServiceTest.kt` - 11 tests)**:
  - 11 production recovery tests verifying monotonic rate-limited rebind cooldown (15 s) and single-shot bounded deferred recovery.
- **Local AI Lifecycle & Runtime Storage (`LocalAiLifecycleTest`, `RuntimeStorageTest`, etc. - 92 tests)**:
  - Verified background release, foreground recovery, and safe fallback handling.

---

## 3. Physical Device Visual Fixtures (Pixel 10 Pro)

Exported on device via `PreviewExportActivity` into `native/review-artifacts/phase3a-acceptance/`:

| Fixture File | Surface / Mode | Font Scale | Size (bytes) | SHA-256 (prefix) | Observed UI State |
|:---|:---|:---|:---|:---|:---|
| `thread-light.png` | Thread / Light | 1.0x | 194,076 | `74821b31ddf6` | Account label, email headers, inbound/outbound bubbles, single composer |
| `thread-dark.png` | Thread / Dark | 1.0x | 194,614 | `e6e8e6877e84` | High-contrast Paper & ink dark canvas (`#212D37`), legible chips |
| `thread-large.png` | Thread / Light | 1.3x | 243,531 | `01a7287895ca` | 130% font scaling, comfortable touch targets, zero clipping |
| `thread-xlarge.png` | Thread / Light | 2.0x | 252,251 | `79325bec7629` | 200% font scaling with responsive button wrap; all actions accessible |
| `thread-generating.png` | Generating / Light | 1.0x | 224,623 | `f546b590ff22` | AI card expanded, "Stop" button, quick chips, status: "Generating locally on this device…" |
| `thread-manual.png` | Manual / Light | 1.0x | 173,849 | `34329ea2bb75` | Handwritten draft with no AI provenance pill; pure manual path |
| `evidence-light.png` | Evidence / Light | 1.0x | 223,215 | `ad4118aca596` | Populated: account lock pill, cited message, instruction, previous draft, style, exclusions |
| `evidence-dark.png` | Evidence / Dark | 1.0x | 221,764 | `03b7b2ac2d3e` | Populated evidence in dark theme with high-contrast text |
| `evidence-large.png` | Evidence / Light | 1.3x | 237,449 | `6f8e470c5415` | Populated evidence cards scaled at 1.3x font size |
| `evidence-xlarge.png` | Evidence / Light | 2.0x | 211,223 | `981302475e67` | Populated evidence cards scaled at 2.0x font size without truncation |
| `evidence-unavailable.png` | Unavailable / Light | 1.0x | 119,111 | `8188fd8e3107` | Separately retained fixture showing "Evidence unavailable: earlier generation evidence is no longer available." |

*All 11 fixtures have verified distinct visual states, file sizes, and SHA-256 hashes.*

---

## 4. Bounded Device Acceptance & Workflow Results

Executed on physical Pixel 10 Pro hardware using synthetic content without live external sends:

1. **Edits—including clearing the editor—during generation**:
   - Synthetic harness: `TestInferenceEngine` (labeled fake inference engine for unit/synthetic testing).
   - In flight typing preserves live writing in the editor and stores incoming AI draft as an alternate version.
   - Clearing the editor (`""`) while generation is in flight preserves the empty editor (`localDraftText == ""`, `selectedVersionIndex == -1`) and offers the generated proposal separately in `draftVersions`.
2. **Discard followed by late result**:
   - Tapping "Discard" clears the editor, deletes saved drafts from database, and increments `localDraftGeneration`.
   - Late callbacks from background execution check generation token and are safely discarded, preventing draft resurrection.
3. **Version selection with matching evidence**:
   - Multiple generations (even producing identical proposal text) maintain separate IDs and independent provenance.
   - Tapping a version chip restores its exact `DraftProvenance` and original AI baseline text.
4. **Account switching without retained evidence**:
   - Switching between Account A and Account B clears active draft text and provenance, closes the evidence sheet, and enforces strict account boundary isolation.
5. **SQLCipher draft/version/provenance restoration after restart**:
   - Production SQLCipher database on device (`mini-hub-actions.db`) with Android Keystore AES-GCM envelope key.
   - Schema verified with `provenance_json` and `versions_json` columns.
   - Process lifecycle logs confirm clean resource release on backgrounding (`RELEASE_DONE id=1 reason=background status=RELEASED afterMs=1 pssKb=163021`).
6. **Opening cited original message & manual exact review**:
   - Tapping "Open original message ↗" from evidence targets the cited message key, closes the sheet, and highlights the message card in `ThreadView`.
   - Tapping "Review reply" opens the reviewed action snapshot dialog displaying exact sender, recipient, and body without executing a network send.

---

## 5. Live Device Telemetry & Sync State

Observed on device (`app.mini.hub`, PID `1363`):
- **Notification Listener**: Active and bound (`ComponentInfo{app.mini.hub/app.mini.hub.android.notification.MiniNotificationListenerService} (user 0): android.service.notification.INotificationListener$Stub$Proxy@87aece`). Zero disconnect/rebind churn.
- **Sync Separation**: Notification listener capture remains strictly isolated from authenticated provider sync.
- **Background Sync Status**:
  - Gmail Account 1: `gmail-[REDACTED] state=Healthy`
  - Gmail Account 2: `gmail-[REDACTED] state=Healthy`
  - WhatsApp Account: `whatsapp|[REDACTED] state=Healthy`
- **Security & Integrity**: Keystore database envelopes, encrypted databases, and FLAG_SECURE release gates fully preserved.

---

## 6. Scope Boundaries

- **Phase 3A Complete**: Conversation clarity, authoritative single-body composer with race/edit protection, truthful "Why this draft?" provenance, accessibility reflow, and plain SQLite / Android SQLCipher persistence verified.
- **Phase 3B on Hold**: Outbox batch actions, model file import workflows, and custom onboarding styling remain strictly deferred.
