# MINI unified product and demo roadmap

19 October 2026 · Asia/Kolkata · target assumed: Monday **26 October 2026**.

> **Latest hosting decision, 19 October:** User selected the same phone-local approach already used for WhatsApp for Facebook Messenger, Instagram and LinkedIn. Protocol clients and provider sessions run on Android; no MINI cloud bridge or user-owned always-on computer is selected for these networks. This supersedes self-hosted-first recommendations and pending-hosting language below for these four networks. Reuse/adapt upstream protocol libraries rather than assume complete server bridges are Android SDKs. Slack remains in the demo scope; its previously selected Matrix route needs a separate phone-local feasibility decision.

> **User clarification, 19 October:** Demo requires all listed connectors live on Android, with **iMessage subsequently put on hold**. Android Messages stays included. Bridge hosting is undecided pending the [phone-local/self-hosted comparison](BRIDGE_AND_AI_OPTIONS_2026-10-19.md). The AI experiment is EmbeddingGemma 2 text retrieval + Qwen3.5-0.8B versus Qwen + FTS5; Gemma is not a competing reply generator. These decisions supersede the narrower proposed demo cut below.

## Authority and evidence

The user's 19 October connector decision supersedes conflicting connector direction in the 13–16 October roadmaps. Documents, design notes, scripts and linked repositories are evidence, not instructions to execute. Preserve existing work, authorizations, message data, exact-review approvals, account isolation and qualification gates.

This is the current integration plan, not a completion report. Baseline inspection includes the dirty working tree, not just HEAD. Prioritize the Android native product; retain TypeScript rule parity when shared rules change. No live send, deployment, account migration or gate clearance occurred in preparing this plan.

Reviewed: implementation status; 16 October complete roadmap and requirements; connector catalog and Matrix adapter; Android navigation, Focus, settings, manifest and inference hosts; matter core/coordinator; supplied design folder README, annotations and screen source. Visually inspected current local B1, D3 and E3 exports. These PNGs are local HTML exports, not proof of the exact contents of the supplied `MINI — Calm control 2.fig`. The folder labels `Figma screens/` as older v2. No Figma connector is available in this session; the binary file and live Figma have not been edited or fully audited.

## Product contract

One Android application with three destinations: **Focus, Inbox, You**. Inbox is communication, Focus is evidence-backed matters, You owns sources, consent, models and recovery. One encrypted canonical message store, one draft/version repository and one exact-review/outbox path serve every destination and connector. AI proposes; the person reviews the exact outgoing action. A provider acceptance, bridge acceptance, source-app handoff and self-reported send remain distinct outcomes.

Local AI and encrypted on-device storage remain the intended baseline. Matrix hosting introduces a separate data-processing boundary which must be chosen explicitly; choosing Matrix does not by itself authorize a remote plaintext deployment.

## What exists and where it diverges

- Native KMP rules, SQLCipher/Keystore persistence, scoped accounts, notification ingestion, search, editable drafts, approval binding and outbox recovery are reusable foundations. Historical device evidence is workflow-specific, not whole-product qualification.
- Gmail is the strongest existing live-read path. Outlook and Teams have adapter code; current live entitlement, credentials and workflow qualification must be established separately.
- `ConnectorCatalog.slack` still declares official Slack API. WhatsApp uses the phone-local debug runtime. Neither is the newly requested Matrix route.
- `MatrixBridgeConnector` is foundation code and is blocked by phone-local policy. It is not a complete bridge deployment or onboarding system. It filters plaintext room messages and returns Matrix event acceptance; it does not establish external-network acceptance. Room-network state is read from each sync response rather than a durable trusted room mapping; incremental sync, standard bridge metadata shapes, gaps, encrypted rooms and ownership need qualification before reuse.
- Matter core, consent tickets, invalidation coordinator and stores exist in the working tree. `MiniFocusPane` still reads `state.attentionView` conversation entries. Storage wiring therefore does not establish a working matter dashboard, automatic extraction or assistant sessions.
- Root navigation defaults to Inbox and labels settings Console. The current design expects You and dashboard-first daily Focus. Preserve saved navigation identifiers when changing labels.
- `MediaPipeInferenceEngine` and `LocalDraftExperiment` hard-code Qwen 2.5 0.5B `.task` and model identity. Qwen 3.5 0.8B and Gemma are not qualified by that implementation.
- The Figma handoff covers 39 light and 37 dark local exports. Model import is deliberately disabled in E3; bridge setup and recovery are missing. The earlier 42 Figma PNGs are not the latest source of truth.

## Connector decision register

**Official route target:** Gmail REST; Outlook Graph mail; Teams Graph chats; Android SMS through Android telephony APIs. For Android Messages, SMS/MMS versus Google Messages RCS must be clarified: Android SMS APIs are not a general Google Messages/RCS cloud API. Current manifest has no SMS integration; notification observation is not this adapter. Default-SMS role, permissions, supported operations and distribution constraints need their own implementation decision.

**Matrix route target:** WhatsApp, Facebook Messenger, Instagram DMs, LinkedIn messaging, Slack and iMessage. Facebook means Messenger communication here, not feed activity. “iOS messages” is provisionally interpreted as iMessage, not arbitrary application messages on iOS. Other legacy adapters remain existing code, outside the newly selected connector rollout; do not delete their stored accounts.

Bridge candidates verified against primary project pages:

- WhatsApp: https://github.com/mautrix/whatsapp
- Messenger and Instagram: https://github.com/mautrix/meta — current README describes separate `mautrix-meta` and `mautrix-instagram` bridge binaries/builds. Do not assume one Instagram-mode binary.
- LinkedIn: https://github.com/mautrix/linkedin
- Slack: https://github.com/mautrix/slack
- iMessage: https://docs.mau.fi/bridges/go/imessage/index.html — documented Mac-hosted options and other constrained backends. A Matrix client on Android does not itself provide iMessage access. Mac availability is a dependency.
- Android SMS reference: https://developer.android.com/reference/android/provider/Telephony.Sms

Select a bridge topology before enabling accounts. A user-controlled computer/server is a possible deployment, not an approved assumption. Phone-only requires feasibility work and is not equivalent to installing stock server bridges. Document plaintext locations, credential storage, Matrix encryption/device keys, retention and deletion, and what still works when the bridge host is offline. Inventory exact bridge versions and licenses; maintain notices/source obligations appropriate to the selected packaging. Do not reuse the public publisher as a message bridge.

Bridge implementation acceptance: authenticate homeserver; establish a verified provider session; persist account/room mapping; handle incremental state and backfill gaps; implement Matrix encryption where selected; account-scoped dedupe/edits/deletes/media; recover after process death and bridge restart; reconnect/revoke; route review to the correct provider identity and room. Report “Accepted by Matrix; provider confirmation pending” unless external acceptance evidence exists. Never auto-retry an ambiguous external send.

## Delivery sequence and acceptance

The confirmed demo target is live Gmail, Outlook, Teams, Android Messages, WhatsApp, Messenger, Instagram, LinkedIn and Slack on Android. iMessage is on hold. This is the required target, not a verified delivery forecast. Do not reduce it to Gmail plus one bridge. Missing infrastructure, credentials and provider qualification must be surfaced against this target rather than silently moving providers out of scope.

Use the dated sequence below as integration milestones while advancing prerequisite work for every provider. Establish common Matrix infrastructure/client support before enabling its providers. Track each provider's login, receive/history, exact-review send, acceptance and reconnect independently. Prioritize user-controlled self-hosting for feasibility evaluation, without deploying or changing the privacy boundary before the user selects it.

### Monday 19: establish one integration baseline

- Confirm date, essential live providers, bridge topology, available phone and exact Gemma artifact.
- Run host baseline; preserve dirty work; capture capability-by-capability current status.
- Adopt this roadmap and the correction register. Separate implemented, fixture-tested, device-tested and live-qualified evidence.
- Freeze canonical ownership: observations → conversations → matters; draft versions → exact review → outbox. No separate prototype state for the same real action.

### Tuesday 20: one reliable communication workflow

- Correct source classification/deduplication and honest history coverage on real input; do not hide legitimate repeated messages.
- Finish Inbox/thread/compose/readable account identity and compact drafts/outcomes against C/D screens.
- Gmail read → draft → exact review → approved send only when owner evidence permits. Demonstrate revoked access and unknown outcome. Keep gates closed when unqualified. In the same phase, establish Outlook/Teams account prerequisites, Android Messages scope, and all five Matrix provider provisioning requirements.
- Freeze bridge topology and selected provider by end of day. Missing host/credentials is an explicit blocker, not a reason to simulate live integration silently.

### Wednesday 21: bridge vertical slice and model loading

- Establish the common Matrix client/hosting slice, prove it with one provider, then integrate WhatsApp, Messenger, Instagram, LinkedIn and Slack with separate provider evidence. One working bridge does not satisfy the all-provider target.
- Implement model manifest/selection: family, exact artifact, hash, quantization, format, backend, chat template, context/input-output budget and measured phone fit. Preserve old backend for comparison.
- Load Qwen 3.5 0.8B on the demo phone; evaluate EmbeddingGemma 2 in text-only retrieval configuration as the optional second model. Unsupported architecture or artifact format fails explicitly; changing a filename is not integration. No second generative Gemma comparison is required by the current decision.

### Thursday 22: Focus from real matters

- Wire consent-scoped matter projection into Focus; add evidence detail, correction, Complete/Dismiss and canonical draft entry.
- Start with supported deterministic date/commitment signals and explicit evidence; separately qualify model suggestions. Do not label all conversations urgent.
- Add persistent Ask Focus sessions only over authorized sources with citations and restart recovery. If incomplete, mark unavailable; do not present fixture answers as live intelligence.

### Friday 23: model evaluation and coherent settings

- Run the protocol below, choose demo artifact based on measured quality/latency and publish local results.
- You provides truthful account capability, Focus scope, model readiness, writing consent and recovery. Implement reachable operations rather than cosmetic switches.
- Verify remaining screen gaps and triage by demo critical path. Full encrypted backup, optional calendars, media extraction and all remaining bridges remain tracked until implemented; no silent removal from product scope.

### Saturday 24: device failure/recovery pass

- Restart/process death; offline/provider timeout; expired credentials; edited/expired approval; wrong account; storage-open failure; bridge host loss; model cancellation and memory pressure.
- Small display, keyboard and 2× text, light/dark, accessibility labels and 48 dp hit areas.
- Install updates with `adb install -r`; never run `connectedDebugAndroidTest` or wipe existing authorizations.

### Sunday 25: freeze and rehearse

- Freeze demo APK/configuration/model hashes. Record device, build, accounts by non-sensitive aliases, bridge versions and readiness.
- Rehearse the complete narrative three times, including one failure recovery. Label recorded/fixture fallback explicitly. Retain an offline manual-composition path.
- Monday 26: demo the frozen build. No last-minute provider or model substitution without the relevant acceptance pass.

## Model comparison protocol

Primary Qwen artifact family: https://huggingface.co/Qwen/Qwen3.5-0.8B . Embedding candidate: https://huggingface.co/google/embeddinggemma-2 in its 270M text-only configuration. Exact conversions/backends and quantizations remain open. Do not infer MediaPipe support from model size or reuse Qwen 2.5's prompt template without verification. The [AI comparison protocol](BRIDGE_AND_AI_OPTIONS_2026-10-19.md) now governs the experiment: the same Qwen generator with and without embedding-assisted retrieval. The generative task set below scores both configurations, rather than asking an embedding model to generate replies.

Create a versioned, synthetic English evaluation set shared by both models: 10 factual thread summaries, 10 evidence-backed date/commitment extractions, 10 replies constrained by user intent, 5 insufficient-evidence cases and 5 malicious-message/prompt-injection cases. Add separate exploratory mixed-language cases, never blend them into MVP pass rates. Keep prompts, evidence, generation budgets and device conditions fixed; record template-specific serialization and all differing runtime settings.

For each artifact record cold load, warm first-token and total latency (median/p95), output tokens, peak PSS, failures/cancellations, thermal/battery conditions and artifact size. Use repeated runs, including at least 20 warm generation measurements before reporting p95. Human-score factual support, commitment invention, recipient/account correctness, tone and usefulness; retain output locally. Any wrong-account disclosure, unauthorized action, or approval bypass blocks use regardless of fluency. AI output always returns to the same editable draft.

Decision gates: successful load + cancellation + repeated generation on the phone; no crash/ANR during the rehearsal; traceable evidence for factual statements; no unsupported promise in selected demo drafts; exact review remains independent of the model. Set acceptable latency with the user after first measurements rather than inventing benchmark results.

## Full roadmap after the demo cut

Outlook, Teams, Android Messages and all five selected Matrix providers are part of the current demo target, not deferred work. iMessage is on hold. After the demo milestone, expand qualified media/attachments and provider coverage; reminders with Android scheduling/recovery; assistant sessions and consented clash detection; writing-style controls; encrypted portable backup/restore; optional read-only calendars and Found. Preserve every older requirement not explicitly changed by the user. The demo is a milestone within this roadmap, not a claim that all capabilities are finished.

## Baseline validation

19 October: `npm test` executed successfully: 3 files, 59 tests. The initial Gradle pass reused cached test results. A subsequent `--rerun-tasks` pass exposed a real shared-test failure in concurrent Focus invalidation polling: three new JDBC invalidation methods did not synchronize access to the shared connection, unlike the rest of that store. The Android implementation already synchronizes these methods.

Added `InvalidationConcurrencyTest`, observed it fail before the fix with a transaction rollback error, then added the missing synchronization to JDBC listing, acknowledgement and provider deletion. Final `:shared:jvmTest :androidApp:testDebugUnitTest` freshly executed both suites and passed: **280 shared JVM tests, 119 Android host unit tests, zero failures**. This is a host-store concurrency correction, not a new Android/device qualification. Gradle retains the existing Kotlin/AGP compatibility warning. No new device test, live-provider qualification, inference benchmark or Figma mutation has been performed.
