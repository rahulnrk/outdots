# Outdots: hosted AI direction and shared delivery plan

29 October 2026, Asia/Kolkata. Source review of HEAD `0513cc3` plus the existing dirty working tree. This is a product decision and implementation plan, not a report that the migration is implemented or live-qualified.

## Product decision

**Help the user never miss an important conversation.** Outdots brings communication into one place, identifies what needs attention, suggests responses, explains deadlines and tracks missing replies. The user finalizes actions and reviews the exact outgoing message before sending.

The user selected one hosted product LLM, replacing the on-device LLM direction. Their clarification: “legacy model” means the product AI that writes emails/responses; it does not require an obsolete model. Claude Code and Codex are development tools and are separate from that product-model choice.

Initial connectors: **Gmail, WhatsApp, Facebook Messenger, Instagram DMs and LinkedIn messaging**. Other existing adapters and stored accounts are retained, but are outside the initial delivery target. Android remains the implementation platform; cross-platform intelligence means reasoning across communication services, not a new commitment to ship iOS/web clients now.

This decision supersedes conflicting local-inference targets, Qwen/Gemma experiments and broader initial connector lists in previous plans. It does not change qualification evidence. Phone-local connector hosting remains the existing decision; moving inference to a provider does not automatically move connector credentials or full mailboxes to a cloud bridge.

## Where we stand

| Area | Evidence available now | Remaining work |
|---|---|---|
| Native foundation | KMP shared rules, Android Compose, SQLCipher/Keystore, scoped identities, observations/search, drafts, exact review and durable outbox | Preserve and extend; production migration and recovery still need feature-specific qualification |
| Gmail | OAuth, incremental sync and thread read; historical device/live-read evidence in implementation status | Fresh background/reconnect coverage and separately qualified approved-send workflow |
| WhatsApp | Phone-local Go/whatsmeow runtime and encrypted session snapshots; historical connected/sync evidence | Fresh receive/history/restart/background/send acceptance; do not infer all capabilities from Healthy |
| Instagram | 20 October report records production owner login, bounded ingest and foreground receive on Pixel 7 Pro | Background receive and sending remain open |
| Messenger | Probe runtime and sealed E2EE device state exist; current registry does not provide a qualified production route | Verify encrypted inbound and lifecycle, integrate production account/inbox path, qualify each capability |
| LinkedIn | Runtime and session scaffolding; 20 October report records blocked WebView authentication | Supported authentication feasibility, then integration and qualification; upstream possibilities are not completed login |
| Focus | Conversation attention UI; matter records, consent tickets, invalidation coordinator and stores exist | Matter-driven dashboard, hosted understanding, assistant sessions and reminder lifecycle |
| AI | Local MediaPipe drafting path and inference interface | Hosted transport, processing consent, request admission/cost limits, structured outputs and evaluation |
| Cross-platform reasoning | Existing retrieval and matter validation enforce one account/compartment | Explicit permissioned multi-account evidence composition and identity linking |

This review did not rerun suites or exercise devices/providers. The 19 October report records 280 shared JVM, 119 Android host and 59 TypeScript tests passing; those are historical results, not a fresh baseline. There is substantial tracked and untracked work. Do not reset, clean, overwrite or bulk-stage it.

Primary source pointers:

- `native/shared/src/commonMain/kotlin/app/mini/hub/intelligence/Inference.kt`: one-compartment tasks, device-era token limits, generic text proposals; not a complete hosted intelligence contract.
- `native/shared/src/commonMain/kotlin/app/mini/hub/matter/{MatterCore,MatterStore,MatterValidation,ProductionFocusCoordinator}.kt`: existing matter foundations and account boundaries.
- `native/androidApp/src/main/kotlin/app/mini/hub/android/ui/MiniDailyApp.kt`: Focus currently reads `state.attentionView`.
- `native/androidApp/src/main/kotlin/app/mini/hub/android/{MiniHubRuntime.kt,connector/AndroidConnectorRegistry.kt}`: production wiring.
- [Instagram evidence](INSTAGRAM_PHONE_LOCAL_PRODUCTION_2026-10-20.md), [Messenger probe](MESSENGER_PHONE_LOCAL_FEASIBILITY_2026-10-20.md), [LinkedIn blocker](LINKEDIN_PHONE_LOCAL_FEASIBILITY_2026-10-20.md), [implementation status](IMPLEMENTATION_STATUS.md).

## Proposed architecture: preserve the foundation

```text
Five connectors -> encrypted canonical messages + coverage/freshness
  -> account consent + exclusions + permitted cross-account scope
  -> incremental evidence retrieval -> hosted inference gateway -> one product LLM
  -> schema/evidence/revision validation -> suggestions and matter candidates
  -> Focus / thread / assistant -> user confirmation and reminder preferences
  -> canonical draft -> exact review -> existing gated outbox -> provider/handoff
```

The hosted gateway is a proposed new service, not the existing publisher skeleton repurposed as a mailbox. It authenticates the app/user, holds the product API credential server-side, enforces per-user budgets and forwards bounded evidence. Do not ship a shared provider API secret in the APK. Define hosting, provider access/retention, deletion and operational costs before enabling private-data traffic. No new backend is deployed by this plan.

One provider/model is active for all generative tasks in a product release. Keep an adapter boundary so a future deliberate migration is possible; do not build three production integrations or silently switch providers during outages. Local encrypted storage, FTS retrieval and deterministic rules remain useful. Local generation and a separate embedding-model project are outside this direction. Offline users can still read stored conversations, edit drafts and use already-confirmed reminders; new hosted suggestions show an honest unavailable state.

### Contracts Codex owns before parallel integration

1. **Processing permission:** distinguish connecting a service, Focus processing, hosted processing, style learning and cross-account composition. Existing local/style consent does not silently authorize remote processing. Record account/conversation exclusions and permission revisions; check before network dispatch and before accepting results.
2. **Scoped evidence envelope:** request ID, task kind, permitted accounts, evidence IDs and source revisions, coverage/freshness, permission revision, prompt/schema/model versions, token/output ceilings and cancellation/generation identifier. Include only required content; no OAuth tokens, session cookies or device keys. Never trust caller-supplied scope without authorization.
3. **Structured proposals:** separate reply drafts, summaries, deadline candidates, waiting/follow-up candidates and cross-service links. Every factual candidate cites admitted evidence. Unsupported dates, unknown source IDs and stale/deleted evidence are rejected or clearly unresolved. Message text is untrusted input and cannot issue instructions to send, widen scope or retrieve secrets.
4. **Cross-account composition:** preserve account-scoped storage and retrieval; create an explicitly authorized aggregate over scoped evidence. Do not remove `MatterValidation`/retrieval isolation checks or put unrelated accounts into a fake shared compartment. Uncertain people links require confirmation; never join people solely because names match. Reply drafting must not leak private cross-service context into the outgoing text without user review.
5. **Matter lifecycle:** distinguish “I owe a reply,” “waiting for someone else,” dated commitment and suggested follow-up. Keep source deadline, date precision/timezone and suggested reminder separate. “Tomorrow” is anchored to source time; absent exact time stays absent. Candidate changes do not silently override user dismissal, completion, snooze or corrections.
6. **Confirmed actions and reminders:** AI suggests. User confirmation establishes a reminder or follow-up schedule; already-confirmed preferences can govern its delivery. New conflicting evidence prompts review. Sending is never automatic. A reply arriving elsewhere is evidence to reconcile, not permission to auto-complete an obligation or claim delivery.
7. **Recovery and cost:** incremental jobs keyed by source revision + scope + model/prompt/schema version; bounded retries/backoff, cancellation, no repeated full-history uploads, usage accounting including billed reasoning tokens and server-side ceilings. Reminders use durable deterministic scheduling, not repeated LLM polling. Preserve the existing no-auto-resend rule for uncertain sends.

Phone-local ingestion still inherits Android background/battery limits. A hosted model cannot analyze messages the phone has not received. Surface stale connections and partial history; measure ingestion-to-alert latency and missed events before making reliability claims. Moving ingestion server-side would be a separate architecture decision.

## Two delivery halves

Split by ownership and implementation volume, not an unsupported estimate of equal hours. Claude Code handles bounded implementation after contracts; Codex handles complex semantics, boundaries and integration review. No extra Codex subagents are required.

| Package | Codex / Astra: complex core | Claude Code: bounded implementation | Dependency |
|---|---|---|---|
| 1. Connection reliability | Diagnose Messenger E2EE and LinkedIn auth blockers; approve protocol/runtime changes | Truthful capability/readiness UI for five providers; existing receive/reconnect coverage and fixtures | Claude C1 can start now |
| 2. Hosted intelligence | Consent/evidence contracts, gateway security, model adapter, quotas, output validator and evaluation selection | Settings and request-state UI, cancellation/retry affordances, fixture-backed presentations | Frozen contract A1 before C2 wiring |
| 3. Focus intelligence | Matter/deadline/waiting semantics, source reconciliation, authorized cross-platform links | Matter cards, evidence details, correction/confirmation controls and shared composer entry | A2 read/action contract before C3 wiring |
| 4. Reminders and finish | Durable scheduling semantics, restart/timezone/cancellation correctness; final approval/outbox integration review | Android host/UI plumbing from contract, accessibility/theme/error states, regression fixtures and evidence report | A3 scheduler contract before C4 |

Codex initially owns `shared/.../{intelligence,matter,retrieval,understanding,action,persistence}`, new hosted service code, protocol runtime changes, and corresponding TypeScript rule parity. Claude initially owns a new isolated account-readiness presenter/component and its tests. Later UI/host integration ownership must name exact files before work starts: `MiniDailyApp.kt`, `InboxViewModel.kt`, `MiniHubRuntime.kt` and `AndroidConnectorRegistry.kt` are integration hot spots, not files both agents edit concurrently.

One bounded package per Claude session. Return changed files, public contracts consumed/needed, executed test results and blockers in a short handoff. Codex reviews that diff and runs relevant integration checks once; it does not repeat Claude's exploration. Preserve dirty work and identify the starting baseline. No global staging, resets, connector session wipes or qualification shortcuts.

## Delivery order and acceptance

1. **Baseline + A1/C1:** record current failures without changing app data; define hosted permission/request/result boundaries while Claude builds honest account readiness. C1 does not require API credentials or a model choice.
2. **Gmail vertical slice:** receive -> admitted context -> hosted draft -> editable canonical draft -> exact review. Approval/dispatch still requires qualified provider capability. Prove denial, stale-source rejection, edited-draft protection, cancellation, timeout, offline and spend ceiling with fixtures before live model use.
3. **Deadline and missing-reply slice:** source-backed candidates -> user confirmation -> durable reminder; replies/edits/revocation reconcile pending suggestions. Cover restart, duplicate events, timezone change, notification permission denied and no-response ambiguity under incomplete sync.
4. **Cross-platform slice:** the same work item spans Gmail and WhatsApp/Instagram with separately permitted evidence. Test same-name decoys, excluded accounts, revoked consent, contradictory dates and a proposed reply that would disclose another conversation.
5. **All five connector acceptance:** Messenger and LinkedIn remain in the target; track blockers independently. Each requires owner login, receive/history coverage, reconnect/revoke/background behavior, and separately approved-send evidence. Do not hold early end-to-end development until every connector is complete, or advertise five working integrations prematurely.

Product evaluation: important-conversation recall, false urgent alerts, deadline precision, missed-reply false positives, factual support of drafts, stale suggestions, ingestion-to-alert latency, battery/network use and cost per active user. Include ordinary messages requiring no action. “Never miss” is the goal, not a proven guarantee.

## Product model selection

No model is selected or benchmarked yet. Compare one affordable candidate per provider with identical synthetic/anonymized fixtures and task budgets, then implement one production adapter. Do not choose solely by model age or price per token. Measure actual billed tokens, draft usefulness, unsupported commitments, deadline mistakes and latency. Verify availability and support lifetime before committing.

Indicative standard text API prices checked 29 October 2026, USD per million tokens (not subscriptions, batch or cached rates):

| Evaluation candidate | Input | Output | Source |
|---|---:|---:|---|
| GPT-5.4 mini | $0.75 | $4.50 | [Official model page](https://developers.openai.com/api/docs/models/gpt-5.4-mini) |
| Claude Haiku 4.5 | $1.00 | $5.00 | [Official pricing](https://platform.claude.com/docs/en/about-claude/pricing) |
| Gemini 2.5 Flash | $0.30 | $2.50 | [Official pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-2.5-flash) |

These are established comparison baselines, not a claim they are today's best value. For example, the same Claude pricing page lists Haiku 5.5 at $0.10 input/$0.50 output for prompts up to 100k tokens; the clarified requirement permits evaluating that newer candidate instead. Shortlist lifecycle and quality must be checked at evaluation time. Google's page distinguishes free-tier product-improvement use from paid-tier terms; provider retention/access terms need separate review before private content is processed.

Illustrative calculation only: 100 requests/day at 2,000 input + 300 billed output tokens each, for 30 days, yields 6M input + 0.9M output: $8.55 for GPT-5.4 mini, $10.50 for Haiku 4.5, $4.05 for Gemini 2.5 Flash. Real tokenization/reasoning, workload, retries, hosting, taxes and media change these figures. This is not a measured per-user cost or a pricing promise.

## Current handoff state

Claude Code 2.1.302 is installed, but `claude auth status` returned `loggedIn: false` in this environment. No Claude task has been dispatched. [The ready-to-use Claude handoff](CLAUDE_CODE_OUTDOTS_HANDOFF_2026-10-29.md) starts with C1 and reserves later work behind Codex contracts. Authentication needs the user's Claude account; do not copy credentials from unrelated apps or place tokens in chat/files.

Open implementation decisions: final hosted model after evaluation, target monthly AI cost per active user, backend hosting/retention configuration, and provider-specific live qualification. These do not block C1 or provider-independent core design.
