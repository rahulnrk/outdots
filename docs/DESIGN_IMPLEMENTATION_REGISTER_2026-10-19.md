# Calm control: design correction and implementation register

> **Latest hosting correction:** Phone-local Android clients are selected for WhatsApp, Messenger, Instagram and LinkedIn. Their normal account screens need on-device login/session/challenge/reconnect states, not server-address or always-on-computer setup. Notification-only remains an explicitly limited fallback. Show processing location from the actual route; a shared connection UI must not imply that every provider has the same hosting or login mechanism. Slack's route remains separately unresolved.

> **Latest scope:** All selected connectors must be live on Android; iMessage is on hold. Android Messages remains included, with SMS/MMS versus RCS unresolved. E3 must distinguish Qwen generator readiness from optional EmbeddingGemma 2 text retrieval/index readiness. See [hosting and AI options](BRIDGE_AND_AI_OPTIONS_2026-10-19.md). Bridge hosting remains undecided.

19 October 2026. Companion to [unified roadmap](UNIFIED_DEMO_ROADMAP_2026-10-19.md). Scope: current local v3 screen source/annotations, selected rendered exports and native source inspection. This is a gap register, not proof that every hidden app route is missing. The supplied binary `.fig` remains unchanged; local v3 exports and the older `Figma screens` folder must not be conflated.

## Required design corrections

1. **A2/A3/A6/E2/C5: routes.** Official Gmail/Outlook/Teams and Android SMS are distinct from Matrix-linked WhatsApp/Messenger/Instagram/LinkedIn/Slack/iMessage. Add provider-linked setup, QR/authentication, bridge host unavailable, expired session, initial history, partial history, reconnect and revocation. Keep notification-only C5 as an explicitly selected fallback, not the normal WhatsApp route. Design state must derive from actual capability snapshots.
2. **A1/E1/E2: privacy.** “On this phone” can describe AI/storage but must not imply bridge processing occurs there when a remote host is used. Show chosen host and processing boundary. Do not change this claim until topology is decided.
3. **E3: real model management.** Replace disabled-only Add model with import/select/verify/loading/cancel/ready/incompatible/corrupt/out-of-memory states. Show exact Qwen/Gemma artifact and backend, measured readiness and device results. Manual Inbox/reply remains usable without a model. Move the state legend off the everyday user screen into the design system.
4. **D3/D4/D5/D8: outcome semantics.** The visible D3 footer says accepted means “not that it was read”; it must also distinguish acceptance from delivery. Matrix acceptance is a separate stage from the external provider's acceptance. Several replies track each independent action, with no implied atomic send or global approval.
5. **B1/B2/B6/E7: evidence.** Preserve consent-scoped sources, correction lineage and uncertainty. An apparent time overlap without durations is a possible clash, never a confirmed conflict. Count matters, not the conversation attention list. Conversation Done and matter Complete stay separate.
6. **B3/E5: reminders.** Show persisted in-app timing separately from Android notification availability. Custom date/time/timezone, denied permission, quiet hours, reboot and cancellation need actual behavior. Current snooze explicitly schedules no OS notification.
7. **A6: progress.** A percentage is permitted only with a defensible denominator. Otherwise use indeterminate loading plus a coverage description. A connected account does not establish full history.
8. **X1/X2 and all sheets: adaptation.** Resolve documented C1/X1 subtitle/search collision; validate keyboard/nonempty drafts, 360×640 and 2× font scaling in both themes. Local CSS layout checks do not qualify Compose hit targets or TalkBack.
9. **Component library.** Folder README still marks control states and product components pending in Figma. Finish matter/evidence/account/message/recipient components and error/disabled/loading states before calling the library complete. Treat paid-plan theme-mode limitations as a tooling constraint, not an app behavior.
10. **E8: recovery.** Do not imply device Keystore keys can simply be restored to another phone. Specify portable encrypted payload, account reauthorization, duplicate merge, wrong password, interrupted restore, version compatibility and retained-data behavior before enabling the flow.

## Screen-to-implementation register

Each entry gives the current evidence and the completion test. Related screens share infrastructure; do not implement independent state for each mock.

- **A1 Welcome:** first-run journey is not established by inspected production navigation. Add skippable local onboarding; existing users and stored accounts must not be reset.
- **A2 Choose sources:** catalog/account UI exists; selected route matrix differs. Selection is not authorization. Complete only when the correct provider setup is reachable and unsupported choices are explained.
- **A3 Notification access:** listener/system access exists. Prove return-to-app permission refresh and optional fallback; no blanket notification dependence for API/bridge accounts.
- **A4 Focus scope:** consent storage/control foundation exists. Prove account exclusions and revocation invalidate in-flight extraction and visible matters.
- **A5 What matters:** no end-to-end roles/important-people workflow established. Save explicit choices only; suggested people remain unselected.
- **A6 Ready:** account health exists. Bind each status to real sync/capability evidence and show resumable incomplete setup.
- **B1 Dashboard:** production Focus renders attention entries. Replace with scoped matter projection, limited useful overview and remaining count; test against real observations.
- **B2 Matter evidence:** matter storage/coordinator exists. Add evidence navigation, durable edits/corrections and separate Complete/Dismiss semantics.
- **B3 Later:** in-app conversation snooze exists; OS reminder is explicitly absent. Add durable matter reminder and recovery/permission behavior.
- **B4 Ask Focus:** resumable assistant session is not established in inspected UI. Persist session/query/draft references and cite only authorized evidence; restore after restart.
- **B5 Quiet day:** current empty list exists. Derive quiet state from eligible matter scope and distinguish missing source coverage.
- **B6 Clash:** cross-account workflow not established. Require separate explicit consent, uncertain-duration wording and individual source evidence.
- **C1 Inbox:** real native list exists. Verify filtering/deduplication/count parity and compact drafts/outcomes on device; reuse current work.
- **C2 Filters:** source/account/status filtering exists in several controls. Match staged Apply/Cancel/Reset design without losing older category choices.
- **C3 Search:** scoped search exists. Verify query retention while scope changes, error versus no results, keyboard and source navigation.
- **C4 Email conversation:** timeline/coverage/compose exists. Qualify readable identity, recipients, correct range and attachment availability; no unjustified full-history claim.
- **C5 WhatsApp fallback:** notification capture/handoff exists alongside phone-local runtime. Preserve fallback while adding separate Matrix-connected conversation states.
- **C6 Conversation menu:** attention actions exist. Keep conversation Done separate from matter completion and share reminder controls.
- **D1 AI compose:** editable local drafting exists. Integrate selected backend and preserve user edits under late/cancelled generation; keep instruction separate from outgoing text.
- **D2 Why draft:** `WhyThisDraftView` exists. Show actual evidence/template/style provenance from the selected generation, including missing context.
- **D3 Exact review:** action core/review exists. Match actual recipients, route, subject, attachments and expiry; bridge route must bind provider session/room ownership.
- **D4 Accepted:** provider outcomes exist. Label acceptance level accurately; do not mark matters complete merely because a reply was accepted.
- **D5 Could not confirm:** durable unknown outcome exists. Prove restart retention and no auto-resend; checking the source must not silently resend.
- **D6 Handoff:** launcher and HandoffOpened exist. Prove failure to open, self-reported completion and preserved draft; never call app opening delivery.
- **D7 New email:** composition exists. Verify validated recipients and independent subject/new-message identity through save/reopen; no synthetic reply anchor.
- **D8 Several replies:** independent action core exists, but a persisted orchestration UI is not established. Save progress and require per-message review.
- **D9 Clash review:** share exact-review machinery with D3 while retaining correct draft/action identity and D8 return path.
- **D10 New-email review:** share exact review without turning a new composition into a reply.
- **D11 Expired approval:** expiry/invalidation rules exist. Provide reachable re-review state; verify content, source and permission changes invalidate approval.
- **E1 You:** current Console exists. Rename display label without breaking saved identifiers; group Accounts, Focus, AI, learning, appearance, privacy and recovery as reachable operations.
- **E2 Account:** capability snapshots/account management exist. Replace raw gate identifiers with actionable descriptions; show bridge host and external session separately.
- **E3 AI:** fixed MediaPipe artifact exists. Implement explicit model selection/import and actual readiness; no device support claim based solely on RAM.
- **E4 Writing style:** opt-in learning exists. Verify per-account scope and pause/off/clear distinctions; inspect/edit learned patterns without widening prior consent.
- **E5 Privacy:** permission handling exists. Notification previews/quiet hours/app lock are not established as a complete implemented settings flow; bind each control to actual behavior.
- **E6 Storage failure:** blocking RuntimeStorage failure exists. Keep content-free diagnostics; no destructive recovery or claim of intact data without evidence.
- **E7 Found:** separate consent-scoped insight feed is not established. Retain as tracked work; verify source changes and account exclusions before presenting results.
- **E8 Backup:** portable encrypted backup/restore is not established. Specify format/key derivation/transactional recovery and prove restore on another device before enabling.
- **X1 Small screen:** design exists. Device verification required at 360×640, both themes and 2× text.
- **X2 Keyboard:** ime padding exists. Prove real nonempty editor and Review remain reachable, AI collapses and manual edits survive rotation.

## Completion evidence per item

Record code location, focused fixture/host check, device/build, live-provider result where applicable, screenshot and remaining limit. “Designed”, “implemented”, “fixture-tested” and “live-qualified” are independent labels. Do not reproduce private message text in this register or commit screenshots with real accounts.
