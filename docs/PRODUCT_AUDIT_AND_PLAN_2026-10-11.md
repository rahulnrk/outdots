# MINI: product audit and execution plan

**Assessment date:** 11 October 2026, Asia/Kolkata. **Base commit:** `0bf2c95`. The working tree was changing during this review; see the verification record below. This report changes no product behavior.

## Verdict

MINI is a substantial Android engineering prototype with real local inference, encrypted stores, inbox workflows and connector infrastructure. It is **not yet qualified for the agreed ten-service pilot**. The main gap is between individual components existing and a person being able to trust the complete daily workflow: connect accounts, receive an accurate history, understand what changed, draft a useful response, approve exactly what will happen, and recover safely when something fails.

Keep the existing architecture. The biggest improvement will come from correcting shared message and action contracts, proving complete workflows, and qualifying phone-local bridges. Adding more screens, models or nominal connector entries before those foundations are reliable will increase rework.

I would not assign a percentage complete. A catalog entry, a fixture passing and a service working reliably on a phone are different milestones; averaging them would conceal the launch blockers.

## Scope and evidence limits

The review cross-examined the available product conversations, repository research and decision documents; the native message ingestion, connectors, persistence, actions, retrieval, understanding, style and inference paths; Android UI/runtime wiring; the TypeScript reference/demo and publisher boundary; and test/device reports. Relevant conversation titles included “Harness Brainstorming Design,” “Cross examine product strategy,” “Product Problem Analysis,” “Research connector integration docs,” “Review progress against product,” “Design Product Architecture,” “UI UX Productivity Design,” “Build Reliability Harness,” and “Building a Local AI Model.” Some older conversation content was unavailable or represented by opaque references. This is not a claim to have recovered every historical message or exhaustively proved every line of code.

Findings below distinguish code inspection, fresh automated checks and historical device evidence. No live provider send, new account authorization, phone installation, destructive device test, production deployment or iOS build was performed for this audit. A physical Android device was connected at the final verification checkpoint; its presence alone proves no product behavior.

### Product decisions carried forward

| Decision | Plan constraint |
|---|---|
| Audience | General everyday users, including people with both personal and work communications. |
| Product | Unified inbox plus an AI dashboard explaining purpose, urgency, deadlines, required action, unanswered requests, waiting and important changes. |
| Privacy | AI and bridge plaintext processing on the phone. No remote bridge mode in this pilot. Original communication services still receive the traffic required to operate their accounts. |
| Pilot services | APIs: Gmail, Outlook, Teams, Slack, Google Chat, X DMs. Phone-local bridges: WhatsApp, Facebook Messenger, Instagram, LinkedIn. All ten remain the pilot target. |
| Platform | Android pilot first. iOS development starts at Android pilot launch and incorporates pilot lessons. |
| History | Import available authorized history for browsing; show provider limits and incomplete coverage. Browsable history and bounded AI context are separate. |
| Learning | Opt-in local learning from the user's own authorized historical writing and accepted edits, without contact-by-contact setup. Preserve account boundaries. |
| Actions | Draft on request; exact human review before sending. Suggestions do not create commitments automatically. |
| Language | English MVP; Hindi/Telugu exploration does not count toward English qualification. |
| Still unresolved | Attachment processing and history/cache retention policy. The older attachment-size/storage formula is superseded. Cross-platform identity merging is not approved. |

These conversation decisions supersede the open bridge-location/platform questions and the earlier “simultaneous launch not agreed” statement in [the October 10 decision record](C:/Users/nande/Downloads/Mini/docs/PRODUCT_DECISIONS_2026-10-10.md). Broader provider ambitions remain future scope. A Telegram bot is not a personal inbox, and WhatsApp Business mobile access must not be represented as the Business Cloud API.

## Where the product stands

| Area | What exists | What remains unproven or incomplete |
|---|---|---|
| Native foundation | Compose Android app, KMP shared rules, SQLCipher/Keystore, notification ingestion and deduplication | Safe failure/recovery across the assembled runtime, large-history responsiveness, ten-account background endurance |
| Inbox and attention | Conversation view, review dialog, priority/follow-up state, deterministic explanations | Accurate automatic unanswered/waiting/change detection from message direction and chronology |
| APIs | Six pilot API adapters and account/capability machinery; historical live Gmail evidence | Complete pagination/history, current app entitlements, account-specific live read/reply/reconnect qualification for every service |
| Bridges | Remote Matrix client adapter for several networks | Embedded phone-local provider sessions; personal Facebook Messenger integration; reconnect, backfill and forwarding proof |
| AI runtime | Real MediaPipe inference, lifecycle/cancellation recovery, bounded requests | English usefulness gate, ordinary-phone resource qualification, reliable model installation and updates |
| Personalization | Default-off consent, historical ingestion hooks, encrypted profile persistence and restart evidence | Cross-account isolation of category profiles, correct authorship/recipient provenance, live benefit to generated drafts |
| iOS | Conditional KMP targets | Native app, Apple-platform compilation and integration qualification; JVM tests do not prove portability |
| Distribution | Buildable debug APK, reference publisher service | Consumer onboarding, signed model distribution, release qualification and operational support |

The architecture worth retaining includes account-scoped IDs, explicit capability gates, bounded ingestion, atomic sync checkpoints, a single inference owner, encrypted local storage, and separate states for provider acceptance, uncertain outcome and opening another app. Several important safeguards already exist; the missing work is often in how they are connected.

## Highest-priority findings

Severity here is release priority. These are reproducible code-path observations or documented test outcomes, not claims that a user's messages were actually leaked, lost or duplicated.

### 1. Sending is not durably claimed before the external action — P0

`InboxScreen` calls `approveAndDispatch` directly. The view model performs dispatch inside an action-store transaction. `ActionController.dispatch` constructs a `Dispatching` state in memory, calls the provider, and only then returns a state for the database to commit. A process death after provider acceptance but before commit can leave the durable command `Ready`. The production startup path does not visibly invoke interrupted-dispatch recovery. The synchronous HTTP path also runs from the UI call chain, risking main-thread network failure or unresponsiveness.

**Fix:** separate durable approval, atomic claim, off-main-thread dispatch, and durable result recording. Persist consumption/`Dispatching` before network I/O. At startup, turn interrupted attempts into `OutcomeUnknown`; reconcile when provider evidence supports it, otherwise require a deliberate user decision. A client trace ID is not automatically a provider idempotency guarantee.

**Acceptance:** process death before claim, after claim, after provider acceptance and before final persistence; concurrent taps; connection loss; timeout; restart. No automatic repeat of an uncertain external action. UI stays responsive.

Evidence: [view model](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:502), [action core](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:124), [SQLCipher transaction](C:/Users/nande/Downloads/Mini/native/shared/src/androidMain/kotlin/app/mini/hub/persistence/SqlCipherActionStore.android.kt:69).

### 2. Exact review is not fully bound to current evidence — P0

The Android payload sets `sourceVersions = emptyList()` and uses fixed grant/policy version strings. Rehashing that same payload does not detect that a source message changed, disappeared or became unauthorized after review. Some provider-specific rechecks exist, notably Outlook recipient/thread checks, but they do not establish the shared invariant.

The canonical payload also concatenates unescaped delimiters. For example, subject `x|body:y` with body `z` and subject `x` with body `y|body:z` produce the same subject/body serialization. SHA-256 cannot distinguish equal input strings. This is a serialization ambiguity, not a break of SHA-256.

**Fix:** a versioned unambiguous encoding; explicit operation type and native reply target; actual source revisions and authorization snapshots; current-state validation immediately before dispatch. Invalidate old approvals when migrating the digest format.

**Acceptance:** changed recipients/body/attachments/route/reply target, edited/deleted evidence, revoked access, expired approval and adversarial delimiters all invalidate the appropriate approval.

Evidence: [payload construction](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:452), [canonical encoding](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/action/ActionCore.kt:43).

### 3. Style and retrieval boundaries need explicit enforcement — P0

Recipient-specific style keys include an account, but `StyleContext.learn` also learns category profiles with a null recipient. Their key is only `cat:<category>`. The process-wide engine therefore combines accounts in its category fallback, including stored example text that can enter a draft prompt. Disabling all learning clears profiles; revoking only one category does not remove its profiles or prevent `resolveProfile` from returning them.

Separately, `ScopedRetrievalEngine` queries by conversation but labels returned evidence with the caller-supplied compartment without checking that the compartment belongs to the conversation's account. Current callers may be consistent; the claimed boundary is not enforced inside the engine.

**Fix:** include account identity in every profile and sample key; enforce permissions during learning, resolution and restore; clear/rebuild affected profiles when consent or sources change. Validate conversation/account/compartment agreement inside retrieval. Migrate old global profiles conservatively: discard ambiguous derived profiles, preserve authorized source history, and rebuild within accounts.

**Acceptance:** same recipient names/categories across two accounts, sparse-recipient fallback, partial consent revocation, corrupted/imported profiles and mismatched retrieval compartments. No cross-account example or evidence enters a prompt.

Evidence: [style ingestion](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/style/StyleContext.kt:82), [global category key and resolution](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/style/AdaptiveStyleEngine.kt:109), [retrieval](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/retrieval/ScopedRetrievalEngine.kt:34).

### 4. Message normalization drops information needed everywhere else — P0

`ProviderMessage` contains direction, recipients and native reply-anchor information, but the observation conversion does not retain those fields. SQLCipher conversation reads sort by ingestion time, not provider event time, and include removed rows. Understanding selects by ingestion time; retrieval does not exclude removed rows. Reply preparation takes the last inbound row from a newest-first timeline, which can select an older message. Equal ingestion timestamps make historical order especially unreliable.

The consequences reach several features: wrong reply target, stale deadline, deleted evidence in a draft and unreliable identification of the user's writing. Outlook additionally classifies `isDraft` as outbound rather than determining actual sender/direction. Historical style bootstrap can use a message subject as the recipient because the original recipients were lost.

**Fix:** establish one persisted message contract: provider/account/message/conversation IDs, native reply anchor, author identity, recipients, direction, provider event time, local observation time, revision, deletion, origin and body completeness. Use stable event ordering and source-revision invalidation throughout the app. Keep a notification dismissal distinct from an authoritative provider deletion.

**Acceptance:** out-of-order backfill, identical timestamps, repeated edits, deleted messages, sent mail, same text from different authors, refreshed metadata and native threaded replies all preserve correct behavior. Old data must migrate without a destructive reinstall; unknown fields remain explicitly unknown.

Evidence: [conversion](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/ConnectorObservationFactory.kt:16), [timeline query](C:/Users/nande/Downloads/Mini/native/shared/src/androidMain/kotlin/app/mini/hub/persistence/SqlCipherObservationStore.android.kt:451), [reply selection](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:434), [Outlook parsing](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/OutlookGraphConnector.kt).

### 5. Storage failure can silently change the product's guarantees — P0

At the inspected checkpoint, `MiniHubRuntime` catches observation/action SQLCipher failures and substitutes in-memory stores. The user can continue with state that will disappear on restart. Attention storage already has a more explicit unavailable-state pattern. Account metadata is Base64-encoded in private preferences; tokens are separately encrypted. Base64 is not encryption, so “all private data encrypted by the app” is too broad today.

**Fix:** explicit unavailable runtime state, blocked sending/learning, inert background workers and a recovery path that preserves the existing encrypted files. Encrypt sensitive account metadata consistently and verify backup behavior. Never repair a database-open failure by silently wiping user data.

**Status:** an untracked `RuntimeStorageTest.kt` was being added during this audit; its implementation was absent at the verification checkpoint. Treat this as work underway, not a completed fix.

Evidence: [runtime fallback](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MiniHubRuntime.kt:108), [account storage](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorStores.kt:25).

### 6. Several connectors can report progress while leaving history gaps — P1

Gmail's original incremental truncation/cursor problem is being corrected in the working tree. The new multi-page tests passed in this audit; that specific shared-code defect should no longer be reported as untouched. Host follow-up scheduling and live catch-up still require qualification. Initial bounded history is not a complete history importer.

Slack, Teams and Google Chat use bounded conversation/message lists without a complete continuation strategy. Advancing to the newest fetched item after a truncated page can skip intermediate messages permanently. Some adapters use revision `2` for any edited message, so a second edit can be ignored. Outlook's conversation fetch is bounded without following its continuation, and its incremental route is inbox-focused. X notes a gap but does not implement complete recovery of the missing pages.

**Fix:** separate incremental checkpoints, per-conversation pagination and historical backfill state. Advance only over accounted-for data; atomically persist messages and continuation. Add fair scheduling, rate-limit backoff, repeated-edit handling and truthful `partial`, `stale` and `gapped` coverage.

**Acceptance:** histories and bursts larger than every page limit, restart between pages, expired cursors, revoked permissions, second/third edits, deletion and inaccessible conversations. No bounded fetch may silently become a completeness claim.

Evidence: [chat adapters](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/ChatConnectors.kt), [Gmail](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/GmailApiConnector.kt), [sync engine](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/ConnectorSyncEngine.kt).

### 7. Connector presence overstates usable send coverage — P1

Teams, Google Chat and X have null reply targets in `ReplyTargets`, while the gateway infers `SendNew` whenever the target is null. Their catalog capability is `Reply`, so a conversation reply can fail qualification checks even when that route is intended to be allowed. Slack's native thread anchor is also lost during normalization. The UI routes bridge accounts to handoff because it recognizes only official API accounts for direct dispatch.

**Fix:** model the operation explicitly; distinguish replying to a conversation from requiring a message-specific anchor. Preserve provider-native thread targets. Route qualified local bridges through the same exact-review machinery. Qualification must exercise the actual UI-to-gateway path, not only call the sender directly.

Evidence: [reply targets](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/ConnectorRouting.kt:10), [gateway capability selection](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/SendContracts.kt:62), [Android route selection](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/ui/InboxViewModel.kt:425).

### 8. The bridge implementation does not meet the chosen privacy architecture — P1, critical path

The current Matrix adapter connects to a homeserver using a token. `whoami` verifies a Matrix identity, not a linked WhatsApp/Instagram/LinkedIn session. There is no embedded provider bridge runtime in this path and no personal Messenger route in the registry. The adapter does not decrypt encrypted room events. The newer network filter and stable transaction ID are improvements, but do not establish persistent room/account binding or provider forwarding success. A Matrix event ID proves homeserver acceptance, not delivery through the target network.

**Fix:** a phone-hosted bridge runtime with guided provider linking, persistent account/session binding, encrypted session storage, history continuation, reconnect, forwarding status and approved dispatch. Inspect candidate libraries for Android packaging, maintenance and license compatibility before adopting one. Never substitute a remote bridge to meet the deadline without a new user decision.

Beeper currently documents on-device WhatsApp, Instagram, Facebook Messenger and LinkedIn connections. That demonstrates feasibility in a shipping product, not an available drop-in MINI SDK or a completed integration. [Beeper connection types](https://help.beeper.com/chat-networks/using-on-device-chat-network-connections-in-beeper).

End-to-bridge encryption can hide content from a homeserver while the bridge itself still sees plaintext. It does not turn a remote bridge into phone-local processing. [mautrix encryption boundary](https://docs.mau.fi/bridges/general/end-to-bridge-encryption.html).

Evidence: [Matrix adapter](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/connector/MatrixBridgeConnector.kt), [registry](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt:176).

### 9. Automatic understanding and personalized drafting are not yet proven user value — P1

Understanding is currently keyword/regex based. “Not urgent” still contains `urgent`; a mentioned weekday is not necessarily a resolved deadline; unanswered status derives from a request plus manual attention state, rather than a verified absence of a reply. This is a useful initial signal layer, but not yet the promised contextual understanding.

Historical device evaluations document useful runtime recovery, but the latest recorded English suite still has stale facts, incoming-message echoes, omitted price and missed intent. Encrypted style persistence passed; the latest live Gmail style-learning and real MediaPipe personalization comparison were skipped for missing prerequisites. Deterministic style-composer fixtures cannot prove improvement in actual model output.

**Fix:** retain cheap rules as provisional signals, add bounded local interpretation where it improves accuracy, and persist typed facts with evidence IDs/revisions, confidence and timezone-aware dates. Preserve user corrections and recompute only affected conversations. Improve evidence selection before comparing models. Learn only from proven user-authored text; preserve the origin of unedited AI drafts even after they reappear in sent history. Canonicalize sample IDs across live ingestion and historical bootstrap.

**Acceptance:** held-out English evaluation of intent, latest facts, prices, dates, commitments, incomplete history and malicious message instructions; a real baseline-versus-personalized comparison with human ratings. A skipped required test blocks qualification. Avoid selecting a new model merely because its benchmark or parameter count is attractive.

Evidence: [understanding rules](C:/Users/nande/Downloads/Mini/native/shared/src/commonMain/kotlin/app/mini/hub/understanding/MessageUnderstanding.kt:24), [device usefulness review](C:/Users/nande/Downloads/Mini/native/DRAFT_USEFULNESS_REVIEW.md:324), [latest device test report](C:/Users/nande/Downloads/Mini/docs/TEST_REPORT.md:5).

### 10. Qualification and documentation lag the assembled product — P1

The README edits describe a Gmail/Outlook-only release UI and hidden Matrix routes, but the inspected registry still returns all routes and `MainActivity` passes them to the screen. Treat that as an in-progress implementation/document mismatch. Older records also describe Matrix defects that have since been partly fixed. There is no hosted CI configuration; the new local verification script does not itself prove device behavior. Shared common code includes JVM-specific constructs, so conditional iOS targets should not be described as a working iOS app.

The model is manually copied onto the phone. The publisher skeleton has no completed signed model-delivery flow. Repeated full-store reads and transaction-wide state replacement need measurement with realistic history; small synthetic tests do not establish long-term performance.

**Fix:** one capability/evidence ledger generated or maintained from actual qualification, with build hash, account type, device, scenario, result and limitations. Add CI and a release checklist. Preserve the existing app/model/accounts during device tests. Tie documentation updates to merged implementation rather than intended behavior.

Evidence: [setup rules](C:/Users/nande/Downloads/Mini/AGENTS.md), [UI registry wiring](C:/Users/nande/Downloads/Mini/native/androidApp/src/main/kotlin/app/mini/hub/android/MainActivity.kt:83), [implementation status](C:/Users/nande/Downloads/Mini/docs/IMPLEMENTATION_STATUS.md).

## Provider constraints that affect the plan

| Service | Required qualification |
|---|---|
| Gmail | Full/partial sync, recovery after invalid history cursor, applicable OAuth scope verification and user-consent flow. Gmail explicitly requires a full resync when a history cursor is unavailable. [Google sync guide](https://developers.google.com/workspace/gmail/api/guides/sync), [production scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification). |
| Outlook | Personal and work/school mail accounts separately; inbound/sent history; reply recipients; token refresh and reconnect. A successful mocked Graph response is insufficient. |
| Teams | Work/school account qualification. The selected chat API does not support delegated personal Microsoft accounts; general-user positioning must state that limitation. [Microsoft chat API](https://learn.microsoft.com/en-us/graph/api/chat-list?view=graph-rest-1.0). |
| Slack | Real workspace/user scope and app-distribution qualification; all necessary conversation/history pages; applicable rate limits, rather than one assumed quota. [Slack history API](https://docs.slack.dev/reference/methods/conversations.history/). |
| Google Chat | Supported account/app configuration, user permissions, pagination and actual reply operation. [Google Chat message listing](https://developers.google.com/workspace/chat/api/reference/rest/v1/spaces.messages/list). |
| X DMs | App entitlement and a measured operating budget; pagination and visible provider history limit. Current lookup documentation exposes events from up to 30 days ago, not an unlimited historical inbox. [X DM lookup](https://docs.x.com/x-api/direct-messages/lookup/introduction). |
| Four local bridges | Each service must independently pass phone-local linking, history, send, disconnect/reconnect and background tests. Success on one bridge does not qualify the other three. |

Periodic Android work is not an instant-delivery guarantee: scheduling is subject to system constraints, and periodic WorkManager has a 15-minute minimum interval. The product needs an explicit freshness design and visible last-sync state. [Android scheduling documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## What we are doing wrong strategically

1. **Counting components instead of completed user outcomes.** “Adapter implemented,” “test passed” and “AI generated text” are useful engineering facts. They do not establish that the right reply went to the right thread after a missed-sync/restart scenario. Make that full workflow the unit of progress.
2. **Building intelligence on an incomplete message record.** Direction, chronology, deletion, reply target and provenance must survive ingestion. Otherwise drafting, style and triage each invent their own reconstruction and disagree.
3. **Treating more model capability as the first remedy.** Some failures originate before inference: wrong order, incomplete retrieval, ambiguous authorship. Fix those first; compare the current runtime against one qualified challenger using identical evidence and device budgets.
4. **Making broad privacy claims before checking every path.** Local inference is valuable, but a remote bridge, global style examples and unencrypted metadata each need separate treatment. Describe the exact boundary people can rely on.
5. **Letting proposals become status claims.** Freeze the approved product decisions, keep speculative research separate, and require evidence before changing a capability to ready.
6. **Using research as demand proof.** The Beeper/PocketPal review ledger contains useful observations about convenience, reconnection, backfill, model setup and resource use. Its annotated records are not a verified unique-customer sample or a measure of market prevalence. General AI usage research does not prove demand for MINI. The pilot must test whether people return and complete communication tasks more easily.

The differentiator to validate is: **one trustworthy place to see what needs attention and produce a useful reply in the user's voice, with local processing and explicit control.** More integrations support that promise; they are not its measurement.

## Execution plan

These are implementation milestones, not a claim that work has been completed. Owner labels describe responsibilities; they do not assume additional staff. Keep the ten-service pilot scope. Sequence implementation internally, then launch only when all ten qualify. Do not set a launch date before the local-bridge feasibility checkpoint.

| Order | Deliverable and accountable role | Exit condition |
|---|---|---|
| 0 — Establish a stable baseline | Release owner reconciles ongoing Stage 1 edits, records a commit/build hash, restores passing automated checks, and creates the capability/evidence ledger. | Reproducible build; required tests pass; no skipped requirement represented as passed; docs match visible routes. |
| 1 — Repair trust boundaries | Core/native owner fixes findings 1–5 and the operation-routing issue in 7. Introduce the message schema/migration first where dependent fixes need it. | Failure-injection tests prove durable send claiming, exact approval, account isolation, storage failure handling and correct chronological evidence. Existing user data survives upgrade. |
| 2 — Resolve bridge feasibility early | Connector/runtime owner packages one genuine phone-local bridge session end to end, then qualifies the other three. This investigation can proceed while milestone 1 is implemented. | Actual provider linking, encrypted local session, restart/reconnect, backfill and approved sending demonstrated on supported phones; dependency/license/maintenance assessment complete. All four required before pilot. |
| 3 — Complete six API workflows | Connector owner implements incremental/backfill separation, edits/deletions, rate handling, refresh/reconnect and qualified UI send paths for Gmail, Outlook, Teams, Slack, Google Chat and X. | Per-service evidence for connect → history → new message → draft/review → authorized test send → reply observed → revoke/reconnect; accurate limitations shown. |
| 4 — Make understanding and drafting useful | Intelligence owner builds evidence-backed attention records, fixes learning provenance and retrieval, and evaluates the real runtime with/without personalization. | Held-out English quality and privacy gates pass; profile controls work; measured resource/latency budgets pass on a declared device matrix. |
| 5 — Make setup and daily use ordinary | Product/Android owner implements guided account linking, model installation/update verification, inbox/dashboard explanations, recovery and privacy controls. | New users connect supported accounts without raw tokens, terminal commands or developer assistance; failures explain what happened and what to do next. |
| 6 — Qualify and launch the Android pilot | Release/product owners run the ten-service device matrix and a multi-day background/upgrade test, then recruit general users. | Every mandatory launch gate below passes. iOS development begins at this launch point. |
| 7 — Learn and improve | Product owner runs a proposed four-week pilot; engineering fixes the highest-frequency observed task failures. iOS uses the corrected shared contracts and pilot findings. | Evidence-based continue/change decision from repeated use, task completion and reliability, not feature count. |

### Next implementation queue

The existing [Stage 1 prompt](C:/Users/nande/Downloads/Mini/docs/JCODE_STAGE1_PROMPT.md) overlaps this audit. Finish and verify its work rather than launching a competing edit stream. In particular, preserve the new Gmail regression coverage and complete its host scheduling behavior.

Then use small, reviewable changes in this order:

1. Finish explicit storage-unavailable handling and restore the automated baseline.
2. Split action claim/dispatch/finalization; add startup recovery and a background execution boundary.
3. Add the persisted message contract and nondestructive migration; update timeline, retrieval and reply selection together.
4. Version canonical action encoding and bind approval to real source/grant revisions.
5. Scope category profiles, enforce partial revocation, validate retrieval compartments and normalize learning provenance/sample IDs.
6. Fix operation capabilities and provider-native reply anchors; test via the UI/view model path.
7. Generalize pagination/backfill accounting across the six APIs.
8. Complete the phone-local bridge milestone and real-model usefulness qualification before consumer polish is called pilot-ready.

Maintain TypeScript rule parity where it represents shared safety rules, as required by repository guidance. Avoid expanding the synthetic web demo into a second product. If a stage-specific task prohibits edits to the reference implementation, record the parity work as a follow-up instead of silently omitting it.

### Concrete product workflow

Setup should explain local processing, connect the selected accounts, show supported account types and history coverage, install a verified model with progress/resume, and offer clear learning consent. No per-contact selection is required for opted-in style learning.

The inbox answers “What arrived?” The dashboard answers “What needs me, why, and what changed?” Every attention item links to the supporting message. Uncertain or incomplete context is visible, and user corrections persist. A requested draft shows which conversation it uses, supports editing, and leads to exact recipient/thread/body review. The outcome distinguishes accepted, failed, uncertain and opened in another app.

Recommended initial attachment behavior is honest metadata/placeholders plus opening the original service for unsupported content. This is a proposal, not a replacement retention policy. Before pilot launch, explicitly settle attachment download/processing, retention, low-storage behavior, disconnect-versus-delete, backup and local export. Do not silently purge history or imply unsupported attachment understanding.

### Proposed pilot gates and measurements

Targets below are recommendations to freeze before qualification, not achieved results or previously approved commitments.

| Gate | Required evidence |
|---|---|
| Scope | All ten named services available through their agreed route; account-type restrictions and history limits visible. No remote bridge plaintext processing. |
| Action safety | Zero wrong-account, wrong-recipient, duplicate-retry or stale-approval successes across the failure-injection suite. Unknown outcomes never silently retry. |
| Data correctness | All accessible events in seeded multi-page/burst/edit/delete/restart cases accounted for; inaccessible history marked incomplete; no silent cursor skip. |
| Privacy | Zero cross-account evidence/style leakage in adversarial fixtures; revoked consent enforced immediately; storage and backup checks pass. |
| English usefulness | Start with at least 200 held-out realistic cases. Suggested targets: at least 90% correct intent/fact handling and 80% usable with no more than minor edits, with zero critical wrong-recipient or unsupported-commitment cases. Report failures and refusals separately; calibrate ratings with two reviewers. |
| Personalization | Blind comparison using the same conversation/model demonstrates a preference for personalized drafts without factuality regression. Real engine output is required. |
| Reliability/resources | Declared low/mid/high Android test phones; at least a 48-hour all-service soak and a seven-day everyday-use check. Measure freshness, cold/warm latency, memory, temperature and battery against the phone's baseline. Freeze numerical budgets before the qualification run. |
| Onboarding/distribution | Suggested target: 80% of pilot users connect at least two eligible services and reach the attention view without engineering help. Signed/versioned model artifact, integrity checks, interrupted-download recovery and upgrade preservation verified. |
| Pilot value | Proposed 12–15 general users over four weeks. Record weekly repeat use, useful attention discoveries, missed/misclassified items, draft acceptance/edit effort and task completion time against their prior workflow. Do not infer market fit from this small sample. |

For resource qualification, the historical ~92 MiB measurement with no model loaded is not the drafting footprint. Recorded model-loaded runs reach several hundred MiB, with substantially greater pressure on a low-RAM phone. Measure the whole ten-connector workload with inference active; the isolated runtime benchmark is insufficient.

Telemetry should use consented, content-free counts/timings and optional participant feedback. Do not collect raw messages, drafts or style examples to measure pilot success.

## Verification record

Fresh local commands completed on 11 October 2026 against the moving working tree:

| Check | Observed result |
|---|---|
| `npm test` | **Pass: 59 tests.** |
| `npm run typecheck` | **Pass** across domain, publisher and hub. |
| `npm run build` | **Pass**, including the Vite production build. |
| Shared Kotlin JVM tests | **Pass: 142 tests, zero failures/errors/skips**, including three new Gmail cursor/pagination regressions. Count obtained from JUnit XML after the run. |
| Android unit-test task | **Blocked at compilation:** the newly added, untracked `RuntimeStorageTest.kt` referenced an absent `RuntimeStorage` implementation. Android unit tests did not execute in this run. |
| `:androidApp:assembleDebug`, run separately | **Pass.** This does not erase the unit-test compilation failure or establish release readiness. |
| Device inventory | A physical Android phone was visible. No new device behavior was qualified by this audit. |

The combined native invocation used `:shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --no-daemon --offline --max-workers=1`; it stopped at Android unit-test compilation. Debug assembly was then run separately and succeeded. Gradle also emitted a Kotlin/Android Gradle Plugin compatibility warning; resolve the toolchain pairing during baseline maintenance rather than suppressing it as evidence of compatibility.

Historical repository evidence records 139 shared and 37 Android unit tests passing before the current edits, Keystore style persistence/restart passing, and live-personalization prerequisites missing. Those are separate observations, not fresh passes of today's entire workspace. The English quality failures remain documented in the device usefulness review.

Concurrent edits observed included README/setup/security docs, Gmail sync and tests, new repository guidance, the Stage 1 prompt, the local verification script and the unfinished storage test. The audit did not modify those files. Findings describe the inspected checkpoint; implementation work should close each with a linked change and fresh evidence rather than treating this document as a permanent defect list.

## Decision

Continue building MINI on the existing native foundation. Make trustworthy message state, durable approved actions and phone-local integration the immediate engineering priorities. Keep the agreed ten-service ambition, but expose the true critical path: local bridge qualification and complete cross-service reliability. Judge the next milestone by an ordinary person's successful daily communication workflow and measured draft usefulness.
