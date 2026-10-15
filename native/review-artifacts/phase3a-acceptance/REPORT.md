# Phase 3A Acceptance Report: Conversation Details, Composer, and “Why this draft?” Evidence

## 1. Scope and Architecture Delivered

Phase 3A establishes the reachable Android conversation, drafting, and AI input explanation experience matching the Paper & ink design contracts:

1. **Conversation Clarity**:
   - Header displays the actual sending identity and account name (`SourceAppNames · Account`).
   - Participants list displayed for multi-party and email conversations.
   - Inbound vs outbound message direction clearly delineated with distinct alignment and backgrounds (sender "You" for outbound).
   - Email cards show From, To, Cc, Subject, Date, expandable formatted bodies, and media attachments with preview/open actions.
   - Coverage banners clearly distinguish notification-only history (`NotificationOnly`), partial synced history (`PartialApiSync`), and loading failures with retry.

2. **One Dependable Composer**:
   - Single authoritative editable body (`localDraftText`). Manual typing, free instructions, refinement chips ("Shorter", "Warmer", "More polite", "More casual", "Say no politely", "Ask a question", "Try again"), and "Stop" generation are unified.
   - Failed or cancelled generation strictly preserves the user's existing text.
   - Asynchronous generation results arriving after switching accounts or conversations verify `selectedConversation.conversationId == requestedConversationId` and `accountId == requestedAccountId`; stale generation results are safely discarded and cannot overwrite the newly selected draft.
   - Version selection chips allow switching between up to 5 generated versions.
   - "Discard" clears draft text, deletes saved draft, and resets composer.
   - "Review reply" opens exact review dialog with exact-review/send contracts intact.

3. **“Why this draft?” AI Input Evidence**:
   - `DraftProvenance` captures the exact inputs supplied for each generated draft version: account ID, account label, user instruction, source message references (message IDs, snippets, timestamps, sender hints), applied style summary, coverage limitations, and excluded attachments.
   - Clickable "Open original message ↗" targets the exact source message, exits the evidence view, and smoothly scrolls to and highlights that message in the conversation thread.
   - Saved drafts persist their provenance in encrypted SQLite (`SqlCipherActionStore`).
   - Manually authored drafts without AI generation explicitly state: "Written manually. This draft was typed directly by you, or earlier generation evidence is no longer available."
   - Binary attachments (such as PDFs or images) are explicitly listed under "Not included" with notice: "Its contents haven't been read for this draft. The draft does not approve the price or claim you've reviewed unread files."

---

## 2. Source Tests (Unit Regression Suite)

File: `native/androidApp/src/test/kotlin/app/mini/hub/android/ui/Phase3AConversationComposerTest.kt`

- `accountIsolation_retrievalAndProvenanceUseOnlySelectedAccount`: Verified retrieved observations and draft context strictly isolate the selected account, with zero leakage across accounts.
- `staleGenerationResult_doesNotOverwriteNewlySelectedConversation`: Verified asynchronous generation results completing after conversation switch never overwrite the newly selected draft.
- `generationFailureAndCancellation_preservesExistingDraftText`: Verified user-typed text is preserved when generation fails or is stopped.
- `draftPersistenceAndRestoration_preservesBodyAndProvenance`: Verified draft body, generation versions, and provenance persist across conversation exit/reopen and discard.
- `manualRepliesRemainUsableWithoutWorkingModel`: Verified composer and exact review work 100% manually when local AI model is absent or unavailable.
- `provenanceSerializer_roundTripsCleanly`: Verified JSON serialization and deserialization of `DraftProvenance` round-trips without data loss.
- `openSourceMessageFromEvidence_setsTargetKeyAndClosesEvidenceView`: Verified clicking a source message reference in evidence closes the view and targets the message for scrolling.

All 43 Gradle tasks across `:androidApp:testDebugUnitTest` and `:shared:jvmTest` passed.

---

## 3. Rendered Visual Previews (Synthetic Fixtures)

Directory: `native/review-artifacts/phase3a-acceptance/`

Rendered via `PreviewExportActivity` on physical Pixel 10 Pro:
- `thread-light.png`: Conversation thread in light mode with account label, email headers, media, and composer.
- `thread-dark.png`: Conversation thread in dark mode with Paper & ink color tokens.
- `thread-large.png`: Scaled at 1.3x font size with all touch targets reachable.
- `thread-xlarge.png`: Scaled at 2.0x font size without clipping or unreachable actions.
- `evidence-light.png`: "Why this draft?" view with account isolation pill, quoted source messages, user instruction card, applied style, and excluded attachments card.
- `evidence-dark.png`: "Why this draft?" in dark mode.
- `evidence-large.png`: "Why this draft?" scaled at 1.3x font size.
- `evidence-xlarge.png`: "Why this draft?" scaled at 2.0x font size.

---

## 4. Live Device Verification (Pixel 10 Pro, Android 16)

- Debug APK built and installed using `adb install -r`, preserving all encrypted databases, user accounts, and authorizations.
- Notification listener remained continuously connected (`MiniListener: listener connected`).
- Device permission state preserved (`POST_NOTIFICATIONS: granted=false`).

---

## 5. Stated Limitations and Separated Scope

- **Out of Scope (Phase 3B / Phase 4)**: Onboarding redesign, model file download/import, scoped style-consent UI, and outgoing attachment editing remain separate work as instructed.
- **Attachment Understanding**: MINI does not process raw PDF or binary media through on-device LLM generation. All attachments are explicitly flagged in "Why this draft?" as not read by AI.
