# MINI product requirements

> **29 October 2026 override:** The user selected one hosted product LLM, initially serving Gmail, WhatsApp, Facebook Messenger, Instagram and LinkedIn. [The current Outdots decision and delivery plan](OUTDOTS_HOSTED_AI_PLAN_2026-10-29.md) supersedes conflicting local-model/optional-hosted direction and older initial connector scope below. AI suggests; users finalize actions. Historical implementation evidence and existing approval/isolation gates remain intact.

> **19 October 2026:** The user's current request authorizes product integration work beyond this historical documentation-only snapshot. Use the [unified roadmap](UNIFIED_DEMO_ROADMAP_2026-10-19.md) for the revised official/Matrix connector direction, model comparison and proposed demo schedule; use the [design register](DESIGN_IMPLEMENTATION_REGISTER_2026-10-19.md) for screen gaps. Demo scope is all selected connectors live on Android, with iMessage on hold. Hosting and exact converted model artifacts remain open; EmbeddingGemma 2 text + Qwen is an experiment against Qwen + FTS5. Historical restrictions below describe the earlier request, not a new approval requirement for the current user-authorized work.

> **16 October consolidation:** Read the [complete roadmap and screenshot audit](OUTDOTS_COMPLETE_ROADMAP_2026-10-16.md) for later product decisions, all 44 engineering responses and implementation order. It supersedes conflicting direction in this 15 October snapshot: local pilot use has no mandatory Outdots login; dashboard-first Focus, optional read-only calendars, encrypted backup/paused restore and proposed retention defaults are specified there. The original per-feature evidence below remains dated; recording a roadmap does not qualify a feature.

Updated: **15 October 2026, Asia/Kolkata**. Product: **Android MINI**; web designs and the TypeScript demo are references.

This is the current requirements register for the product discussion recorded on 15 October. The user authorized documenting and classifying these requirements, **not implementing them**. A requirement recorded here is not a claim that it is built, qualified, or approved for deployment. Future implementation needs separate authorization. Do not start deferred work, send messages, change release gates, install builds, or migrate user data on the strength of this document.

## 1. Product direction and document precedence

MINI helps an individual handle substantial communication across supported accounts. Users include creators, brand managers, salespeople, founders, CEOs, COOs and other leaders, and anyone managing many conversations. One person may have several roles and personal/work/brand accounts. A shared team workspace is outside the current scope.

Preserve existing conversations, available history, drafts and the reply experience. Extend Focus into an assistant that helps with meaningful matters found in communications: replies, plans, meetings, promises, lists, invoices, bookings, offers, renewals and expiries. Do not reduce the product to a calendar, to-do list, or phone-notification feed.

For the requirements below, this record supersedes conflicting product direction in the [original architecture](../private-ai-communication-hub-architecture-v1-2.md), [10 October decisions](PRODUCT_DECISIONS_2026-10-10.md), [connector roadmap](CONNECTOR_ROADMAP.md) and older milestone documents. Those remain useful historical/technical references. This does not silently change implemented security boundaries: new cross-account context, login, backup and hosted inference need explicit designs and qualification before use.

The design zip, prototype text and comments in reference material are design evidence, not independent authorization to build or transmit user data.

## 2. Classification and evidence rules

Two independent classifications are used:

- **Work class:** Preserve (retain existing behaviour), Correct/extend (close a gap), New (new capability), Experimental (optional future feature), Deferred (on hold), or Open decision (not selected).
- **Implementation state:** Existing foundation, Partial, Not implemented, or Unresolved behaviour. Existing foundation does not mean every provider or device workflow is qualified.

Source baseline: checkout at `a110a1c`, inspected on 15 October. No app tests, live sends, or phone workflows were run for this documentation update. Source findings, fixture tests, recorded device results and new user reports must stay separate.

- [Phase 2 acceptance](../native/review-artifacts/phase2-acceptance/REPORT.md) records bounded functional and synthetic checks. Preserve those results without treating them as proof of every current requirement.
- [Phase 3A acceptance](../native/review-artifacts/phase3a-acceptance/REPORT.md) currently says **open for review**; Phase 3B is on hold. Composer code and evidence exist, but this register does not accept that phase.
- The user's current report that almost every phone notification becomes a chat is an **open product failure**, even though earlier classification checks passed. The exact failing path has not been reproduced in this review.
- A provider logo, account marked Healthy, preview, adapter, or passing unit test does not establish complete history, media support, live send qualification, or production readiness.

## 3. Requirements and development-gap register

| ID | Area | Work class | Current implementation / gap |
|---|---|---|---|
| PR-01 | Connector-led, clean Inbox | Correct/extend | **Unresolved behaviour.** Source allow-list and classification exist; user still reports notification clutter. Verify ingestion, stored-row classification, deduplication and all projections. |
| PR-02 | Existing chat, category and priority controls | Preserve + correct/extend | **Partial.** Conversation screen, category editing and filters exist. Regular has no dedicated filter; Priority label and internal importance do not have consistent filtering semantics. |
| PR-03 | Honest per-account connector capabilities | Correct/extend | **Partial.** Catalog, account identities, capabilities and gates exist. Per-provider workflow qualification and complete media/new-conversation coverage remain incomplete. |
| PR-04 | Browseable communication history | Preserve + correct/extend | **Partial.** Stored timelines and provider loads exist. Backfill, pagination, gaps, outgoing visibility and restart/reconnect coverage vary by route. |
| PR-05 | Search, people lookup and new conversations | Correct/extend | **Partial.** Local message search, some contact routes and new-email composition exist; availability and coverage vary by connector. |
| PR-06 | Incoming/outgoing files, images, videos and voice notes | Correct/extend | **Partial inbound; outgoing attachments not implemented in the common send path.** Gmail/WhatsApp opening paths and limited Instagram media handling exist. Gateway rejects nonempty outgoing attachments. |
| PR-07 | Prepare, edit, review, approve and send | Preserve + correct/extend | **Existing individual-message foundation.** Exact approval, outbox and composer exist; multi-message preparation from Focus is missing. Live send eligibility remains gated per route. |
| PR-08 | Focus assistant and resumable assistant chats | New, reusing existing Focus | **Not implemented.** Current Focus is Needs you / Waiting / Later with conversation cards and actions, not an assistant chat/session system. |
| PR-09 | Meaning across conversations and platforms | New | **Partial signals only.** Simple deadline/request/change analysis exists. Scoped retrieval currently serves one conversation/account; permissioned cross-source facts and conflict detection are missing. |
| PR-10 | Personal setup and broader user knowledge | New | **Not implemented as the discussed experience.** Account setup and settings exist; role/people/topic/notification preference onboarding and broader memory do not. |
| PR-11 | Personal writing style and controlled learning | Preserve + correct/extend | **Partial.** Opt-in encrypted account/category/recipient profiles and history bootstrap exist. Broader controls and lighter preference feedback from unchanged approved AI drafts are missing. |
| PR-12 | Meaningful briefings and phone reminders | New | **Partial attention state only.** Focus lists and Later exist; personalised proactive notification scheduling and reminder lifecycle are missing. |
| PR-13 | Found collection | Experimental | **Not implemented.** Separate useful-information collection on Profile, also accessible through Focus; not an Inbox activity feed. |
| PR-14 | MINI login and optional device continuity | New | **Not implemented.** Provider connections and local persistence exist; MINI sign-in, encrypted backup/restore and key recovery do not. |
| PR-15 | Privacy and independent processing/storage choices | Preserve + new design | **Local foundations exist.** Hosted processing and encrypted continuity choices need separate designs; no upload or remote plaintext bridge is authorized. |
| PR-16 | Local model versus model API | Open decision | **Local drafting/interface exists; hosted inference is not implemented.** Keep both candidates open and decide after pilot validation. |
| PR-17 | Manual Light / Dark theme control | Correct/extend | **Themes exist; app preference missing.** Production currently follows the phone theme. Add a persisted in-app selector. |
| PR-18 | Text, image and file input; assistant voice later | New + deferred | **Partial text/media foundations.** General assistant file/image understanding is missing. Assistant voice interaction remains deferred; communication voice notes belong to PR-06. |

## 4. Detailed behaviour and acceptance

### PR-01 — Inbox eligibility and notification noise

- Inbox is built around actual conversations from deliberately connected communication accounts, with correct provider/account provenance. Provider sync must also classify non-message events: not every API event is a conversation.
- Notification permission alone must not create an authenticated account or enable arbitrary sources. Any retained notification fallback is explicit, source-limited and visibly incomplete; its final default/placement remains a design decision.
- Likes, recommendations, posts, jobs, app/service/status notifications, progress notices and notification-summary bundles must not create chats or inflate conversation, unseen, search or Focus results.
- Genuine messages must not be excluded merely because their body mentions a job, post, offer or like. An actual promotional email remains an email in its existing thread; useful information may be extracted separately into Found.
- Reconcile notification/API copies using reliable identity evidence; never bind unknown notification accounts by display name alone. Do not silently suppress a genuine message to obtain an attractive count.
- Correcting existing clutter must not delete stored records, history, accounts or user decisions without a separately agreed deletion policy.

**Acceptance:** a mixed batch of real communication shapes and non-message alerts produces only expected chats across Inbox, search, counts and Focus, before/after restart. A connector event plus a matching notification appears once without merging accounts. Reproduce the user's failing examples through the production pipeline using privacy-preserving evidence; earlier synthetic passes alone cannot close this issue.

### PR-02 — Preserve conversations; make filters consistent

- Tapping an Inbox conversation opens its existing person's/group's/organisation's platform conversation, including available history and the reply composer. It does not open an assistant workspace.
- Keep platform/account threads separate. Permissioned cross-platform assistance must not silently merge their messages or identities.
- Preserve account, All, Unseen in MINI and Done filtering and existing Family, Friends, Work and Sales labels.
- Importance is independent of category: a Family or Work conversation may also be Important. Priority filtering must reflect importance consistently across list, search, associated drafts and people routes.
- Add a Regular view, with its exact definition (uncategorised, normal importance, or an explicit category) resolved before implementation. Do not assume these are equivalent.
- Users can change category and importance inside the opened conversation. Explicit choices take precedence over suggestions.
- Done hides a chat from active views without deleting history. Undo restores its previous attention record; identical repeats do not resurrect it; a genuinely new message can resurface it.
- Seen in MINI, Done, source-app read status and provider archive are different states.

**Acceptance:** a chat marked Work + Important appears in both relevant views; removing importance preserves Work. Search scope changes without retyping. Done/Undo/restart/duplicate/new-message workflows preserve history, drafts and account identity. Opening a chat does not falsely acknowledge provider read or mark it Done.

### PR-03 — Connector contract and qualification

For each **provider + account type + route**, record and expose the actual supported subset:

1. Authentication/device linking, stable identity, scopes, revocation, disconnect and reconnect.
2. Incoming and outgoing visibility; direct/group/thread/channel coverage; participants and account identity.
3. Historical range, selected folders/spaces, backfill/pagination, gaps, deletions, edits and disappearing content.
4. New-message updates, incremental checkpoints, reconciliation, duplicates, rate limits and Android background constraints.
5. Local search versus provider search; people/contact lookup; ability to start a conversation versus only reply.
6. Incoming and outgoing media capabilities **separately for every media type**, including format/size limits and voice-note representation.
7. Exact-review send route, reply windows/templates, eligibility, ambiguous outcome handling and supported receipts.
8. Processing location, official versus unofficial route, provider AI-use eligibility, health and last successful check.
9. Evidence state: source-only, fixture-tested, synthetic-device-tested, live-account-qualified or released, per capability.

Unknown/unsupported capabilities remain off with an explanation. Opening an account or seeing Healthy is not blanket acceptance. Do not replace personal access with a bot/business account without explaining that it covers different conversations.

**Acceptance:** demonstrate the claimed connect → history → search → receive → new conversation/reply → supported media → process death/reconnect workflow per account route. Record unavailable steps honestly. Approval and owner-evidence release gates remain enforced; no tests/defaults may clear them. Qualify each capability rather than using one global "complete connector" badge.

### PR-04 — History

Retain browsable incoming/outgoing timelines and existing drafts. Load older history where the actual route permits it; retain coverage dates, gaps and partial-history notices. Resume bounded pagination/checkpoints without losing or duplicating messages. Source edits/deletions and disappearing content must invalidate dependent context according to an explicitly defined retention policy.

**Acceptance:** initial and subsequent sync, interrupted backfill, expired cursors, process death and reconnect preserve ordering/account isolation. Notification-only and provider-limited histories cannot be presented as complete. A failed history request keeps existing history usable and offers recovery.

### PR-05 — Search and starting conversations

Search permitted stored people/messages and supported provider history, making the searched coverage clear. Filter consistently by account, category, importance, unseen and active/Done state. Offer new-conversation routes only where supported; show New contact explicitly. The outgoing account, recipient and thread must be unambiguous.

**Acceptance:** search works against the real store, returns the correct scoped history, reacts to Done/filter changes without retyping and distinguishes no results from a search failure. Start/reply routes are validated separately for each provider; unsupported initiation is explained, not simulated by notification observations.

### PR-06 — Media and files

Target text/links, photos/screenshots, videos, PDFs, documents, spreadsheets, other permitted files, audio attachments and channel voice notes. Establish a per-route receive/preview/download/upload/send matrix with explicit MIME/size restrictions; receiving a media marker alone is not complete support. Voice-note sending may differ from sending an audio file.

Keep media account-scoped and protected at rest; use bounded downloads and temporary export cleanup. Review must bind the actual selected outgoing files to the exact action. Attachment contents are not AI context unless deliberately processed under the user's permitted mode; disclose omissions and unavailable/expired media.

**Acceptance:** qualify each advertised incoming/outgoing type with real provider workflows, wrong-account rejection, unavailable/oversize/unsupported files, restart and expired exports. Confirm filename, type, size, preview/open and sent bytes where supported. No placeholder or fixture establishes live upload support. Current `attachments_not_supported_yet` is a development gap, not a complete media route.

### PR-07 — Drafting and sends

Reuse manual writing, instruction-based drafting/refinement, draft versions, cancellation/edit preservation, encrypted draft restoration and source evidence. MINI prepares in the user's direction; the user edits or approves each exact outgoing message. A multi-platform request prepares **separate** messages with **separate** approvals, not blanket permission to act.

Preserve binding to account, recipients/To/Cc/Bcc, subject/body, attachments, route, reply target and relevant policy/source versions. Changes invalidate prior approval. Handoff opened is not sent; accepted is not delivered; an unknown outcome is not automatically retried. Calendar edits, purchases and other external actions are not implicitly authorized by a communication request; their scope/review flow remains open.

**Acceptance:** each prepared message reaches the existing exact-review flow; edits/account/recipient/file changes invalidate approval. Unsupported sends explain the route. Unknown outcomes stay reconcilable. Preserve existing draft and provenance behaviour during navigation, rotation and restart.

### PR-08 — Focus assistant

Retain useful Needs you / Waiting / Later actions while adding short useful briefing points, text interaction, a list of outstanding matters and separate resumable assistant conversations. Users can select a matter, continue working on it and open its source conversation. One matter may involve several chats; one chat may contain several matters.

The exact layout is unresolved: design an intentional flow between briefing, assistant input, conversation and outstanding matters. A generic text box placed above the old list is not an accepted design specification. Current Inbox remains a communication list.

**Acceptance:** a user can start/resume a Focus conversation, understand its source coverage, open a cited original chat, prepare relevant replies and return without losing either assistant history or communication drafts. Permission and unavailable-source states are clear.

### PR-09 — Cross-platform meaning

Identify meaningful facts, requests, commitments, lists, plans, bookings, invoices, renewals and expiries from authorised communications. Preserve sources, certainty, dates/time zones, status and revisions. Distinguish proposed/confirmed, cancelled/superseded and resolved matters. Surface uncertainty instead of inventing a commitment.

Example: a Gmail meeting tomorrow at 12 and a WhatsApp lunch with parents tomorrow at 12 can produce a supported conflict explanation and user-directed reply preparation. Duration/time ambiguity may require clarification. Other useful scenarios are finding an invoice across accounts or recognising a follow-up promised in one conversation and fulfilled in another.

Cross-source retrieval needs explicit permitted scope and a separate policy; do not remove existing account isolation. Do not infer that matching names identify the same person. Keep send approvals scoped to one actual account/thread even when reasoning uses several authorised sources.

**Acceptance:** source-backed scenarios cover a real overlap, no overlap, changed/cancelled plans, uncertain time and conflicting evidence. Revoked/removed/unavailable sources are not silently used as current facts. Identity linking and permission UX must be agreed before implementation.

### PR-10 — Setup and preferences

Intended sequence: MINI sign-in, connect supported accounts, then lightweight personalisation with ongoing corrections. Learn roles, brands/accounts, important people/organisations/topics, broad-versus-important-only coverage and family priorities. Do not require one exclusive user persona or contact-by-contact onboarding for all style learning.

**Acceptance:** users can skip nonessential questions, revise choices later, inspect what is remembered and correct a priority suggestion. Initial setup does not authorise sending or silently enrol all phone notifications. Exact question set and defaults remain to be designed.

### PR-11 — Style learning

Preserve opt-in local learning from authorised user-authored outgoing history and edits, account/category/recipient separation, compact encrypted profiles and deduplicated samples. Adapt to relationship, brand and conversation context; user-authored writing is stronger evidence than AI text.

Unchanged approved AI drafts may supply **lighter preference feedback**, not authored-style examples or an equal-strength learning signal. Approval is not consent to train shared model weights. Provide inspect/correct/pause/clear controls; distinguish inferred relationship knowledge from writing style.

**Acceptance:** historical and newly sent samples do not double-count; inbound and unchanged AI text never become user-authored examples; weak evidence does not imply confident personalisation. Preserve account isolation and consent/revocation behaviour. Weighting, confidence thresholds and broader memory controls remain design work.

### PR-12 — Briefings and important notifications

Show short meaningful points in Focus and selective phone notifications according to user priorities. Include the reason, source and useful action. Deduplicate alerts, reflect changes/cancellations, stop reminders for resolved matters and respect notification/privacy choices. Quiet hours, urgency thresholds and cadence remain open.

These are notifications **sent by MINI**, separate from notification observations **received by MINI**. Existing Later only maintains an in-app snooze; it is not an OS reminder scheduler.

**Acceptance:** important matters can alert while routine conversations remain browsable without alert spam; repeat ingestion does not repeat the alert; Done/changed/cancelled matters reconcile reminders across restart. Lock-screen disclosure and Android permission/battery limits are handled honestly.

### PR-13 — Found (experimental)

Provide a small separate collection on the proposed Profile page, also callable from Focus. It can hold coupons, useful promotions, invoices, insurance/other renewals, expiry dates and other information meaningful to that user. Preserve source, relevant date, conditions, uncertainty and lifecycle. Finding an offer must not purchase/redeem anything or turn the source into an artificial chat.

**Acceptance:** a user can find an item through Profile and Focus, open its source and see whether it expired or changed. Core Inbox remains useful with this experiment off. Automatic versus on-demand discovery, initial item types and retention policy remain open. Existing Console is not yet the proposed Profile.

### PR-14 — MINI login and continuity

Provide MINI login separately from provider authorisation. On a new device, reconnect provider accounts; optionally restore permitted preferences, knowledge, Focus chats and saved information. Do not assume provider sessions or all raw mailbox history transfer. Raw-history backup scope, multi-device conflicts and merge policy remain open.

**Acceptance:** the device-only choice makes no private-content backup; permitted encrypted restore recovers only selected state. A MINI login alone is not a decryption/recovery design. Define user-held keys, device enrollment, recovery/loss, logout and deletion behaviour before building the service. Reconnect accounts without borrowing another account's history or approval.

### PR-15 — Privacy and mode controls

User content must not be sold, used for advertising, reused for unrelated purposes or used to train a provider's/shared model. User-local personalisation is a separate, controlled function. Preserve encrypted local stores, protected credentials, content-free logs, account isolation and exact-action controls.

Two choices must be independent: **where AI runs** (device-only or an optional qualified hosted route) and **whether permitted state is backed up** (device-only or optional encrypted continuity). No-training terms alone do not establish zero retention or exclusive user access. Define and qualify content access, retention, subprocessors, metadata, key ownership and deletion before enabling a hosted route; do not promise that ordinary API encryption supplies user-only processing.

**Acceptance:** strict local mode has no hosted-inference content transmission; backup choice does not silently enable hosted AI or vice versa. Cloud-disabled/failed requests cannot silently fall back to another provider. No provider, SDK, remote bridge, hosted service or private-data upload is selected or enabled by these requirements.

### PR-16 — Model decision after pilot

Keep local models and model APIs as candidates. Preserve a replaceable inference boundary. Choose only after pilot evaluation of actual MINI tasks: usefulness/accuracy, context/source handling, privacy/access/retention, latency, offline behaviour, battery/memory/device coverage, reliability and cost. Existing Qwen/MediaPipe code and earlier Gemma/LiteRT-LM candidate notes are not a final product selection.

**Acceptance:** compare candidates on the same representative permitted tasks, document failure cases and evidence, then record a separate decision. Do not upload private pilot content or switch runtimes based on this document alone.

### PR-17 — Manual Light / Dark preference

Add an in-app **Light / Dark** selector; "normal" means Light. Both Paper & ink palettes already exist. Remember the choice after restart and apply it consistently to Inbox, Focus, Console/Profile, conversations, forms, evidence, media surfaces and review dialogs. A Follow system option is optional, not a substitute for the two requested manual choices. Placement in current Console versus future Profile is a design detail.

**Acceptance:** switch either way while reading a chat or editing a draft; navigation position, draft/recipient/account and pending review state remain intact. Restart preserves the preference. Check readable contrast, large text, system bars and keyboard visibility. Theme changes never approve, send, clear or regenerate content.

### PR-18 — Assistant inputs and voice scope

Target text, photos/screenshots and supported files for Focus, with transparent attachment processing and source references. The current text drafting and media-opening paths do not establish image/document understanding. Define file limits and extraction behaviour; never claim unseen attachment contents informed an answer.

**Acceptance:** admitted inputs are processed only in the permitted privacy mode, with unsupported/encrypted/unreadable files handled clearly. Assistant voice input/output is **deferred**. Receiving, playing and sending channel voice notes remains PR-06; it is not deferred merely because assistant voice is.

## 5. Provider scope and route classification

This is the implementation snapshot and product sequencing, not a new entitlement or blanket official-API claim. [ConnectorCatalog](../native/shared/src/commonMain/kotlin/app/mini/hub/connector/ConnectorCatalog.kt) describes available routes; [ConnectRoutePolicy](../native/androidApp/src/main/kotlin/app/mini/hub/android/connector/ConnectRoutePolicy.kt) controls what the UI actually offers. Catalog entries and visible release routes are not live capability proof.

| Provider / account route | Current foundation | Requirement classification / boundary |
|---|---|---|
| Gmail | Official API adapter, Android account connection, history/thread loading, incremental sync and gated text sending; release-visible. | Extend/qualify history, search, supported media and full workflow. Existing account/device evidence is bounded; send gates still apply. |
| Outlook | Official Graph mail adapter, account flow, Inbox/Sent delta and gated text sending; release-visible. | Extend/qualify the claimed folders/history, search, outgoing attachments and device workflows separately. |
| Slack | Official adapter; debug-only, labelled not live-qualified. | Work-account candidate; installation/scopes and history limits apply. Qualify new conversation and each media type, not just text replies. |
| Teams | Official work/school chat adapter; debug-only. | Work-account candidate; tenant permissions and chat/channel coverage differ. Personal Teams is not established by this route. |
| Google Chat | Official user-auth adapter; debug-only. | Candidate for permitted spaces/messages; scope, retention and app configuration apply. Full initiation/media coverage is not established. |
| WhatsApp personal | Phone-local companion implementation packaged for debug using whatsmeow; reported account health is not full qualification. | **Unofficial linked-device route**, not a personal official API. Qualify history/media/send/reconnect/background completeness and disclose its route. Do not replace with Business Cloud API. |
| WhatsApp Business mobile app / Business Cloud API | Bridge foundation and a separately described/deferred Business Cloud route. | Distinct account/operating modes. Neither inherits qualification from personal WhatsApp. Business API access does not establish personal family-history coverage. |
| Telegram | Bot adapter exists, debug-only. Personal TDLib route is deferred/not implemented. | Bot conversations are not a personal inbox. Personal-client work and AI/content-use eligibility need separate qualification; neither is resolved by having a token. |
| X | Official DM adapter, debug-only. | Candidate with endpoint access/budget and bounded history. Official lookup documentation currently specifies 30-day retrieval; not unlimited backfill. Qualify media/initiating separately. |
| Instagram | Official professional-account adapter and media markers exist; old bridge/fallback foundations also exist. | **Further work deferred by current user direction.** Professional API does not establish ordinary personal-inbox access. Current route policy does not offer this as a released connection. |
| Facebook / Messenger | Notification fallback; Page/business route described but not a complete released connector. | **Further work deferred.** Page messaging is distinct from a personal Messenger inbox. |
| LinkedIn | Notification/activity and bridge foundations; no qualified personal official messaging route. | **Further work deferred.** Sign-in does not grant personal messages; partner routes require actual access. |
| Signal | Notification fallback only. | No qualified full connector in the product. Do not claim complete history, voice/media or sending from observation. Respect redaction/disappearing content. |
| LINE | Notification fallback; Official Account route described/deferred. | Official Account/bot conversations are distinct from personal history. Full personal requirements are unqualified. |
| Discord | Notification fallback; bot route described/deferred. | Bot visibility is not a personal DM inbox. No user-token automation requirement is approved. |
| Google Messages / SMS / RCS | Notification fallback; older on-device roadmap proposal, no completed full connector. | No qualified personal full-history route. SMS role, RCS access and any unofficial linked-device route are separate unresolved choices, not assumed official access. |

Relevant provider references checked in the 15 October discussion: [Gmail](https://developers.google.com/workspace/gmail/api/guides), [Outlook](https://learn.microsoft.com/en-us/graph/outlook-mail-concept-overview), [Slack history](https://docs.slack.dev/reference/methods/conversations.history/), [Teams chat creation](https://learn.microsoft.com/en-us/graph/api/chat-post?view=graph-rest-1.0), [Google Chat](https://developers.google.com/workspace/chat/overview), [WhatsApp Business Platform](https://www.postman.com/meta/whatsapp-business-platform/overview), [Telegram TDLib](https://core.telegram.org/tdlib), [Telegram API terms](https://core.telegram.org/api/terms), [X DM lookup](https://docs.x.com/x-api/direct-messages/lookup/introduction), [Instagram](https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api), [Meta conversations](https://www.postman.com/meta/messenger-platform-api/folder/22794852-255610cd-47f5-4f4d-b3fa-71aec360be9a), [LinkedIn access](https://learn.microsoft.com/en-us/linkedin/shared/authentication/getting-access). Recheck provider terms/permissions/limits before implementation and release. Read access alone does not establish permission for AI processing.

## 6. Suggested delivery groups (not authorization or a launch commitment)

| Group | Requirements | Exit criterion |
|---|---|---|
| A — Preserve trust and fix communication correctness | PR-01, PR-03–07 and existing PR-02/11/15 safeguards | No alert-created false chats in the verified scenarios; supported account workflows and truthful coverage proven without data loss or weakened gates. |
| B — Improve existing daily controls | PR-02 corrections and PR-17 | Category/importance semantics agreed; filters consistent; manual themes persist without disturbing drafts/history. Theme work need not wait for all future connectors. |
| C — Build the Focus experience | PR-08–10, PR-12, PR-18 text/file/image slice; extend PR-07/11 | Interaction/permission design agreed; source-backed pilot workflows work across the supported account subset with separate approvals. |
| D — Separate continuity and experimentation | PR-13–15 | Found remains optional; privacy, login, recovery and continuity designs qualified before any service uses private data. Login onboarding must use the actual available auth path, not a fake placeholder. |
| Decision track | PR-16 and the choices below | Model decision follows pilot validation; maintain local operation while evaluating optional designs. |

Instagram, Facebook/Messenger and LinkedIn work, assistant voice interaction, shared team workspaces and iOS implementation are outside this Android change set. The first three and assistant voice are explicitly deferred; web remains a reference. Their listing is not removal of existing user history. Do not infer a phase number, start Phase 3B, or revive an older connector execution order from these groups.

## 7. Decisions still required

1. Regular filter semantics and category/importance migration for existing choices.
2. Focus layout, assistant-session organisation and how outstanding matters relate to chats.
3. Cross-source consent, identity linking and source-revocation behaviour without weakening account isolation.
4. Optional notification fallback defaults/placement and the exact misclassification reproductions.
5. Provider/account subset for the pilot, route qualification and per-type media limits; no simultaneous all-provider launch is agreed.
6. Reminder timing, urgency, quiet hours and lock-screen disclosure.
7. Found discovery defaults, initial item types and retention.
8. Broader personal memory controls and lighter AI-approval feedback weighting.
9. MINI authentication, device enrollment, encrypted backup scope, key recovery and multi-device conflict policy.
10. Hosted inference eligibility, access/retention/metadata boundaries and mode switching; final model/runtime choice after the pilot.
11. Actions beyond communication preparation/sending, including calendar writes, remain separately scoped.

## 8. Implementation evidence pointers

- Inbox/filtering: [MiniInbox](../native/androidApp/src/main/kotlin/app/mini/hub/android/ui/MiniInbox.kt), [InboxScope](../native/shared/src/commonMain/kotlin/app/mini/hub/inbox/InboxScope.kt), [ConversationLabels](../native/androidApp/src/main/kotlin/app/mini/hub/android/ConversationLabels.kt), [classification](../native/shared/src/commonMain/kotlin/app/mini/hub/observation/CommunicationClassification.kt), [source policy](../native/shared/src/commonMain/kotlin/app/mini/hub/observation/CommunicationSources.kt).
- Conversation/composer/review/media: [ChatUi](../native/androidApp/src/main/kotlin/app/mini/hub/android/ui/ChatUi.kt), [InboxScreen](../native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxScreen.kt), [InboxViewModel](../native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt), [send gateway](../native/shared/src/commonMain/kotlin/app/mini/hub/connector/SendContracts.kt).
- Current Focus/Console: [MiniDailyApp](../native/androidApp/src/main/kotlin/app/mini/hub/android/ui/MiniDailyApp.kt). Current system-driven theme: [MiniTheme](../native/androidApp/src/main/kotlin/app/mini/hub/android/ui/MiniTheme.kt), [MainActivity](../native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt).
- Context/learning: [retrieval](../native/shared/src/commonMain/kotlin/app/mini/hub/retrieval/ScopedRetrievalEngine.kt), [understanding](../native/shared/src/commonMain/kotlin/app/mini/hub/understanding/MessageUnderstanding.kt), [style](../native/shared/src/commonMain/kotlin/app/mini/hub/style/AdaptiveStyleEngine.kt), [runtime/history bootstrap](../native/androidApp/src/main/kotlin/app/mini/hub/android/MiniHubRuntime.kt), [inference interface](../native/shared/src/commonMain/kotlin/app/mini/hub/intelligence/Inference.kt).

These pointers support the snapshot, not an assertion that fresh tests passed. Future evidence updates must identify the tested build, account route and whether the result is source, fixture, synthetic device or live-provider evidence, without recording private communication content.
