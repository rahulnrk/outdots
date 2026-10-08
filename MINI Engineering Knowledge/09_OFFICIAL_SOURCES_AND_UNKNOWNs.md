# MINI — Official Sources, Evidence Quality and Reverification Queue

**Compiled 9 October 2026.** The links below are primary references where available, with some official collection pages replacing docs that blocked automated retrieval. Information in this pack is not legal advice, partner approval or a claim of working live integration. Cite these URLs in MINI ADRs when adopting a design or API.

## A. JCode and developer harness

- [Official JCode repository/README](https://github.com/1jehuang/jcode) — shared Rust daemon, swarm, supported providers, benchmark procedures; author's RAM measurements are environment-specific.
- [Swarm task graph](https://github.com/1jehuang/jcode/blob/master/docs/SWARM_TASK_GRAPH.md) — task DAG, handoffs and verification progress; reconcile with installed version.
- [JCode memory architecture](https://github.com/1jehuang/jcode/blob/master/docs/MEMORY_ARCHITECTURE.md) — note potential disparity from README and possible external relevance processing; **do not copy network data flows** into MINI.
- [JCode server architecture](https://github.com/1jehuang/jcode/blob/master/docs/SERVER_ARCHITECTURE.md) — coordinator/shared daemon engineering ideas.

## B. Model cards and runtimes

- [Gemma 4 overview](https://ai.google.dev/gemma/docs/core) — E2B mobile/text-only weight estimates, model family, QAT, context/memory caveats, model terms links.
- [LiteRT-LM overview](https://developers.google.com/edge/litert-lm/overview) — supported interfaces, Kotlin stable/Swift early-preview designation, vendor device benchmarks, EmbeddingGemma artifact sizes.
- [Qwen3.5-4B official card](https://huggingface.co/Qwen/Qwen3.5-4B) — 4B architecture, multilingual claims and Apache-2.0 labeling.
- [Liquid LFM2.5 1.2B official card](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct) — text-only, 8 listed languages, 32K context, custom lfm1.0 license.
- [EmbeddingGemma 2 official card](https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2) — 270M text submodel, 128/256/512/768 dimensions, Apache-2.0.
- [llama.cpp repository](https://github.com/ggml-org/llama.cpp) — use concrete commit/release for hybrid-model compatibility.
- [MLX Swift](https://github.com/ml-explore/mlx-swift) and [MLX Swift Examples](https://github.com/ml-explore/mlx-swift-examples) — iOS/macOS integration examples; verify current Swift/iOS availability.
- [Apple Core ML](https://developer.apple.com/documentation/coreml) — Apple-native runtime integrations.

## C. Email, Microsoft and workspaces

- [Gmail API overview](https://developers.google.com/workspace/gmail/api/guides) — REST, messages, threads, drafts and push notifications.
- [Gmail OAuth scopes](https://developers.google.com/workspace/gmail/api/auth/scopes) — sensitive/restricted classifications, security review trigger.
- [Gmail push docs](https://developers.google.com/workspace/gmail/api/guides/push) — Cloud Pub/Sub, mailbox watch expiration/renewal; mobile-client sync caveat.
- [Gmail API quotas](https://developers.google.com/workspace/gmail/api/reference/quota) — per-method units; subject to change.
- [Graph message resource](https://learn.microsoft.com/en-us/graph/api/resources/message?view=graph-rest-1.0) — Outlook messages, delta, drafts/send and subscriptions.
- [Graph Teams chatMessage](https://learn.microsoft.com/en-us/graph/api/resources/chatmessage?view=graph-rest-1.0) — Teams chat/channel methods and change notifications.
- [Graph subscriptions](https://learn.microsoft.com/en-us/graph/api/subscription-post-subscriptions?view=graph-rest-1.0) — webhook subscription creation, permissions.
- [Slack Events API](https://docs.slack.dev/apis/events-api/), [Slack OAuth](https://docs.slack.dev/authentication/installing-with-oauth/) — workspace authorization, real-time event modes.
- [Google Chat API](https://developers.google.com/workspace/chat/api/guides/v1/messages/list) — space messages; verify auth and app type.

## D. Meta, social and messaging

- [Meta WhatsApp Business Platform official Postman workspace](https://www.postman.com/meta/whatsapp-business-platform/overview) — official test collections for Cloud API, business management, embedded signup.
- [Meta WhatsApp Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api/) — official business-account messaging, webhook, templates and onboarding.
- [Meta Instagram messaging with Instagram Login](https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-instagram-login/messaging-api) — professional account message send path.
- [Meta Instagram Business Login](https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-instagram-login/business-login) — official professional-account authorization flow.
- [Meta Messenger Platform getting started](https://developers.facebook.com/documentation/business-messaging/messenger-platform/get-started) — Page messaging.
- [Meta Messenger and Instagram messaging policy](https://developers.facebook.com/documentation/business-messaging/messenger-platform/policy) — window and policy restrictions.
- [LinkedIn developer OAuth](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication) — authorization is not personal DM entitlement.
- [LinkedIn Pages messaging partner integration](https://www.linkedin.com/help/linkedin/answer/a6246714) — official list of supported partner platforms; shows Page messaging route distinct from member inbox.
- [Telegram Bot API](https://core.telegram.org/bots/api) and [MTProto developer access](https://core.telegram.org/api/obtaining_api_id) — entirely different access/identity models.
- [Discord Gateway](https://docs.discord.com/developers/events/gateway) — bot/Gateway events and intents.
- [LINE Messaging API](https://developers.line.biz/en/docs/messaging-api/overview/) — official account messaging.
- [Reddit developer API](https://www.reddit.com/dev/api/) — developer access and commercial terms must be checked.
- [X API](https://docs.x.com/x-api) — mutable DM availability/tiers.
- [TikTok developer docs](https://developers.tiktok.com/doc/) — no implied generic personal DMs.
- [Threads API docs](https://developers.facebook.com/docs/threads/) — verify exact supported social actions.
- [YouTube Data API](https://developers.google.com/youtube/v3) — channel/comments/live chat.
- [Viber Bot REST API](https://developers.viber.com/docs/api/rest-bot-api/) — check availability and account eligibility.

## E. OS / profiling and privacy

- [Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) — user-granted notification observations, not an inbox sync API.
- [Android permissions restricted to default handlers](https://developer.android.com/guide/topics/permissions/default-handlers) — SMS/call role limitations.
- [Apple UserNotifications](https://developer.apple.com/documentation/usernotifications) — notification extensions for notifications belonging to app; no public all-app inbox feed.
- [Apple MessageUI](https://developer.apple.com/documentation/messageui) — user-controlled compose/handoff route.
- [Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) and [Perfetto](https://perfetto.dev/docs/) — performance evidence.
- [Apple MetricKit](https://developer.apple.com/documentation/metrickit) and [Instruments](https://developer.apple.com/documentation/xcode/instruments) — runtime performance/energy evidence.

## F. Known unknowns / verification checklist (must not be silently filled)

**P0 — model/device:** exact Gemma E2B QAT **text-only** download, LiteRT-LM artifact conversion, model-specific prompt template, Android GPU/NPU on chosen devices, iOS Swift preview stability, real cold TTFT, working-memory peak, thermal throttling, chosen Qwen hybrid support in actual backend, iOS Apple alternatives' licensing/device entitlements, actual Hindi/Telugu naturalness. Vendor data are not MINI test results.

**P0 — privacy:** whether any proposed external relay receives personal message text; whether external APIs allow retaining derived style profiles/embeddings; deleting model caches; app backup behavior; log sanitization; consent records; whether encryption and Keystore withstand device lifecycle/restart, not merely compile.

**P0 — connectors:** Meta professional/business eligibility; whether developer account qualifies for review; exact current versioned Graph endpoint/permissions and messaging window exceptions; LinkedIn direct/Pages API partner access; Gmail restricted-scope compliance cost; Microsoft Teams tenant approval, Slack DM visibility; Android OEM listener behavior; iOS parity impossible for notification capture. The Meta developer pages returned 429/rate limits in this research session; Meta path examples in `03` need direct revalidation.

**P0 — tests:** exact repo HEAD, independent passing builds/tests, physical Android device instrumentation, data-loss and send-duplication fault injection, native DB migration/key invalidation; and iOS builds on macOS/Xcode with actual device.

**P1 — usability:** empty/loading/offline states, UI responsiveness, multilingual fonts/accessibility, model download disclosures, battery consumption in daily use, style onboarding consent, failure modes from PocketPal/Beeper reviews. The user previously designated complaints as a high-priority product-quality checklist; map every actual complaint to a reproducible acceptance test when the full source review is available.

**P1 — economics:** current provider per-message/subscription costs, business/account verification duration, relay compute/storage, model redistribution restrictions, model download bandwidth/storage, App Store/Play policy and privacy labeling, expected support burden.

## Factual hierarchy for subsequent JCode research

1. **Verified in MINI itself:** exact commit/test/device/authorized API call evidence.
2. **Current official docs:** feature/permission availability conditional on account, scope and deployment.
3. **Vendor published benchmarks/model cards:** useful hypothesis, not cross-hardware comparative proof.
4. **Community advice/third-party posts:** leads only, never authoritative on platform permissions.
5. **Engineering inference:** clearly marked proposal, validated via tests before implementation claim.
