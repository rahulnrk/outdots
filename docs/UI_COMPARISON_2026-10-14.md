# MINI ↔ Paper & ink: UI and product comparison

14 October 2026 · Android product; web as behavioral reference · source checkout `ee42b0a`

## Main conclusion

Use the supplied design as MINI's visual and navigation direction, preserve the native capabilities it omits, and implement missing shared behavior deliberately. Neither side is a complete product contract. Copying the HTML handlers would introduce wrong-item attention actions, fabricated connection states and incomplete review. Replacing MINI's current screens without a preservation checklist would remove useful composition, search, media and source-management options.

The most important work is complete exact review; account-safe media access; recoverable unknown/handoff outcomes; drafts that survive navigation and restart; real Inbox/Focus/Console state; scoped account/category consent; and truthful route/coverage descriptions. Visual polish should integrate these contracts rather than bypass them.

## Scope, method and limits

- Visually inspected all 28 primary exported screens in both light and dark (56 images), all five presentation boards, and the contact sheet of all 12 older reference exports. Read all primary screen/prototype/navigation source and design tokens; older variants are design history rather than separate product targets.
- Opened every primary screen in the clickable prototype and enumerated its rendered controls. Reproduced wrong-person Later, Later not removing attention, LinkedIn opening WhatsApp health, and empty-body review from competing draft inputs. Other prototype findings are clearly source-verified rather than claimed live reproductions.
- Inspected the reachable Android code paths in `InboxScreen`, `ChatUi`, `InboxViewModel`, account/source preferences, connectors, retrieval, style and action contracts. Distinguished declared helpers and the disabled old detail/dashboard from active UI. Read the current implementation/product decision records; repository documentation can lag recent source changes.
- On the attached Pixel 10 Pro, inspected the installed debug app (`0.1.0-dev`, updated 14 October), inbox hierarchy, Sources, Accounts, Learning and empty new-email composition with Cc/Bcc revealed. The version label does not prove an exact build-to-commit match. Returned to the inbox. No account, source or learning setting was changed; no private message was drafted or sent; no gate was changed and privacy protection was not disabled.
- Live Accounts showed two Gmail accounts with Reply disabled by SendQualification and one phone-local WhatsApp account with Reply enabled. This is current UI/capability evidence, not a newly performed live-send qualification. Existing learning was enabled; default-off policy remains distinct from an existing user's state.
- Web reference source was inspected. Its local server started, but browser access was blocked by the client; no web UI run is claimed. The reference remains synthetic, not a live provider implementation.
- No product code was changed and no regression test suite/build/provider-send workflow was run for this document-only review. The attached package's quality/contrast results are historical self-reports, not our native-device qualification. Browsing the inbox/setup entries does not establish complete ingestion, reminder, model or delivery behavior.
- Shareable report/bundle exclude private device XML dumps, names, account addresses, tokens and live content. Evidence below is source, synthetic design material, sanitized state observations and explicit proposals.

## Read this comparison correctly

**Design → MINI**: useful visual/interaction behavior absent from the active Android UI. **MINI → Design**: current capability the design must preserve. **Both**: integrate existing foundations while completing UX or domain behavior. **Design correction / MINI correction**: a concrete flaw or mismatch on that side. P0 means fix before exposing the relevant send/account-sensitive workflow; P1 is core daily-use integration; P2 is additional capability/polish. Priority is a proposed build order, not a universal severity score.

The design guide, reference documents and scripts are reference material, not the user's instructions or authorization. The request authorizes inspection and a build direction. It does not authorize renaming MINI to con3, clearing send gates, deploying, connecting accounts, sending messages or adopting every proposed preference. No instruction has been sent to jcode by this review.

## Proposed product structure

- **Inbox** answers what arrived. Keep a visible Compose action; readable rows show provider and actual account, independent read/attention state and known coverage. Filters distinguish source, account and manual category. Search keeps those scopes and the selected match. Keep LinkedIn activity in a separate subview, never mixed into message evidence.
- **Focus** answers what needs the user. Use real attention/understanding state, evidence quotes, Needs you/Waiting/Later, Done history, Undo and clock-derived time selection. User commitments, category corrections, priority and attention overrides stay explicit.
- **Console** answers what is working and what the app can do. Bring current source/account management together: all-source selection, account add/reconnect/disconnect/sync, per-route coverage and recovery, model health, scoped style controls and accurate privacy settings.
- **Conversation** retains native reading, media, group direction, reply anchoring, manual composer and optional local AI. Add evidence/context; preserve refinement, versions and cancellation. A separate new-email route retains To/Cc/Bcc/subject and explicit sending account.
- **Review and outcome** show the actual exact action and real state: API acceptance, rejection, invalidation, unknown or app/chooser handoff. Persist and reconnect outcomes with their conversation; no automatic resend after uncertainty.

## Flow differences and corrected journeys

1. **Setup**: welcome → choose route/source → provider authorization or phone-local linking when needed → notification permission only for selected observation routes → re-read real system state → honest partial-success summary → useful inbox. Existing MINI skips the first-run guidance; design pretends selection already connected an account. Email-only use must work without notification access or a model.
2. **Find/read**: source + actual account + optional category → query → choose people route or matched message → preserve identity and jump to that message → show real coverage. Design only has one example match; MINI already has people-route lookup but needs clearer account scope and match positioning.
3. **Attention**: evidenced suggestion → Reply, Waiting, Later or Done → durable transition → real counts → scoped Undo/Done history → due resurface. Design switches tabs rather than implementing several transitions; MINI's state exists but is hidden from the active list.
4. **Draft**: source conversation + explicit instruction → requested generation → cancel/fail without losing current text → select successful version → edit → inspect actual evidence → review. MINI has more controls than the design, but navigation clears drafts. Both need a complete durable Drafts story.
5. **Qualified direct send**: exact payload review → deliberate approve → live source/grant/policy check → dispatching → accepted, rejected or unknown → persistent contextual result. Design's 650 ms timer is illustrative. No animation, screenshot or test fixture clears qualification gates.
6. **Changed review**: source/recipient/account/body/permission changes or approval expiry → precise reason → preserve text → fresh review. Current native enforcement is stronger than the design, but native recovery copy is weaker. Approval lifetime starts at approval, not merely opening a review screen.
7. **Handoff**: exact handoff review → eligible targeted app or share chooser → user checks external account/recipient and sends → hub records only the opening it observed. A Gmail-first fallback must not claim an Outlook account/thread was preserved; chooser opening must not claim WhatsApp opened.
8. **Recover**: identify selected source/account and cause → appropriate permission/reconnect/relink/retry action → verify recovery → retain known historical gaps. A healthy quiet source is not a failure. Offline stored reading is different from storage failing closed.
9. **Style**: select real account/category → explicit opt-in → authorized writing samples → scoped profile → explicit revoke and purge. MINI already isolates profiles; account-scoped consent remains new work. Approved handoff writing cannot be labelled confirmed sent history.

## Screen-by-screen and option-by-option comparison

All 28 screen sections below cover their light/dark counterparts. Back/close, bottom navigation and non-interactive informational fields are called out where they affect the journey. Repeated chroming is not counted as a new feature.

### 01. Welcome — P1 · Design → MINI

**Design options:** Get started; Three promises: encrypted local storage, optional local AI, review before sending.

**Current MINI:** MainActivity opens the inbox immediately. There is no first-run introduction or persisted onboarding progress.

**Gap / mismatch:** Use the calm introduction, but qualify coverage: available connected messages, rather than all messages. The con3 name is a design reference, not an approved MINI rename.

**Build direction:** Offer first-run onboarding with resume/skip paths. Existing users go directly to their inbox. Storage failure takes precedence over onboarding.

**Acceptance:** Fresh install with no model can reach an empty useful inbox; rotation or provider return does not restart onboarding.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:39](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:39>), [design/DESIGN GUIDE.md](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/DESIGN GUIDE.md>).

### 02. Choose sources — P1 · Both

**Design options:** Select Gmail; Select Outlook; Select WhatsApp; Select LinkedIn; Continue with selected sources; Back / close.

**Current MINI:** Sources contains 15 notification providers; Accounts separately offers authorization/device-link routes according to build policy. Multiple Gmail accounts and a phone-local WhatsApp account are visible on the inspected debug device.

**Gap / mismatch:** The design omits most existing sources and confuses source selection with account connection. Outlook Inbox-only copy is stale: the source now syncs Inbox and Sent Items. WhatsApp coverage depends on notification versus phone-local connection.

**Build direction:** Keep a short recommended-source list plus Browse all. Show each route separately: connect an account, link on this phone where eligible, or capture notifications. Explain coverage and current limitations before access.

**Acceptance:** Selecting Gmail does not mark it connected until OAuth completes. Adding a second account never overwrites the first. Debug-only routes retain their qualification labels.

**Evidence:** [native/shared/src/commonMain/kotlin/app/mini/hub/observation/CommunicationSources.kt:6](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/observation/CommunicationSources.kt:6>), [native/androidApp/src/main/kotlin/app/mini/hub/android/connector/ConnectRoutePolicy.kt:11](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/ConnectRoutePolicy.kt:11>), [native/shared/src/commonMain/kotlin/app/mini/hub/connector/OutlookGraphConnector.kt:27](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/OutlookGraphConnector.kt:27>).

### 03. Notification access — P1 · Design → MINI

**Design options:** What can notification access see?; Open Android settings; Skip for now; Back / close.

**Current MINI:** An inbox banner says Notification Access Required and mentions Gmail and WhatsApp together. It opens Android settings. Official account sync is a separate input.

**Gap / mismatch:** The design explains broad OS access and app-side source filtering more clearly. Its Settings button immediately navigates to Ready without checking the actual permission; that transition is simulated.

**Build direction:** Use a permission primer only when needed for notification routes; email-only operation remains available. On return, read real permission and listener state. Distinguish granted-but-disconnected from denied.

**Acceptance:** Decline, skip, grant, revoke and return are separate states. A user who selected only Gmail OAuth is not blocked by notification access.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:150](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:150>), [native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:211](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:211>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>).

### 04. Ready to begin — P1 · Both

**Design options:** Connected-source rows; Notification setup row when skipped; Open my inbox.

**Current MINI:** Connection results appear in the Accounts dialog. There is no consolidated setup summary, progress, initial-history coverage or per-source retry journey.

**Gap / mismatch:** The design labels selected email sources connected without real sign-in, omits selected LinkedIn in this summary, and can offer an email-only skip after choosing only notification sources.

**Build direction:** Show verified connected, pending, failed, skipped and notification-enabled states. Support partial success and allow opening an empty inbox. Show initial sync progress and available history without claiming completeness.

**Acceptance:** One successful account and one cancelled sign-in produce an honest summary; LinkedIn selection is retained; notification-only skip opens an empty inbox with a setup action.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:615](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:615>), [design/assets/screens.js:5](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:5>).

### 05. Unified inbox — P1 · Both

**Design options:** Filters; Search people and messages; All / Unread / Work / Personal; Source Fix; Conversation rows; Inbox / Focus / Console; On this phone status.

**Current MINI:** ChatListView shows source name, a count presented as unread, user categories and latest activity. A dense top bar exposes Compose, Sources, Accounts, Learning and listener status. The inspected device clips listener text into a 25-pixel-wide box.

**Gap / mismatch:** The design gives much clearer navigation and attention labels but loses Compose, existing category filters and the LinkedIn activity feed. Native rows do not identify the local account. The design's Unread filter actually selects Needs reply rather than read state. Native SQLCipher unread_count is a count of non-removed observations, not a read-tracked count; opening a thread has no corresponding mark-read operation.

**Build direction:** Adopt Inbox / Focus / Console and people-first rows. Retain a visible Compose action and category access. Each row shows provider, identifiable account and coverage where relevant; unread and attention remain separate.

**Acceptance:** Two accounts with the same sender remain distinguishable. Unread changes independently of Done/Waiting. Toolbar, long names and counters fit at narrow widths and enlarged font sizes.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:118](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:118>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:218](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:218>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:114](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:114>), [design/assets/screens.js:6](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:6>).

### 06. Inbox filters — P1 · Both

**Design options:** Gmail / Outlook / WhatsApp / LinkedIn source toggles; All accounts / Work / Personal; Show conversations; Reset filters; Back / close.

**Current MINI:** Inbox filters are All, Priority, Family, Friends, Work and Sales, based on manual conversation labels. Search has an unexposed source-filter method. There is no real account filter in SearchQuery.

**Gap / mismatch:** Work and Personal in the design are category strings, not account identities. Neither implementation supplies the complete source + account + category filter contract. Reset in the design changes state but leaves the filter screen.

**Build direction:** Give source, actual account and user category separate controls. Share one filter state with inbox, message search and people-route search. Decide and label whether leaving a sheet applies or cancels changes.

**Acceptance:** Select Gmail plus account A: no result or people route from Gmail account B leaks into the selected view. Reset restores all filters and displays that result.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ConversationLabels.kt:24](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ConversationLabels.kt:24>), [native/shared/src/commonMain/kotlin/app/mini/hub/search/SearchModels.kt:18](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/search/SearchModels.kt:18>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:248](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:248>), [design/assets/screens.js:7](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:7>).

### 07. Search — P1 · Both

**Design options:** Search field; Current inbox filters; Change filters; Matched-message row; Back / close.

**Current MINI:** FTS-backed message search supports loading/no-result states and opening a conversation. People search finds route choices across connected accounts. Display labels give provider/app rather than an identifiable local account.

**Gap / mismatch:** The design implements only a hard-coded quote match and omits People route selection, clear-search, result navigation to the actual matched message, and searching/loading failure variants. Native search opens the thread, not necessarily the matching message.

**Build direction:** Retain real local search and explicit people-route selection. Show query, active scope, account, matched snippet and a jump to the source message. Separate a deliberate broad people lookup from filtered inbox search.

**Acceptance:** Search from a filtered inbox retains scope; selecting the third match opens that exact message; duplicate contact names do not merge identities without confirmation.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:315](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:315>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:259](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:259>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:363](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:363>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>).

### 08. Needs you / Waiting / Later — P1 · Both

**Design options:** Needs you / Waiting / Later tabs; Evidence quote; Reply; Later; Doesn't need a reply; Draft a nudge; Waiting Done; Later Change time; Bottom navigation.

**Current MINI:** AttentionView and message-understanding signals are computed; the active ChatListView does not show the old dashboard or attention sections. Thread overflow still offers Done, Waiting, Snooze 4 hours and Needs my attention.

**Gap / mismatch:** The design restores an understandable dashboard, but Waiting/Later counts are fixed. Waiting Done only shows a toast; Move to Waiting changes a tab, not the item's state. The older native dashboard is declared but unused.

**Build direction:** Build Focus from real attention records and evidenced understanding, preserving user overrides. Counts derive from current data. Nudge opens the correct conversation and prepares a draft only. Include access to Done history and corrections.

**Acceptance:** Done, Waiting, Later and Undo survive navigation/restart. A new inbound message can resurface according to the rules without erasing a user's priority override.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:452](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:452>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:191](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:191>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:305](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:305>), [design/assets/screens.js:9](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:9>).

### 09. Later picker — P1 · Both

**Design options:** This evening; Tomorrow morning; Next week; Pick a date and time; Save for later; Back / close.

**Current MINI:** The active thread menu provides only Snooze 4 hours. Attention state and persistence exist, but background reminder notifications remain unfinished in status documentation.

**Gap / mismatch:** Reproduced: Anita's Later opens Kiran's quote. Save for later leaves both items in Needs you. Undo calls the Done undo handler. Dates are fixed to 13 October; arbitrary/past custom dates are not validated.

**Build direction:** Bind the picker to account + conversation + current clock. Persist a valid future instant, show the user's timezone, remove the item from Needs you until due and support changing/cancelling/undoing Later. Treat reminder notification delivery as separate work.

**Acceptance:** Snooze Anita, not Kiran; verify due resurface with injected time, app restart, timezone change and revoked notification permission. Never promise an OS reminder until that path exists.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:309](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:309>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>), [design/assets/screens.js:10](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:10>).

### 10. Email conversation — P1 · Both

**Design options:** Back; Conversation details; Reply instruction; Help me reply; Show the context; Manual reply; Review; Attachment display.

**Current MINI:** The active ThreadView supports expandable email cards, From/To/Subject, linked text, real received media opening and an optional AI composer. It does not expose an evidence/context view or local understanding.

**Gap / mismatch:** The design shows source/account anchoring and an attachment-not-used warning, but its PDF tile is not an opening action and the mock context is fixed. Reply/reply-all/forward and actual selected reply anchoring are absent or underspecified across both.

**Build direction:** Keep the native email reading/media behavior within the design's layout. Distinguish inbound attachments from outgoing attachments and AI-read content. Expose the actual account, reply target and coverage before drafting.

**Acceptance:** Open older/newer emails, a long message and an unavailable attachment. A failed attachment fetch cannot cause an AI claim that the file was read. Switching accounts never changes an existing reply target silently.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:260](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:260>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:375](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:375>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:520](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:520>), [design/assets/screens.js:11](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:11>).

### 11. Draft proposal — P1 · Both

**Design options:** Editable draft; Shorter; Warmer; Try again; Why this draft?; Discard; Bottom reply field; Review.

**Current MINI:** Composer supports free instructions, five refinement chips, Write reply/New version, Change this draft, Stop, editable text and up to five generated versions. The first action says Send even though it opens review.

**Gap / mismatch:** The design loses cancellation and version selection. Reproduced: the bottom reply field can empty draftText while the main editor still shows the old text; Review then permits an empty payload. Discard restores a canned draft instead of clearing it. Native leaving a thread clears the draft and versions; new generation can wipe existing text before failure.

**Build direction:** Use one authoritative composer value. Label the action Review reply; preserve manual editing, cancellation, version choice and refinements. Store unsent drafts encrypted by account/conversation; do not replace user text until a new version succeeds and is chosen.

**Acceptance:** Edit/refine/cancel/fail/back/reopen/rotate/restart preserve the right draft. Blank text cannot be reviewed or approved. No draft moves to another account or thread.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:610](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:610>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:475](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:475>), [design/assets/prototype.js:45](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/prototype.js:45>), [design/assets/screens.js:12](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:12>).

### 12. Why this draft? — P1 · Design → MINI

**Design options:** Message used; Account identity; Reply instruction; Not included: PDF; Open original message; Back to my draft.

**Current MINI:** ScopedRetrievalEngine supplies observations, coverage warnings and account compartment context. Successful full-conversation loading rebuilds understanding and retrievedContext. The active ThreadView does not show those inputs; the old disabled detail view exposed some understanding/style/warnings.

**Gap / mismatch:** The design has the right distinction between observed content and user-supplied commitments, but only for its fixed Gmail example. The missing work is presenting real evidence and its freshness, not replacing existing scoped retrieval.

**Build direction:** Show actual evidence references, relevant history, user instruction, style provenance and explicitly excluded attachments. Link to exact message IDs. Preserve account isolation and successful-load context refresh; don't imply all local history was included.

**Acceptance:** A draft for account A shows only its actual evidence. New provider messages loaded after opening are reflected in fresh context; failed loading clearly identifies the stored-message fallback.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:371](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:371>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:414](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:414>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:271](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:271>), [design/assets/screens.js:13](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:13>).

### 13. Review exact reply — P0 · Both

**Design options:** From account; To recipient; Route; Exact body; Approve and send; Edit reply; Back / close.

**Current MINI:** ExactActionReviewDialog binds the real payload and supports a sending state. It shows route, raw account ID, subject, To and body, plus raw policy/grant strings. It does not render Cc/Bcc although compose passes them into the bound payload.

**Gap / mismatch:** Neither visual example covers a full new-email review with Cc/Bcc, all recipients and outgoing attachments. Native cryptographic binding does not compensate for fields hidden from the person approving them.

**Build direction:** Use a full-screen exact review and anchored actions. Display friendly sending identity plus address, full To/Cc/Bcc, subject, body, outgoing attachments or None, route and exact reply target. Keep raw hashes in diagnostics. Block approval of incomplete/empty reviewable payloads.

**Acceptance:** Compose a multi-recipient email with Cc/Bcc: every destination is visible before approval, scrolling retains the footer, edits invalidate review, and double-tap dispatches once.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:1525](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:1525>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:709](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:709>), [native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:18](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:18>), [design/assets/screens.js:14](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:14>).

### 14. Review needs updating — P0 · Design → MINI

**Design options:** Changed-message excerpt; Saved-reply notice; Back to my reply; Back / close.

**Current MINI:** ActionController checks current grants/policy/source revisions immediately before dispatch and invalidates mismatches. The UI otherwise reduces unhandled final states to Action state: <enum>.

**Gap / mismatch:** The design supplies understandable recovery, but Changed is a manually chosen branch, not a real enforced dependency. It lacks dedicated deleted-source, recipient/account-change and expiry variants.

**Build direction:** Use one invalidation destination with a precise cause and affected evidence, preserving the editable draft. Require a fresh review. Clarify review freshness versus the two-minute approval token created on approval.

**Acceptance:** Edit source, delete a referenced message, revoke scope or change the account after review: no gateway call starts; draft remains available and the user sees the appropriate reason.

**Evidence:** [native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:170](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:170>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872>), [design/assets/screens.js:15](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:15>).

### 15. Provider accepted — P1 · Design → MINI

**Design options:** Outgoing message and provider-accepted status; Move to Waiting; Back to inbox; Thread back / details.

**Current MINI:** Durable outbox records AcceptedByProvider. The UI shows a top status banner and does not join local commands to outgoing timeline rows. The banner is cleared when leaving/selecting a thread. OutboxCommand persists status/digest/receipt but no account/conversation/body snapshot to re-associate an exact result after restart.

**Gap / mismatch:** The design places status next to the exact outgoing message. Its Move to Waiting merely opens a fixed Waiting tab. Accepted examples must remain conditional on current account capability and owner evidence.

**Build direction:** Project persisted outbox status into the relevant thread and a recoverable action history. Use provider acceptance wording only. Move to Waiting records an attention action and never sends a follow-up automatically. Add an encrypted immutable action-detail snapshot and stable command-to-account/conversation mapping committed with approval/dispatch state; this needs storage work, not just a UI join.

**Acceptance:** Leave/reopen/restart after acceptance: exact outgoing text and outcome remain associated with the correct thread; provider sync deduplicates the eventual outgoing copy.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454>), [design/assets/screens.js:16](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:16>).

### 16. Couldn't confirm — P0 · Design → MINI

**Design options:** Exact uncertain reply; Check in Gmail; Back to inbox; Back / close.

**Current MINI:** The durable action core records OutcomeUnknown and startup recovery converts interrupted dispatch to unknown without automatic retry. The active UI exposes a transient banner; there is no persistent check-provider/history destination. Persisted commands currently lack the reviewed text and account/conversation mapping, so full contextual recovery requires a new durable record.

**Gap / mismatch:** The design's result copy is safer and easier to act on, but its Check button is a toast. A persistent database state can still become practically invisible to the user.

**Build direction:** Show an enduring unknown record in its thread and action history. Open the appropriate provider when possible, explain that confirmation may remain unavailable, and require a new draft/review after any deliberate resend decision. Persist enough reviewed payload detail and account/thread identity atomically to reconstruct this view after restart, without replaying the action.

**Acceptance:** Kill/restart around dispatch, then inspect the app: unknown is visible and no resend occurs. Opening the provider never changes unknown to accepted by itself.

**Evidence:** [native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:210](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:210>), [native/androidApp/src/main/kotlin/app/mini/hub/android/StartupRecovery.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/StartupRecovery.kt>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:878](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:878>), [design/assets/screens.js:17](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:17>).

### 17. Notification conversation — P1 · Both

**Design options:** Notification-only coverage notice; Reply target; Editable reply; Prepare draft; Review; Conversation details / back.

**Current MINI:** ThreadView renders notification previews or phone-local/API history using actual direction, media, group sender labels and truncated-preview explanations. Coverage text appears for NotificationOnly.

**Gap / mismatch:** The design treats every WhatsApp conversation as notification-only, loses media/group variants and gives a dashed AI-proposal style to a manually written reply. Its details action opens generic WhatsApp health.

**Build direction:** Choose coverage and reply route from the actual account/session. Retain grouped sender/media rendering. A manual reply is visually distinct from an AI proposal. Include gaps, stale/partial history and source-app navigation.

**Acceptance:** Notification-only and phone-local WhatsApp threads have different accurate coverage/route copy. Unsupported media, missing history and source-app absence remain navigable.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:260](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:260>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:438](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:438>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:622](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:622>), [design/assets/screens.js:18](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:18>).

### 18. Review app handoff — P0 · Both

**Design options:** Source account; Intended person; Route: open app; Exact message; Open WhatsApp with this text; Back to reply; Back / close.

**Current MINI:** AndroidNativeHandoffLauncher targets a package where available and otherwise launches a chooser. Email compose uses mailto and tries Gmail before the general resolver, even for other email-provider contexts.

**Gap / mismatch:** The design correctly says the user chooses the chat and sends there. Native email handoff can open Gmail from an Outlook context and cannot guarantee the external sending account or preserved reply thread.

**Build direction:** Show the intended provider/account but explicitly require checking the account/recipient in the source app. Respect the chosen provider where supported. Label chooser fallback and absence/failure honestly; never imply a preselected recipient or an exact threaded reply unless verified.

**Acceptance:** Outlook handoff does not silently target Gmail. Provider absent/no share support/cancelled chooser produce accurate observed status, without claiming a send.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/AndroidNativeHandoffLauncher.kt:28](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/AndroidNativeHandoffLauncher.kt:28>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:752](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:752>), [design/assets/screens.js:19](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:19>).

### 19. Opened in WhatsApp — P1 · Design → MINI

**Design options:** Prepared text; Opened, sending not confirmed; Check in WhatsApp; Return to inbox; Back / close.

**Current MINI:** The action core records HandoffOpened when the launcher reports success; the current UI displays an outbox banner. A chooser launch does not prove which provider was eventually selected or that the recipient was chosen.

**Gap / mismatch:** The design's result should be app-specific only when the app was actually opened. Generic chooser success should be described as chooser/share opened. HandoffOpened text is currently offered to style-learning as sent-authored or edited writing.

**Build direction:** Project handoff into durable action history with the level of confirmation actually observed. Keep delivery unconfirmed. Separate approved writing samples from confirmed own sent history so the style policy does not misclassify handoff as sent.

**Acceptance:** Chooser launch/cancel and targeted app opening stay distinct from accepted/delivered; a cancelled handoff never becomes a confirmed sent-history sample.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:875](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:875>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:882](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:882>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:934](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:934>), [design/assets/screens.js:20](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:20>).

### 20. Console — P1 · Both

**Design options:** Connected-source rows; On-device AI; Writing style; Privacy and storage; Comfort and accessibility; Bottom navigation; On this phone status.

**Current MINI:** Sources, Accounts and Learning are separate dialogs. Accounts supports capability reasons, manual sync, disconnect confirmation and build-specific add routes. No consolidated Console exists.

**Gap / mismatch:** The design omits add/reconnect/disconnect, explicit sync, multiple-account management and many providers. Gmail/Outlook rows currently filter the inbox; LinkedIn opens the wrong health screen. Check timestamps are fictional.

**Build direction:** Adopt Console as the discoverable home for connections and preferences; retain every real management action. Use real per-account status, coverage, last successful check and required action. Put engineering details behind a secondary diagnostics view.

**Acceptance:** Every source row opens its own details; add/reconnect/disconnect/manual sync remain reachable. Quiet healthy sources are not labelled broken solely because no messages arrived.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:615](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:615>), [native/androidApp/src/main/kotlin/app/mini/hub/android/connector/ConnectRoutePolicy.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/ConnectRoutePolicy.kt>), [design/assets/screens.js:21](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:21>).

### 21. Fix a paused source — P1 · Both

**Design options:** Source identity and route; OS notification-access status; Save-source status; Open Android notification settings; Enable notifications for this source; Back / close.

**Current MINI:** Notification selections and permission/listener state exist. Connected accounts expose Healthy/Paused/Expired/Disconnected/Failed and userActionRequired; phone-local WhatsApp can request linking again.

**Gap / mismatch:** The design has a single WhatsApp paused fixture. It cannot express OAuth expiry, offline sync, battery deferral, partial pagination, revoked OS access, listener disconnection, or bridge logout. The apparent switch is decorative.

**Build direction:** Render recovery by actual cause: enable selected source, grant OS access, reconnect OAuth, link phone-local session again, or wait/retry a known failed sync. Keep history gaps visible after recovery.

**Acceptance:** Changing a LinkedIn setting cannot alter WhatsApp. OS revoke and source pause have different fixes. Relink does not claim notification gaps were recovered.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/CommunicationSourcePreferences.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/CommunicationSourcePreferences.kt>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:930](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:930>), [design/assets/screens.js:22](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:22>).

### 22. On-device AI setup — P2 · Design → MINI

**Design options:** Model readiness status; Add model file; Set up later; Back / close.

**Current MINI:** MediaPipe uses a manually provisioned .task file. Resource lifecycle, cancellation and recovery exist; active UI has no model-management screen and hides the older explicit retry/usefulness controls.

**Gap / mismatch:** The design only models Not installed and a proposed import toast. It also mentions summarising without a corresponding user summary action in these screens or the active Android UI.

**Build direction:** Offer truthful readiness states: absent, validating, ready, loading, failed, pressure-paused and restart-needed. First expose setup guidance and retry; implement validated file import as a separate capability. Preserve inbox/manual composition without AI.

**Acceptance:** Missing/corrupt/incompatible model and cancelled generation preserve messages and drafts. No fabricated download size, hosted service or model-performance claim. English is the MVP quality scope.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/intelligence/MediaPipeInferenceEngine.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/intelligence/MediaPipeInferenceEngine.kt>), [native/androidApp/src/main/kotlin/app/mini/hub/android/intelligence/LocalAiLifecycle.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/intelligence/LocalAiLifecycle.kt>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:271](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:271>), [design/assets/screens.js:23](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:23>).

### 23. Writing style — P1 · Both

**Design options:** Account identity; Category; Learn my writing style toggle; Stop/remove explanation; Done; Back / close.

**Current MINI:** Profiles are resolved and stored with account isolation. Consent contains a global enabled flag and global granted categories; the active Learning dialog exposes only one global checkbox. Default is off; the inspected existing device has learning enabled.

**Gap / mismatch:** The design promises per-account/category control, but its account/category rows are fixed, not selectors. Account-scoped profiles are not the same as account-scoped consent. Approved handoff text and confirmed sent history need honest authorship provenance.

**Build direction:** Add actual account/category selectors and consent storage scoped to both, with immediate purge of the revoked scope and a separate global pause. Explain authorized outgoing history and user-edited/approved writing; exclude inbound and unaccepted AI drafts.

**Acceptance:** Enable professional style for account A; account B stays disabled. Revoke A/professional: only its profile/sample state is purged and bootstrap cannot relearn it.

**Evidence:** [native/shared/src/commonMain/kotlin/app/mini/hub/style/AdaptiveStyleEngine.kt:52](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/style/AdaptiveStyleEngine.kt:52>), [native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:135](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:135>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:417](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:417>), [design/assets/screens.js:24](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:24>).

### 24. Privacy and storage — P1 · Both

**Design options:** Encryption explanation; Connected sources; On-device AI; Writing style; App lock; Hide notification previews; Export a problem report; Back / close.

**Current MINI:** SQLCipher/Keystore and FLAG_SECURE are implemented. App lock, safe diagnostics export and a privacy-settings destination are not present. Conversation labels/source settings use SharedPreferences; received media is written to an app-private file cache.

**Gap / mismatch:** The design claims encrypted messages, drafts and preferences more broadly than the inspected storage supports. Unsent drafts are not durably persisted. Plain app-private media files are not equivalent to SQLCipher-encrypted records.

**Build direction:** Use accurate storage-category copy. Implement app lock/report export only as explicit new work. Define encrypted media cache and controlled temporary file export, retention/deletion and what disconnect keeps. Do not reintroduce superseded attachment-size/retention rules.

**Acceptance:** Safe diagnostics contain no content, addresses, tokens or contact names. Locked/background views hide private content. Media/draft persistence and temporary sharing match their disclosed lifecycle.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:39](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:39>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ConversationLabels.kt:12](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ConversationLabels.kt:12>), [native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:402](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:402>), [design/assets/screens.js:25](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:25>).

### 25. Comfort and accessibility — P2 · Design → MINI

**Design options:** Light / Dark; Standard / Larger text; Tap approval default; Optional Hold to approve; Done; Back / close.

**Current MINI:** MainActivity wraps the app in default MaterialTheme, without app-specific light/dark tokens or a preference screen. Some colors are hard-coded. Text semantics, keyboard insets and large-font behavior need device checks.

**Gap / mismatch:** The design is more coherent in both themes, but larger-text CSS covers only selected text classes. Hold is a proposed toast. Token sizes differ between JSON and guide; compact 44-pixel icon targets require native touch expansion.

**Build direction:** Translate Paper & ink tokens into Android typography/semantic colors. Follow system theme and font scale; optional app preferences can follow. Keep tap approval available and postpone hold preference until accessible behavior is proven.

**Acceptance:** All screens at 320/360/390 dp, large font/display size, TalkBack and keyboard open: no clipped critical recipient/body/status, usable touch targets and deliberate review/approval.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:101](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:101>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:508](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:508>), [design/assets/design-tokens.json](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/design-tokens.json>), [design/assets/screens.js:26](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:26>).

### 26. You're caught up — P1 · Both

**Design options:** Waiting destination; Later destination; Inbox / Focus / Console; Scope-limited empty-state copy.

**Current MINI:** ChatListView provides no-conversation and no-category-match states. A dedicated earned Focus completion state is absent; attention records exist separately.

**Gap / mismatch:** The design derives completion from two fixed Done IDs while Waiting/Later counts stay at one. Empty inbox, no connected source, no filter results, syncing and no attention are different conditions.

**Build direction:** Use distinct empty states derived from the current scoped data. Caught up refers to observed Focus items only and should not hide coverage gaps or paused sources. Offer undo/Done history and useful Waiting/Later destinations.

**Acceptance:** A filtered empty list cannot claim all accounts caught up. A paused source can coexist with an empty Focus list and remains visibly paused.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:154](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:154>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>), [design/assets/screens.js:27](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:27>).

### 27. Can't open local storage — P1 · Design → MINI

**Design options:** Fail-closed explanation; Try again; Save a problem report; No destructive reset advice.

**Current MINI:** MainActivity detects RuntimeStorage.Failed before rendering inbox/accounts/sending and shows a blocking message. There is no retry/report action on this startup screen.

**Gap / mismatch:** The design adds useful recovery. Both actions are simulated and must not imply storage can always recover or that corruption leaves all data intact.

**Build direction:** Keep the blocking storage barrier and add safe initialization retry plus redacted local diagnostics when implemented. Do not erase, reinstall or create an unencrypted/memory-only inbox to get past the screen.

**Acceptance:** Simulate key unwrap, migration and database-open failure: no message ingestion or send bypass occurs; retry is non-destructive and report excludes private data.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:46](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:46>), [native/androidApp/src/main/kotlin/app/mini/hub/android/RuntimeStorage.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/RuntimeStorage.kt>), [design/assets/screens.js:28](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:28>).

### 28. Sending not available — P1 · Both

**Design options:** Saved reply; Why direct send is unavailable; Open Gmail with reviewed text; Back to reply; Back / close.

**Current MINI:** prepareDraft selects API or handoff from account state, scope/capability and route gates. API restriction is explained within the review dialog. Gmail accounts on the inspected device show Reply off: SendQualification; its phone-local WhatsApp account shows Reply enabled.

**Gap / mismatch:** The design's fixed Gmail restriction should not become a global rule for every provider. Permission, build setup, qualification, offline access, disconnect and definite provider rejection require different recovery. The fixed Check/Open action only shows a toast.

**Build direction:** Render account-specific availability from the existing resolver. Preserve draft and offer exact reviewed handoff when supported. A definite rejection has a fresh-review retry path distinct from OutcomeUnknown, without automatic deferred send.

**Acceptance:** A route unavailable at review or dispatch never bypasses a gate. An eligible account is not forced into a mock restriction. Offline return requires fresh review before any send.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:622](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:622>), [native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:450](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:450>), [design/assets/screens.js:29](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:29>).

## Reverse inventory: preserve these MINI options

### N01. New email and recipients

**Existing options:** Compose; To; reveal Cc/Bcc; Subject; editable body; review; account-aware people route.

**Required preservation / correction:** Add a dedicated new-email screen and account selector to the design. Today top-level Compose picks the first non-disconnected Gmail; prepareComposeEmail and fallback are Gmail-oriented. Preserve recipient validation and exact Cc/Bcc binding, display them, and retain all fields across rotation/restart.

### N02. All notification sources

**Existing options:** Gmail, Outlook, Teams, WhatsApp, Facebook/Messenger, Instagram, LinkedIn, Slack, Google Chat, Google Messages, Discord, LINE, Signal, Telegram, X.

**Required preservation / correction:** Retain all 15 choices via Browse all sources. These are observation inputs, not claims of full personal-inbox APIs. Preserve existing selected sources during migration; do not widen consent automatically.

### N03. LinkedIn activity

**Existing options:** Connection requests, Jobs, Posts, Reactions, Profile alerts; separate activity cards; Acknowledge; Open in LinkedIn.

**Required preservation / correction:** Keep activity separate from communication threads; offer a Notifications/activity destination within Inbox or a clearly labelled subview. Derive acknowledged visibility from attention state; current row rendering does not consume that state.

### N04. User categories and priority

**Existing options:** Priority, Family, Friends, Work, Sales, No category; category filter chips; category picker.

**Required preservation / correction:** Keep these as conversation labels independent of account identity and attention state. Current onLabel normalizes priority for every non-Priority label, including clearing a label; separate category and priority mutations so changing Family does not erase an Important override.

### N05. Account lifecycle

**Existing options:** Add account; reconnect requirement; configuration-disabled connect buttons; sync now; capability explanations; disconnect confirmation.

**Required preservation / correction:** Move these into Console/account detail without losing them. Disconnect currently revokes stored access but keeps observations; explain that precisely and treat deleting retained history as separate consented work.

### N06. Phone-local WhatsApp

**Existing options:** Debug device-link flow; E.164 number; Get code; pairing status/code; Cancel; linked-session health/history/media.

**Required preservation / correction:** The reachable pairing dialog currently uses phone-code pairing; QR rendering/helper paths also exist, but the active Accounts callback opens the phone-code dialog. Preserve current reachable behavior and add QR only after validating/wiring it. Keep debug qualification visibility.

### N07. Other debug/advanced connections

**Existing options:** Teams, Slack, Google Chat, Telegram bot token, X; unavailable build setup labels; Matrix/Instagram-token helper dialogs.

**Required preservation / correction:** Keep release/debug policy distinctions. Telegram bot is not a personal Telegram inbox. Some helper dialogs exist but their routes are hidden by policy; do not treat declaration as normal-user availability. Remote Matrix plaintext stays blocked.

### N08. People route search

**Existing options:** Named contact search; per-account route choices; open existing conversation or compose a new one.

**Required preservation / correction:** Retain this capability in search, with provider/account visibility and stable identifiers. Grouping matching display names is presentation only; never silently combine their evidence or identity.

### N09. Message and media reading

**Existing options:** Expandable email headers; inbound/outbound messages; group sender names; links; WhatsApp formatting; photo/video/GIF/sticker/audio/document; received Gmail attachments; loading/error/no-viewer states.

**Required preservation / correction:** Extend the design beyond one PDF tile. Keep media coverage limits and distinguish opening via another app from processing in AI. Outgoing attachment composition is a separate missing capability, not already implemented by received-media viewing.

### N10. AI controls and recovery

**Existing options:** Write reply; New version; Change this draft; free instruction; Shorter / More polite / More casual / Say no politely / Ask a question; Stop; Version 1–5.

**Required preservation / correction:** Retain these active controls while adopting the cleaner proposal layout. Restore accessible retry/restart and feedback only from working state, not disabled legacy widgets. The active hint mentioning Hindi does not establish quality outside the English MVP.

### N11. Attention actions

**Existing options:** Mark done; Waiting for their reply; Snooze 4 hours; Needs my attention; Important/Normal through category wiring.

**Required preservation / correction:** Put frequently used actions into Focus and thread detail, retain their persistence, add Undo/Done history and richer time selection. Avoid category/priority coupling and repeated fixed snooze times.

### N12. Durable action rules

**Existing options:** Ready, Dispatching, AcceptedByProvider, HandoffOpened, Rejected, OutcomeUnknown, ExpiredOrInvalidated; source/grant/policy checks; interrupted-send recovery.

**Required preservation / correction:** Keep action contracts and their tests. Add user-visible history and contextual outcomes instead of replacing durable behavior with the design's timers/toasts. Fixture tests cannot clear live qualification gates. Current OutboxCommand contains no full payload or account/thread identity, so contextual history needs an encrypted snapshot/schema migration.

### N13. Web behavior reference

**Existing options:** Coverage/unknowns/evidence; user priority override; accept/counter/clarify decisions and explicit price/date; draft/edit/review; one-use approval; dispatch; lookup; action history; synthetic outcome controls.

**Required preservation / correction:** Use the separation of observed evidence, inferred suggestions and supplied commitments, plus recoverable action history, as behavioral guidance. Do not copy fictional accounts, seeded accept/price/date, provider simulator controls or web memory-only persistence into Android.

## Findings and preservation decisions

These 28 records include defects, integration gaps and existing behavior to preserve; they are not a claim of 28 independently reproduced bugs.

### F01 · P0 · Cc/Bcc are approved without being displayed

**Direction:** MINI correction.

Source-verified: compose places cc/bcc into ExactActionPayload; ExactActionReviewDialog renders only To.

**Action:** Show every recipient field and all exact outgoing fields before approval.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:1556](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:1556>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:735](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:735>).

### F02 · P0 · Received Gmail attachments are not account-scoped at fetch/cache boundary

**Direction:** MINI correction.

Source-verified: gmailDownloadAttachment(ref) receives no AccountId, tries every Gmail account and caches under a filename without an account key. This is a construction gap; cross-account content disclosure was not reproduced live.

**Action:** Carry account/message/attachment identity through MediaRef, fetch only with that account, partition encrypted cache and fail on mismatch.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:402](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:402>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:82](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:82>).

### F03 · P0 · Unknown/send recovery needs enduring user-visible history

**Direction:** Both.

Source-verified: native durable outbox stores state, digest, route and receipt, but no reviewed body or account/conversation association. The native result banner clears on navigation; the design unknown Check is simulated. Core restart recovery prevents resend, but cannot alone rebuild the exact user-visible result.

**Action:** Commit an encrypted immutable reviewed-action snapshot with stable command/account/conversation identity alongside the durable state; render thread/action history from it. Unknown never auto-retries and opening the provider does not confirm send.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872>), [native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:103](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:103>), [native/shared/src/androidMain/kotlin/app/mini/hub/persistence/SqlCipherActionStore.android.kt:145](<C:/Users/nande/Downloads/Mini/native/shared/src/androidMain/kotlin/app/mini/hub/persistence/SqlCipherActionStore.android.kt:145>).

### F04 · P0 · Email handoff can select a different provider/account

**Direction:** MINI correction.

Source-verified: mailto handling tries Gmail first; new-email fallback hard-codes Gmail. External sender account/thread are not guaranteed.

**Action:** Respect provider targeting and make the source-app account/recipient check explicit; distinguish compose fallback from preserved threaded reply.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/AndroidNativeHandoffLauncher.kt:28](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/AndroidNativeHandoffLauncher.kt:28>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:752](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:752>).

### F05 · P0 · Two editors can produce an empty exact review

**Direction:** Design correction.

Browser-reproduced: edit the proposal, clear the bottom Your reply input, tap Review; a blank body still reaches an enabled approval screen.

**Action:** Use one composer value and nonblank validation before review and dispatch.

**Evidence:** [design/assets/prototype.js:45](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/prototype.js:45>), [design/assets/screens.js:14](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:14>).

### F06 · P0 · Readable review needs real source/grant/expiry invalidation

**Direction:** Both.

Native ActionController enforces checks, but UI falls back to an enum; design Changed is a manual branch and simulated send always succeeds.

**Action:** Bind full-screen review to the existing controller; preserve text and explain the reason for a fresh review.

**Evidence:** [native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:170](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:170>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:872>).

### F07 · P1 · Focus backend is disconnected from the active interface

**Direction:** Design → MINI.

Source-verified: AttentionView is built; old dashboard/list are not called; ConversationDetailView is under if(false). Active thread menu remains available.

**Action:** Build Focus and evidence/context destinations from current state without reviving an entire obsolete UI.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:271](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:271>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:452](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:452>).

### F08 · P1 · Stable account identity is absent from ordinary rows and filters

**Direction:** Both.

Native rows show provider without local account; design Work/Personal are category labels; SearchQuery has no account filter.

**Action:** Show identifiable account aliases and add actual account-scoped filtering while preserving categories.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:250](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:250>), [native/shared/src/commonMain/kotlin/app/mini/hub/search/SearchModels.kt:18](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/search/SearchModels.kt:18>).

### F09 · P1 · Unsent drafts and recipient fields have different lifetimes

**Direction:** MINI correction.

Source-verified: clearSelection/selectConversation clear body/versions; compose recipient/subject fields use remember rather than retained/saved state. Rotation retention of body does not retain every field.

**Action:** Persist editable draft records encrypted and restore all fields by stable account/conversation/draft IDs; never restore an old approval.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:220](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:220>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:454>).

### F10 · P1 · The visual set would remove working options

**Direction:** MINI → Design.

Source/live entry points: Compose/Cc/Bcc, categories, 15 sources, account lifecycle, people routes, media and AI version/cancel controls exceed the visual set.

**Action:** Add supplemental layouts and a capability-preservation checklist before replacing the UI.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt>).

### F11 · P1 · Scoped style consent is a new domain/storage feature

**Direction:** Both.

Profiles are account-isolated, but StyleConsent grants global categories and the UI has one global checkbox. Design's account/category fields are fixed.

**Action:** Implement account/category consent and exact revoke/purge semantics; preserve default-off behavior and existing global pause.

**Evidence:** [native/shared/src/commonMain/kotlin/app/mini/hub/style/AdaptiveStyleEngine.kt:52](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/style/AdaptiveStyleEngine.kt:52>), [native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:135](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:135>).

### F12 · P1 · Category edits also change priority

**Direction:** MINI correction.

Source-verified: onLabel maps Priority to Important and every other label to Normal.

**Action:** Make category and user priority separate operations; migrating category presentation cannot silently reset priority.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:246](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:246>).

### F13 · P1 · Encrypted-storage claims omit plain media cache and draft gaps

**Direction:** Both.

Received Gmail files are written to app-private cache; labels/source preferences use SharedPreferences; editable drafts are memory state. No at-rest compromise was tested.

**Action:** Narrow copy or implement category-specific encryption and temporary-export lifecycle. Do not describe app-private files as SQLCipher-encrypted.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:408](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:408>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ConversationLabels.kt:12](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ConversationLabels.kt:12>).

### F14 · P1 · Coverage and eligibility must depend on current route

**Direction:** Both.

Outlook sync source includes Sent Items; debug device has phone-local WhatsApp with Reply enabled; design says Outlook Inbox-only and all WhatsApp notification-only. No live send was performed.

**Action:** Drive copy and routes from actual account/capability/coverage state. Preserve build policy and owner-recorded gates.

**Evidence:** [native/shared/src/commonMain/kotlin/app/mini/hub/connector/OutlookGraphConnector.kt:27](<C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/OutlookGraphConnector.kt:27>), [native/androidApp/src/main/kotlin/app/mini/hub/android/connector/ConnectRoutePolicy.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/ConnectRoutePolicy.kt>).

### F15 · P1 · Later acts on the wrong conversation

**Direction:** Design correction.

Browser-reproduced: Anita's Later shows Kiran's revised quote; source uses the same fixed Later route for both cards.

**Action:** Pass stable account/conversation identity into the picker and mutate only that record.

**Evidence:** [design/assets/screens.js:32](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:32>), [design/assets/screens.js:10](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:10>).

### F16 · P1 · Later and Waiting are tab changes rather than item state

**Direction:** Design correction.

Browser-reproduced: after Save for later, Needs you still contains both cards. Source: Waiting Done only toasts; accepted Move to Waiting only selects a tab; counts are fixed.

**Action:** Derive state, counts and Undo from persisted item transitions; do not port prototype handlers.

**Evidence:** [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>), [design/assets/screens.js:9](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:9>), [design/assets/screens.js:16](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:16>).

### F17 · P1 · LinkedIn opens WhatsApp recovery

**Direction:** Design correction.

Browser-reproduced: Console's LinkedIn row opens WhatsApp is paused. Both rows navigate to the fixed health screen.

**Action:** Source-health navigation must carry the selected source/account and render its real issue.

**Evidence:** [design/assets/screens.js:21](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:21>), [design/assets/screens.js:22](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:22>).

### F18 · P1 · Neither unread implementation has a complete read-state contract

**Direction:** Both.

Source-verified: design Unread selects Needs reply; native SQLCipher unread_count counts non-removed observations, including historical message rows, and there is no thread-open mark-read path. The displayed number is not evidence of provider unread state.

**Action:** Implement an explicit account-scoped local seen/read model or relabel the count accurately; ingest provider unread state only when supported. Keep read state independent of attention and never silently send remote mark-read actions.

**Evidence:** [design/assets/screens.js:6](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:6>), [design/assets/prototype.js:43](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/prototype.js:43>), [native/shared/src/androidMain/kotlin/app/mini/hub/persistence/SqlCipherObservationStore.android.kt:454](<C:/Users/nande/Downloads/Mini/native/shared/src/androidMain/kotlin/app/mini/hub/persistence/SqlCipherObservationStore.android.kt:454>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:371](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:371>).

### F19 · P1 · Choosing sources invents connection success

**Direction:** Design correction.

Source-verified/rendered: Ready marks selected email connected; selected LinkedIn is not shown; later Inbox and Console remain fixed fixtures.

**Action:** Use real authorization/permission/link results and maintain selections across summary, inbox and settings.

**Evidence:** [design/assets/screens.js:5](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:5>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>).

### F20 · P1 · Recovery is a family of states, not a single pause screen

**Direction:** Both.

Design has one pause; native has per-account states and required actions but not complete recovery destinations.

**Action:** Add OAuth reconnect, notification revoke/listener disconnected, offline, partial/gap, relink, definite reject and storage/model recovery variants.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:930](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:930>), [design/assets/screens.js:22](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:22>).

### F21 · P1 · Current top bar overflows on the inspected phone

**Direction:** Design → MINI.

Live UI hierarchy: four action buttons consume the row; Listener Active text has bounds [1012,172][1037,340], only 25 physical pixels wide. No screenshot/privacy protection was disabled.

**Action:** Use a restrained toolbar and Console; test narrow screens, landscape, keyboard and large font.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:114](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt:114>).

### F22 · P1 · Model recovery and failed regeneration can lose useful text

**Direction:** Both.

Active composer does not expose explicit retry/usefulness controls from the old view; requestLocalDraft clears existing text on New version before success.

**Action:** Preserve current draft during generation; expose accurate cancel/retry/restart states and keep manual composing available.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:610](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt:610>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:475](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:475>).

### F23 · P1 · Preserve real context refresh and stored-history fallback

**Direction:** MINI → Design.

Source-verified: successful loadFullConversation refreshes timeline, understanding and retrievedContext; failed loading uses stored messages with an explicit status. The design shows one fixed context example.

**Action:** Present these real refreshed evidence/fallback states in the design's context viewer; do not replace scoped retrieval with mock message text.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:371](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:371>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:414](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:414>).

### F24 · P2 · Discard, search, settings and time labels are prototype-only

**Direction:** Design correction.

Source-verified: Discard restores defaultDraft; search recognizes fixed quote terms; custom/time labels are fixed; model import, app lock, hold and report export are toasts; privacy style/model copy can stay stale.

**Action:** Retain visual intent, replace demo handlers with real state, and label unimplemented preferences honestly.

**Evidence:** [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>), [design/assets/app.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/app.js>), [design/assets/screens.js:12](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:12>), [design/assets/screens.js:23](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js:23>).

### F25 · P2 · Accessibility claims require Android evidence

**Direction:** Both.

All 56 exports inspected. The package's 112-layout/browser checks are historical self-reports; they do not prove TalkBack, font scaling, keyboard insets, native targets or provider behavior.

**Action:** Use token definitions as a starting point and verify real semantic controls, 48 dp targets, both themes, system font scale and keyboard review.

**Evidence:** [design/assets/design-tokens.json](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/design-tokens.json>), [design/working/quality.json](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/working/quality.json>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt>).

### F26 · P2 · Some desired daily actions have no complete design or implementation

**Direction:** Both.

Neither full screen set nor active native UI defines reply-all/forward, outgoing attachment editing, a Drafts list, per-item reminder notification delivery, complete history/cache policy or summary action.

**Action:** Create scoped supplementary designs and acceptance contracts; treat these as additional product work and separate them from a visual reskin.

**Evidence:** [design/assets/screens.js](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/assets/screens.js>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt>), [docs/PRODUCT_DECISIONS_2026-10-10.md](<C:/Users/nande/Downloads/Mini/docs/PRODUCT_DECISIONS_2026-10-10.md>).

### F27 · P2 · Legacy references and packaging are not the current design contract

**Direction:** Design correction.

12 reference exports show alternative palettes/Focus layouts and older hold/WhatsApp acceptance claims. Guide mentions a PDF design book and QUALITY CHECKS.md, neither present in this ZIP. Board 05 contains an encoding typo.

**Action:** Use 28 Paper & ink screens/tokens as the visual baseline; archive references as history, correct copy and export packaging, and do not mix conflicting palettes or claims.

**Evidence:** [design/DESIGN GUIDE.md](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/DESIGN GUIDE.md>), [design/references/](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/references>), [design/boards/05-HONEST-OUTCOMES.png](<C:/Users/nande/Downloads/MINI-UI-Review-2026-10-14/design/boards/05-HONEST-OUTCOMES.png>).

### F28 · P1 · Handoff-written text is classified as sent-authored style data

**Direction:** MINI correction.

Source-verified: applyDispatchResult learns for HandoffOpened as well as AcceptedByProvider; authorshipForApprovedBody returns UserAuthoredSentMessage for manual text. Handoff does not establish sending.

**Action:** Resolve approved-writing versus confirmed-sent provenance explicitly; never teach from an unaccepted AI proposal or claim external send confirmation.

**Evidence:** [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:882](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:882>), [native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:934](<C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:934>).

## What is missing from both as a complete journey

New-email account selection; durable unsent Drafts listing; reply-all/forward; outgoing attachment editing; exact matched-message search positioning; scoped consent management; fully specified reminder notification delivery; model file import/validation; app lock and safe diagnostics; complete history/media retention/deletion policy; a user-requested summary journey. Some have domain foundations or proposed widgets, but that does not establish a finished user workflow.

Do not add all of these to the first visual change. The staged brief separates immediate correctness, daily navigation, AI/setup recovery and additional capability work.

## Visual direction and implementation notes

Use the current 28 Paper & ink screens, not a mixture of the 12 legacy palettes. Adopt semantic cobalt action, amber attention, green acceptance/settled and red failure, with written labels. Map bundled Manrope/Inter and the 4 px spacing grid to Android typography/dp/sp; preserve font licenses. Keep full exact content scrollable with anchored review actions. Expand compact visible controls to usable native targets; do not copy 10–11 px web metadata uncritically. Keep system font scaling/theme/reduced motion, keyboard and TalkBack as real device acceptance, not claims established by exported images.

Reserve the dashed boundary for an AI proposal; the design applies it to a manual WhatsApp reply too. Prefer one composer and avoid controls hidden only below scroll. The old board's encoding typo and missing PDF/QUALITY CHECKS packaging are handoff-quality issues, not evidence the product is unsafe. Do not carry invented fixed timestamps, provider acceptance receipts, model sizes or download services into the real app.

## Build order and owner decisions

1. Repair complete exact review, media account scoping, email handoff targeting, persistent uncertain outcomes, draft lifetime and category/priority independence.
2. Integrate Inbox/Focus/Console and preserve all current options, real account identity, filters and source-health recovery.
3. Complete honest setup, context presentation, local AI recovery and scoped consent; implement model import only with a validated lifecycle.
4. Scope additional actions/preferences separately and qualify on device/live providers before launch claims.

Android was explicitly selected as the target for this review. Keep final name, Work/Personal alias/category semantics, reminder notification defaults, app lock policy, history/cache lifecycle and ordering of additional composition/media/summary work as owner decisions. Do not delay the known corrections or ordinary screen integration to ask for every styling choice.

## Validation handoff

Use the companion Jcode brief's scenario checklist. It calls for source-account isolation; recipient review; stale/expired/revoked approvals; once-only dispatch; process-death unknown recovery; provider/chooser absence or cancellation; offline/storage/model failure; draft navigation/rotation/restart; attention/undo/timezone; scoped learning revoke; media identity and lifecycle; real-device accessibility.

The present review produced documents only. Existing fixture test counts and old device evidence in status files were read as historical records, not rerun or upgraded to current qualification. A future implementation must record its own evidence. Never use connectedDebugAndroidTest or destructive app reset when qualifying with stored accounts.
