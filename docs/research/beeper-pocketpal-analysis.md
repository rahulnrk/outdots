# Beeper and PocketPal: lessons for MINI

Research date: 2026-10-09. Source: `C:\Users\nande\Downloads\32316_merged.pdf`.

## Main product decision

MINI should help people keep up with communication, with less effort and less
distraction. Combine a reliable unified inbox with useful private drafting.
Connecting more networks and generating more tokens are inputs, not the outcome.

Beeper's strongest positive theme in these screenshots is communicating without
entering addictive social feeds, alongside less switching and one familiar inbox.
PocketPal's positive themes are local AI, model flexibility, accessible voice use,
and useful writing when the configuration works. Both show how quickly a promising
idea loses value if the first essential action fails.

## How this research was handled

All 104 pages were visually surveyed and text-recognized. The evidence ledger
consolidates the same named review across overlapping/repeated screenshots.
Partial and unnamed reviews remain explicitly marked. Two entries contain only a
name/date and are not used to infer sentiment. The extracted transcript preserves
every page; OCR can misread names, star symbols, dates and model identifiers.

The ledger contains 164 annotated review records: 91 Beeper and 73 PocketPal,
including those two body-less entries. These are records in this supplied sample,
not independently verified unique customers. There are 40 synthesized findings:
22 for Beeper and 18 for PocketPal. A review can support several findings, and a
mixed review is not forced into a single positive/negative rating.

Page 1 is promotional material; pages 2 and 86 include store-generated AI summaries.
These are not customer testimony. Page 86 shows a performance filter with 64
mentions, 9 positive and 55 negative icons, while its AI summary uses optimistic
wording and describes only a few issues. Those store-displayed figures are not
our deduplicated sample counts and cannot establish current product-wide rates.

Visible reviews span 2024-2026 and often omit app versions. Claims describe the
reviewer's experience at that time. No competitor behavior was reproduced on a
device in this analysis. Cropped continuations were not reconstructed. Abusive
language was excluded from the paraphrases while preserving actionable feedback.

## Beeper: what to preserve and improve

Preserve one inbox, consistent presentation, multiple accounts and feed-free
communication. Optional person linking can reduce clutter, but every message and
reply still needs an unambiguous provider/account identity. A reviewer describing
ADHD-related reply difficulty illustrates the value of fewer places to check; it
does not establish a medical benefit.

The highest-impact failure reports concern missed background notifications,
silent disconnections, confusing send status, difficult reauthorization and
missing history. The next layer is control: mute/archive choices resetting,
unfindable filters, icon-only navigation, unclear reply targets and unwieldy pinned
chats. Media and native-feature gaps require per-provider qualification, not a
blanket promise to replace every source app.

Deletion is a good example of why diagnosis matters: users say it is absent,
while developer replies say it exists for some networks. Filters were also
described by a developer as available. MINI must distinguish an absent capability,
a broken operation and a feature people cannot discover. A later review praises
polls, while earlier reviews complain about them; network/platform/version is
unspecified. Do not turn either statement into a universal current capability.

Pricing comments express loss of trust after changing access to organization,
multiple accounts and queued scheduling. Quoted prices disagree and are not
current pricing evidence. Avoid breaking pending work or surprising people with
limits after they have completed setup. Positive and negative support reports
both appear; a generic response is not proof that an issue was fixed.

## PocketPal: what to preserve and improve

Preserve local inference, an approachable default, useful writing and optional
advanced controls. Its model catalog and personas suit experimentation; MINI's
main flow should let ordinary users reply without becoming model experts.

The first-use failures are expensive downloads that fail near completion,
misleading size/storage expectations, models that will not load, and startup
crashes that appear to require reinstalling. Runtime failures include endless
loading, long-context freezes, slow generation, keyboard lag, failed acceleration
and unusable output. Reports cover both lower-memory and flagship devices, so
neither a small file nor high RAM proves compatibility.

Exact engine/model/template/tokenizer/backend compatibility needs qualification.
Garbled text, repetition and empty replies must be separated from subjective
writing quality. Engine update suggestions in developer replies are possible
fixes, not a follow-up proving resolution. A kernel-panic allegation deserves
investigation but does not establish that the app caused a kernel defect.

Users complain when switching apps stops generation. MINI's existing foreground
restriction is still appropriate for protecting memory. Improve the experience
around it: preserve typed input, retain safe partial output when practical, clearly
explain cancellation and offer a new user-triggered attempt on return. Do not
remove memory safeguards or restart a heavy model silently to imitate background
generation. Downloads need a separate resumable lifecycle where supported.

Claims that offline AI stopped when networking was disabled, and that a voice
engine repeatedly needed internet, are test leads. They do not prove cloud
inference, surveillance or data leakage. Verify offline text inference after
provisioning; verify optional voice assets independently. Provider communication
sync necessarily needs a network and must be explained separately from local AI.

## Current primary-source checks

- [Beeper bridges](https://developers.beeper.com/bridges/): bridges translate
  messages between Matrix and networks; Beeper publishes bridge source. Inspect
  the specific implementation and license before reuse.
- [Beeper connection types](https://help.beeper.com/chat-networks/using-on-device-chat-network-connections-in-beeper):
  on-device connections support multiple accounts and differ from cloud routes;
  not every network has the same available mode.
- [Beeper engineering](https://www.beeper.com/engineering): its Instagram work
  explicitly uses reverse engineering. Aggregator support is not proof of an
  officially endorsed provider API.
- [Beeper WhatsApp guide](https://help.beeper.com/whatsapp?kb_language=en_US):
  linked connections depend on periodic use of the primary WhatsApp app, list
  unsupported features, and provide limited history import. MINI cannot promise
  these provider constraints disappear.
- [Beeper FAQ](https://help.beeper.com/top-beeper-faqs?kb_language=en_US):
  notification controls and scheduling vary by platform and route. Historical
  reviews about missing features require a current capability check.
- [PocketPal official project](https://github.com/a-ghorbani/pocketpal-ai):
  documents phone-local inference, no account for core use, GGUF model access,
  benchmarking and particular acceleration backends. This documents intended
  capabilities, not measured reliability on every phone.

## MINI's opportunity and tradeoffs

The proposed advantage is a communication tool that helps users decide what
needs attention and reply with private, context-grounded English drafts. This is
a product hypothesis; the research does not prove market exclusivity or demand
for MINI's complete feature set.

Use a clear conversation row: person, provider/account, recent message, unread
state and sync health. Show a full replied-to message in the composer and the
exact account that will send. Optional grouping must never collapse unrelated
same-named people or cross personal/work context boundaries.

Use an understandable connection flow: select provider/account type, see actual
capabilities/dependencies, authorize, show sync progress and coverage, and recover
without destroying local history. Gmail, Outlook and Teams belong in the plan,
but their connection status must match actual implementation evidence.

Convenience also means less noise, legible text, clear errors, preserved user
choices and a usable manual reply flow whenever AI fails. Avoid copying advanced
model menus, personas, contact merging, cross-device sync and every network into
the MVP at once. Voice, mathematical rendering and richer media should follow
demonstrated communication needs.

## Order of work before the next Jcode implementation prompt

1. **Prove real account access.** Jcode's reported commit `68e44c5` adds isolated
   account/token contracts and a read-only Gmail connector with tests, but live
   OAuth and two-account sync are unproven. This update is user-reported, not
   independently verified in this review. Finish the proper native public-client
   authorization configuration; do not embed a client secret in the Android app.
2. **Prove message reliability and recovery.** Verify background notifications,
   connection health, completeness labels, history persistence, duplicate handling
   and account isolation. Read-only sync does not prove a working send path.
3. **Make the core workflow easy.** Observe people connecting accounts, finding
   priority messages, choosing the right reply account and recovering a failure.
   Measure task completion, time, wrong turns and unnecessary app switching.
4. **Prove useful safe drafting.** Keep English MVP, foreground protections and
   explicit recovery. Verify cold start, cancellation, repeated use, context
   handling and offline behavior on low-RAM and modern phones. Evaluate usefulness
   and editing effort, not token speed alone. Romanized Hindi/Telugu remains
   exploratory for later builds.
5. **Expand qualified providers.** Reuse the foundation for Outlook Graph and
   separately qualify Teams account/tenant permissions. Research social bridges
   with explicit license, data-flow and device requirements. Increase connector
   count only when the daily workflow remains reliable.

No numerical performance target in this research is claimed as achieved. Set
release thresholds from measured baselines and user task testing. Existing tests
and builds are progress, but do not replace live account/device evidence.

## Detailed findings and evidence

The following sections map every synthesized finding to the relevant PDF pages,
user impact, proposed MINI behavior and a reproducible acceptance check.

## Beeper: complete finding map

### B01 - One inbox reduces app switching

Type: Positive. Category: Convenience. Proposed stage: MVP. PDF pages: 19, 21, 23, 37, 48, 49, 90, 91, 94, 96.

User experience: Users can keep up with people across services and report less effort replying.

MINI response: Keep one inbox with clear source and account labels; include Gmail, Outlook and Teams in the connection plan.

Acceptance check: Receive synthetic conversations from two qualified providers and reply without selecting the wrong account.

Evidence qualification: Reviewer report; not reproduced here

### B02 - Messaging without addictive feeds

Type: Positive. Category: Convenience. Proposed stage: MVP. PDF pages: 19, 23, 24, 26, 28, 29, 31, 32, 35, 43, 44, 45, 91, 93, 94, 100, 102.

User experience: People keep social contact while avoiding reels, status feeds and doomscrolling; one reviewer describes easier follow-through with ADHD.

MINI response: Keep MINI focused on conversations and user-chosen attention; no algorithmic social feed.

Acceptance check: A message can be found and answered without entering a provider feed; compare workflow effort with native apps.

Evidence qualification: Reviewer report; not reproduced here

### B03 - Consistent interface and easy setup for some

Type: Positive. Category: Convenience. Proposed stage: MVP. PDF pages: 7, 10, 20, 33, 48, 50, 90.

User experience: Familiar presentation and good performance can make many networks feel manageable, although other users disagree.

MINI response: Use a consistent Android-native interface, legible text labels and a guided first account setup.

Acceptance check: Observe a first-time user connecting an account and finding a chat without coaching.

Evidence qualification: Reviewer report; not reproduced here

### B04 - Person merging and Matrix interoperability

Type: Unique. Category: Convenience. Proposed stage: Later. PDF pages: 7, 19, 22, 36.

User experience: Merging reduces duplicate contacts but can obscure where messages came from; Matrix openness interests some users.

MINI response: Explore optional user-confirmed person linking; preserve provider/account identity and reversible separation. Do not merge by display name.

Acceptance check: Two same-named people remain separate; linked threads retain source badges and the correct reply route.

Evidence qualification: Distinctive capabilities in this sample, not a claim of market exclusivity

### B05 - Notifications arrive only when opened

Type: Issue. Category: Reliability. Proposed stage: MVP. PDF pages: 3, 4, 5, 7, 9, 10, 14, 15, 16, 42, 103.

User experience: Users miss time-sensitive messages even after trying battery settings. This undermines the entire hub.

MINI response: Prove background intake and OS notifications independently of the heavy AI model; expose stale/disconnected sources.

Acceptance check: Compare synthetic messages with provider truth while screen off, backgrounded, restarted and under OS battery restrictions; record delivery gaps.

Evidence qualification: Reviewer report; not reproduced here

### B06 - Repeated disconnections and painful reconnection

Type: Issue. Category: Reliability. Proposed stage: MVP. PDF pages: 5, 13, 14, 26, 28, 32, 33, 35, 39, 50, 95.

User experience: Reauthorization friction makes the hub harder than using native apps; some users cannot sign back in.

MINI response: Provide per-account health, safe reconnect, preserved local history, and explicit reauthorization steps.

Acceptance check: Expire/revoke one account, reconnect it, and verify other accounts and prior local context stay intact.

Evidence qualification: Reviewer report; not reproduced here

### B07 - Unclear sending state and failed delivery

Type: Issue. Category: Reliability. Proposed stage: MVP. PDF pages: 6, 12, 33.

User experience: A spinner may persist after actual delivery; blind retries could duplicate a message.

MINI response: Represent pending, confirmed, failed and unknown delivery honestly; reconcile provider IDs before retrying.

Acceptance check: Interrupt connectivity after submission, reconcile the result, and prove no duplicate send.

Evidence qualification: Reviewer report; not reproduced here

### B08 - History disappears or new chats are absent

Type: Issue. Category: Reliability. Proposed stage: MVP. PDF pages: 25, 34, 40, 93, 94.

User experience: Users lose access to old context or cannot see a new conversation until opened elsewhere.

MINI response: Publish provider-specific history coverage, preserve reconnect history and detect incomplete sync.

Acceptance check: Reconnect and paginate synthetic old/new chats; verify deduplication, persistence, correct coverage labels and no invented complete-history claim.

Evidence qualification: Reviewer report; not reproduced here

### B09 - Muted and archived chats do not stay that way

Type: Issue. Category: Control. Proposed stage: MVP. PDF pages: 39, 41, 88, 89, 104.

User experience: Unwanted groups reappear or become noisy after updates; users lose trust in their own settings.

MINI response: Persist MINI attention choices; explicitly choose whether new messages reopen archives. Keep provider mute and MINI mute distinct.

Acceptance check: Sync, restart, update and receive a new group message; verify the user-selected mute/archive policy remains unchanged.

Evidence qualification: Reviewer report; not reproduced here

### B10 - Long inboxes and hidden network filters

Type: Issue. Category: Control. Proposed stage: MVP. PDF pages: 8, 11, 18.

User experience: All chats together can become overwhelming; an existing filter is ineffective if people cannot find it.

MINI response: Make source/account filters, low priority and search visible; add optional labels without burying basic navigation.

Acceptance check: Ask a first-time user to find a work account and low-priority chat; measure completion and wrong turns.

Evidence qualification: Developer reply on p8 says network filtering existed; this is also a discoverability problem

### B11 - Icon-only redesign and oversized pinned chats

Type: Issue. Category: Control. Proposed stage: MVP. PDF pages: 17, 18, 30, 44, 45, 94, 100, 101.

User experience: Users lose familiar navigation or must open pinned chats to see the latest message.

MINI response: Use text with icons, compact pinned rows with previews, accessible font scaling and stable navigation.

Acceptance check: At large text size, show sender, account, latest preview and unread state without clipping or excessive taps.

Evidence qualification: Reviewer report; not reproduced here

### B12 - Reply targets, quotes and mentions are wrong or unclear

Type: Issue. Category: Control. Proposed stage: MVP. PDF pages: 29, 30, 42, 44, 101, 103.

User experience: Messages can address the wrong context, leave stale quoted replies or corrupt a tagged name.

MINI response: Show the exact replied-to message and reset the composer after confirmed send; qualify mentions per connector.

Acceptance check: Reply twice to different messages and tag a synthetic contact; verify target IDs, clear quote state and unmodified mention text.

Evidence qualification: Reviewer report; not reproduced here

### B13 - Photos, voice notes, GIFs and videos fail

Type: Issue. Category: Reliability. Proposed stage: After core text workflow. PDF pages: 20, 21, 24, 31, 35, 43, 102.

User experience: Users miss photos/context or the last part of audio and must return to native apps.

MINI response: Support media only where qualified; show unsupported content explicitly with a route to the source app. Preserve full audio length.

Acceptance check: Exercise full voice-note playback, images, video failures and unsupported/view-once types; never silently omit content.

Evidence qualification: Reviewer report; not reproduced here

### B14 - Native features vary by network and version

Type: Issue. Category: Reliability. Proposed stage: MVP. PDF pages: 12, 30, 34, 39, 42, 90, 91, 103.

User experience: Calls, polls, channel comments and group details do not have consistent parity; a later review praises polls.

MINI response: Publish a per-network capability list, explain unavailable actions and offer a source-app handoff.

Acceptance check: Run connector-specific capability checks; never display an enabled action that the route cannot perform.

Evidence qualification: Historical complaints are not proof of current absence; p90/91 praise polls, while current WhatsApp help lists polls unsupported

### B15 - Deletion and editing are unavailable or undiscoverable

Type: Issue. Category: Control. Proposed stage: MVP. PDF pages: 25, 32, 33, 38, 46, 47, 49, 87, 92, 96, 98, 99.

User experience: Users cannot clean clutter or distinguish local deletion from deleting a provider message.

MINI response: Separate hide/archive, local deletion and provider deletion; expose qualified edit/delete actions and safe bulk cleanup.

Acceptance check: Verify each action affects only its stated scope and account; confirm destructive bulk actions clearly.

Evidence qualification: Developer replies on p47/87 say deletion exists for some networks; absence, failure and discoverability must be separated

### B16 - Lag, battery drain and update regressions

Type: Issue. Category: Trust. Proposed stage: MVP. PDF pages: 4, 11, 13, 42, 50, 103.

User experience: The hub itself becomes another heavy app and users prefer stable essentials over more features.

MINI response: Keep inbox work light, measure actual battery use, and gate connector/runtime upgrades on regression checks.

Acceptance check: Measure idle/background and active use on low-RAM and modern phones; verify the inbox survives AI failure.

Evidence qualification: Reviewer report; not reproduced here

### B17 - Cloud discomfort and account security warnings

Type: Issue. Category: Trust. Proposed stage: MVP. PDF pages: 7, 30, 45, 92, 100.

User experience: Some users distrust cloud accounts or report provider security restrictions; a centralized inbox also concentrates sensitive history.

MINI response: Prefer qualified phone-local routes, show where data flows, protect credentials and local history, and surface provider security challenges.

Acceptance check: Inspect data flow and account isolation; show security/reconnect failures without hiding them.

Evidence qualification: These reviews do not prove a data breach or establish the cause of a provider restriction

### B18 - Pricing changes break expected workflows

Type: Issue. Category: Trust. Proposed stage: Product decision. PDF pages: 17, 32, 33, 36, 37, 47, 98.

User experience: Users object to losing categories, multiple accounts or scheduled messages, especially an already queued send.

MINI response: Make limits and pricing clear before setup; preserve pending work across entitlement changes. Decide pricing after cost validation.

Acceptance check: Change an entitlement with queued work and show an explicit outcome; disclose account limits before connection.

Evidence qualification: Reviewer prices differ and are historical; none are treated as the current price

### B19 - Support replies do not reliably close problems

Type: Issue. Category: Trust. Proposed stage: MVP. PDF pages: 15, 25, 41, 91, 104.

User experience: Some praise support; others report repeated generic replies and unresolved problems.

MINI response: Create privacy-safe diagnostics, traceable issue status and an explanation of what recovery can do.

Acceptance check: Export diagnostics without message bodies/tokens; reproduce a failure and show the user a concrete recovery outcome.

Evidence qualification: Reviewer report; not reproduced here

### B20 - Notification sounds and noise preferences

Type: Request. Category: Control. Proposed stage: After reliable notifications. PDF pages: 8, 19, 20, 32, 48, 97.

User experience: People want per-person/network sounds and less noise from shared reels.

MINI response: Use Android notification channels and user-controlled noise rules; do not silently classify all shared content as junk.

Acceptance check: Change one source notification preference and verify important other-source messages still alert.

Evidence qualification: Reviewer report; not reproduced here

### B21 - Email, contacts and more network coverage

Type: Request. Category: Convenience. Proposed stage: MVP foundation; more networks later. PDF pages: 13, 14, 23, 28, 37, 50, 88, 89, 90, 95.

User experience: Users request email, nicknames, contacts, calls, TikTok, Snapchat and Viber; scope expansion competes with stability.

MINI response: Make Gmail, Outlook and Teams first-class scoped providers; assess other networks separately. Keep a contacts view optional.

Acceptance check: Qualify each requested account type and route before making connection claims.

Evidence qualification: Reviewer report; not reproduced here

### B22 - Native-app and cross-device dependencies surprise users

Type: Issue. Category: Trust. Proposed stage: MVP disclosure; cross-device later. PDF pages: 7, 24, 39, 46, 48, 85, 87, 97, 99.

User experience: A unified hub is mistaken for a complete native replacement; linked-device renewal and mobile iMessage limitations disappoint users.

MINI response: Disclose device/session dependencies during setup. Multi-device encrypted sync is a later deliberate design, not an implicit promise.

Acceptance check: Explain and test renewal/handoff behavior; maintain access to native apps where required.

Evidence qualification: Current Beeper WhatsApp guide confirms primary-app use every 12-14 days; cannot promise MINI bypasses it

## PocketPal: complete finding map

### P01 - Private AI on the phone without a core account

Type: Positive. Category: Convenience. Proposed stage: MVP. PDF pages: 64, 70, 71.

User experience: Some users value local models and no hidden payment; privacy is a strong motivation.

MINI response: Retain local English drafting without a MINI cloud account; explain provider sign-in separately.

Acceptance check: After model setup, draft from synthetic local context in airplane mode.

Evidence qualification: Current official project documentation states local inference and no account required for core AI

### P02 - Flexible model choice and useful writing

Type: Positive. Category: Convenience. Proposed stage: MVP. PDF pages: 65, 66, 70, 71, 82.

User experience: Successful users praise smooth local models, Hugging Face access and a useful complex letter.

MINI response: Choose a tested default for ordinary users; retain advanced model options only after compatibility qualification.

Acceptance check: Evaluate useful English communication drafts on target devices; model download alone is not success.

Evidence qualification: Reviewer report; not reproduced here

### P03 - Voice can improve hands-free use

Type: Positive. Category: Convenience. Proposed stage: Later. PDF pages: 69.

User experience: A user combines read-aloud speech with keyboard dictation and requests picture input.

MINI response: Consider local voice accessibility after reliable text; distinguish phone dictation from MINI-owned processing.

Acceptance check: Run a complete offline voice flow with assets present and expose unsupported voice features.

Evidence qualification: Reviewer report; not reproduced here

### P04 - Local model library, personas and benchmarking

Type: Unique. Category: Convenience. Proposed stage: Default model in MVP; advanced tools later. PDF pages: 52, 61, 69, 70, 71, 83.

User experience: Custom Pals and model discovery are powerful, but selection and diagnostic tools can become friction.

MINI response: Borrow device qualification and an optional diagnostic benchmark; keep personas/catalogs out of the main reply flow.

Acceptance check: A normal user can draft without selecting a persona or running a benchmark.

Evidence qualification: Distinctive in this sample, not exclusive to PocketPal; p69/83 also report benchmark crashes

### P05 - Downloads fail near completion and waste mobile data

Type: Issue. Category: AI availability. Proposed stage: MVP. PDF pages: 52, 55, 59, 61, 65, 68, 70, 77, 78, 80, 81, 83.

User experience: Large failed downloads waste hours and expensive bandwidth; retries may require starting over.

MINI response: Use resumable downloads where the host supports ranges, integrity checks, clear byte size and consent for mobile data.

Acceptance check: Interrupt at multiple stages, resume or explain unsupported resume, verify checksum, and count transferred bytes.

Evidence qualification: Reviewer report; not reproduced here

### P06 - File size and storage requirements are misleading

Type: Issue. Category: AI availability. Proposed stage: MVP. PDF pages: 78.

User experience: A reviewer reports a 2.44GB model becoming 4.30GB; free disk space alone does not ensure sufficient runtime memory.

MINI response: Show actual file size, extra installation space, runtime RAM needs and compatibility before downloading.

Acceptance check: Compare advertised and downloaded bytes; handle disk exhaustion safely and explain RAM separately.

Evidence qualification: Reported discrepancy; its cause is not established here

### P07 - App becomes unusable after model download

Type: Issue. Category: AI availability. Proposed stage: MVP. PDF pages: 51, 55, 56, 58, 60, 62, 63, 64, 69, 72, 73, 74, 77, 83.

User experience: Crashes on loading, repeated startup crashes and failed recommended models block the first useful action.

MINI response: Never auto-load a repeatedly failing model; provide safe startup, explicit recovery and a working manual inbox.

Acceptance check: Install an invalid/incompatible synthetic artifact, restart and recover without reinstalling or deleting user conversations.

Evidence qualification: Reviewer report; not reproduced here

### P08 - Compatibility is not just model size or RAM

Type: Issue. Category: AI availability. Proposed stage: MVP. PDF pages: 58, 59, 63, 64, 69, 74, 75, 77, 79, 82, 83.

User experience: Reports span a 4GB Galaxy A05 and high-end phones; both small and large models fail. One severe kernel-panic report is unverified.

MINI response: Qualify exact model format, tokenizer/template, runtime version and device/backend combination. Do not assume high RAM guarantees success.

Acceptance check: Run a known model/runtime/device matrix; treat severe device failures as blockers until investigated.

Evidence qualification: Reviewer assertions about enough RAM or universal failure are not diagnosed causes

### P09 - Slow or endless loading and generation

Type: Issue. Category: AI availability. Proposed stage: MVP. PDF pages: 53, 57, 58, 62, 68, 72, 73, 75, 76, 77, 78, 79, 80, 81, 84.

User experience: Even a greeting can hang; keyboard lag and long waits make on-device AI feel broken.

MINI response: Use bounded load/generation times, responsive cancellation and clear progress; qualify a useful default on low-RAM phones.

Acceptance check: Measure cold start, first token and completion; cancel a stuck draft and keep the inbox responsive.

Evidence qualification: Reviewer report; not reproduced here

### P10 - GPU/NPU hardware claims exceed actual compatibility

Type: Issue. Category: AI availability. Proposed stage: MVP qualification. PDF pages: 75, 77, 79, 82, 86.

User experience: Users see CPU-only fallback despite a capable phone; broad hardware marketing creates false expectations.

MINI response: Report the backend actually used and measured performance; select only tested acceleration paths with fallback.

Acceptance check: Compare detected vs used backend and fallback on Qualcomm and MediaTek target devices.

Evidence qualification: Store AI summary p86 is not evidence that every chipset works; current project docs explicitly distinguish supported backends

### P11 - Long conversations fill context and freeze

Type: Issue. Category: AI availability. Proposed stage: MVP. PDF pages: 53, 61, 73, 74.

User experience: Replies stop after a few turns or paragraphs; users cannot continue the conversation.

MINI response: Budget context, preserve recent relevant messages, summarize only when qualified, and explain truncation instead of freezing.

Acceptance check: Use long synthetic threads; verify bounded prompts and no loss of current intent or invented missing details.

Evidence qualification: Reviewer report; not reproduced here

### P12 - Switching apps interrupts work

Type: Issue. Category: AI availability. Proposed stage: MVP. PDF pages: 66, 67, 68, 69, 80, 81.

User experience: Generation stops/unloads and downloads may stop when backgrounded; users perceive unfinished work as failure.

MINI response: Keep heavy drafting foreground-only for MVP. Save the composer and permitted partial draft, explain interruption and offer a fresh retry on return. Downloads have a separate lifecycle.

Acceptance check: Switch apps during load, generation and download; verify no hidden model restart, clear cancellation, preserved input and safe download recovery.

Evidence qualification: Reviewer report; not reproduced here

### P13 - Empty, repeated or garbled output

Type: Issue. Category: AI quality. Proposed stage: MVP. PDF pages: 52, 56, 72, 74, 76, 78, 82.

User experience: Blank replies, repeated characters and off-topic loops are unusable regardless of token speed.

MINI response: Validate engine/template/tokenizer compatibility; evaluate task usefulness and handle empty/failed output plainly.

Acceptance check: Use English cases with dates, prices, quoted context and user intent; flag empty/repetitive output and allow manual reply.

Evidence qualification: Reviewer report; not reproduced here

### P14 - Generic answers and forced personality

Type: Issue. Category: AI quality. Proposed stage: MVP. PDF pages: 72, 74, 78, 82.

User experience: Users dislike fake friendliness, misunderstood requests and inappropriate or off-topic output.

MINI response: Draft grounded, concise replies in the user-selected tone; require exact review and never send automatically.

Acceptance check: Blindly assess English drafts for factual/context accuracy, tone and editing effort; retain romanized Hindi/Telugu exploratory cases.

Evidence qualification: Reviewer report; not reproduced here

### P15 - Setup and sign-in boundaries are confusing

Type: Issue. Category: Control. Proposed stage: MVP. PDF pages: 51, 52, 61, 84, 86.

User experience: People do not know how to load a model, get redirected unexpectedly or believe local Pals require sign-in.

MINI response: Guide the first usable draft; distinguish local AI setup, provider OAuth and optional online catalogs. No unexplained store redirect.

Acceptance check: Observe setup without coaching; core local drafting works without catalog sign-in.

Evidence qualification: Developer p61 says local Pals do not require sign-in; the review establishes confusion, not a proven account requirement

### P16 - Offline and voice behavior is questioned

Type: Issue. Category: Trust. Proposed stage: Text MVP; voice later. PDF pages: 67, 68, 69.

User experience: A reviewer says disabling SIM stopped AI; another says voice needs internet repeatedly. These are trust concerns, not proof of cloud inference.

MINI response: Verify offline drafting and downloaded voice assets separately; disclose network use for downloads and communication sync.

Acceptance check: After provisioning, restart in airplane mode and measure network attempts with synthetic data; test text and voice separately.

Evidence qualification: Unverified reviewer allegations; cannot infer data exfiltration or a privacy breach

### P17 - Readable formatting, images and accessible output

Type: Request. Category: Control. Proposed stage: Plain text MVP; math/media later. PDF pages: 65, 69.

User experience: Correct generated math is hard to read without rendering; users ask for image input and spoken interaction.

MINI response: Use clear copyable/editable reply formatting in MVP. Consider math rendering and image/voice input only for demonstrated communication needs.

Acceptance check: Verify multiline text, copying and large-font display; qualify any later media model input.

Evidence qualification: Reviewer report; not reproduced here

### P18 - Updates can introduce regressions

Type: Issue. Category: Trust. Proposed stage: MVP. PDF pages: 56, 57, 61, 64, 74.

User experience: Previously working configurations break; developer replies describe engine and model-support fixes.

MINI response: Pin qualified runtime/model versions and rerun cold-start, repeated-use, cancellation and recovery checks before upgrades.

Acceptance check: Repeat the accepted device/model workflow after an upgrade with existing local state.

Evidence qualification: Developer replies are proposed fixes; reviewers did not verify resolution in these screenshots

## Review-by-review evidence ledger

Same review across overlapping screenshots appears once here. IDs are research records, not verified identities. Entries with no visible body are explicitly retained without a finding.

- **BR001 | Beeper | Timothy Dougherty** - PDF pp. 2, 3. Notifications require foreground despite battery-setting attempts. Findings: B05. 

- **BR002 | Beeper | Ryan Hobbs** - PDF pp. 3, 4, 26, 27. Likes unified concept; clunky layout, sporadic links, missed alerts and battery use caused abandonment. Findings: B01, B05, B06, B11, B16. 

- **BR003 | Beeper | Greg Haines** - PDF pp. 4, 5. Previously liked app; reports late alerts, hard reconnection and perceived decline after monetization. Findings: B05, B06, B18. 

- **BR004 | Beeper | Black Knight** - PDF pp. 5, 6. Missing mobile notifications and sending spinner despite desktop delivery. Findings: B05, B07. 

- **BR005 | Beeper | Arin Taylor** - PDF pp. 6, 7. Fast UI and rare reconnects; Messenger notifications, cloud discomfort and merged-source clarity remain problems. Findings: B03, B04, B05, B17, B18. 

- **BR006 | Beeper | Directive Taurus** - PDF pp. 7, 8. Wants discoverable network filters and a different notification sound. Findings: B10, B20. Developer explains an existing network filter.

- **BR007 | Beeper | Michael Goodin** - PDF pp. 8, 9. Broken Google Messages connection was unnoticed; critical messages missed. Findings: B05, B06. Developer mentions disconnection banners; effectiveness still disputed.

- **BR008 | Beeper | Chris Payne** - PDF pp. 9, 10. Beautiful useful aggregation; background notifications fail. Findings: B01, B03, B05. Developer references device-specific notification issues.

- **BR009 | Beeper | Moshiur Rahman (Adib)** - PDF pp. 11. Works but lags on weaker phone; requests hiding chats and folders. Findings: B10, B16. 

- **BR010 | Beeper | Aaron Knobloch** - PDF pp. 11, 12. Cannot replace native apps due to delays, timeouts and missing native actions. Findings: B07, B14, B22. 2024 report, not a current capability audit.

- **BR011 | Beeper | Will Widlof** - PDF pp. 12, 41, 42, 103, 104. Stability before features: polls, battery, reply targets, Android behavior and foreground-only notifications. Findings: B05, B12, B14, B16. 

- **BR012 | Beeper | Edgar Perez** - PDF pp. 13. Likes idea; background crashes and SMS delays. Findings: B05, B16. 

- **BR013 | Beeper | Mark Kim Caladcad** - PDF pp. 13, 14. Requests more networks/deletion; reports delayed alerts, logouts and wrong Messenger content. Findings: B05, B06, B12, B15, B21. 

- **BR014 | Beeper | Syros Only** - PDF pp. 14. Likes app but no Discord/Instagram alerts. Findings: B05. 

- **BR015 | Beeper | RACHA NIKHIL** - PDF pp. 15. Praises app but intermittent missing notifications. Findings: B05. 

- **BR016 | Beeper | Chris** - PDF pp. 15, 16. Notifications absent for months; support did not resolve. Findings: B05, B19. 

- **BR017 | Beeper | LUXE MTL** - PDF pp. 15, 16. Consolidation is good; new-message alerts sometimes absent. Findings: B01, B05. 

- **BR018 | Beeper | Shehu Awwal** - PDF pp. 16. No notifications while away from app. Findings: B05. 

- **BR019 | Beeper | Kady Cooley** - PDF pp. 17, 18. Categories are essential; layout/font requests and objection to premium labels. Findings: B10, B11, B18. Historical reviewer price is not current pricing.

- **BR020 | Beeper | Sagasu Jin** - PDF pp. 18. Multi-account function good; icon-only bar and buried low-priority view are confusing; reminders unwanted. Findings: B01, B10, B11. 

- **BR021 | Beeper | mauricio curiel** - PDF pp. 19. Avoids social apps and enjoys optional person-based contact merging. Findings: B01, B02, B04. 

- **BR022 | Beeper | Ved** - PDF pp. 19, 20. Likes clean design; requests reel alert suppression and typing indicators. Findings: B03, B14, B20. Reviewer explanation of Meta API limits is speculation.

- **BR023 | Beeper | Dan Goelz** - PDF pp. 20, 21. Feed-free Instagram messaging good; audio tail missing and phone-number link clutter. Findings: B02, B13. 

- **BR024 | Beeper | Miguel Silveira** - PDF pp. 21, 22. Staying on top of cross-platform messages became much easier. Findings: B01. 

- **BR025 | Beeper | Michael** - PDF pp. 22. Wants stronger native Matrix support on all devices. Findings: B04. 

- **BR026 | Beeper | Emma Lantos** - PDF pp. 22, 23. Setup problems then good experience; reduced social addiction; requests nicknames. Findings: B02, B03, B21. 

- **BR027 | Beeper | Andrew Selzer-Wong** - PDF pp. 23. Single inbox helps with ADHD-related reply follow-through. Findings: B01, B02. Review cropped at end; no invented continuation.

- **BR028 | Beeper | Otto Werse** - PDF pp. 24. Broken GIF picker; requests easier on-device cross-device sync. Findings: B13, B22. 

- **BR029 | Beeper | Zac Bird** - PDF pp. 24. Can remove Instagram yet keep messages, receipts, audio and shared reels; minor bugs. Findings: B02, B13. Review ends mid-sentence.

- **BR030 | Beeper | Troy Greer** - PDF pp. 25. Google Messages history lost after reconnect; unresolved support and recurrence. Findings: B06, B08, B19. Developer mentions limited recent-chat backfill.

- **BR031 | Beeper | James Petty** - PDF pp. 25. Requests select-all deletion for archived junk messages. Findings: B15. 

- **BR032 | Beeper | Name cropped (developer addresses Tomas)** - PDF pp. 26. Logout prevents reentry and partial feature coverage requires original apps. Findings: B06, B14, B22. Reviewer identity cropped; not merged with Tomas Landovsky.

- **BR033 | Beeper | Thevindu D** - PDF pp. 26, 27. Instagram communication without unwanted feed content. Findings: B02. 

- **BR034 | Beeper | Nicholas Tom (partially visible)** - PDF pp. 28. Uses Instagram inbox without scrolling. Findings: B02. 

- **BR035 | Beeper | Chris Raaz** - PDF pp. 28. Requests scheduling and smoother account linking. Findings: B06, B14. 

- **BR036 | Beeper | Nicolas Pereira M.** - PDF pp. 28, 29. Free version and reduced screen time praised; quoted reply persists after sending. Findings: B02, B12, B18. 

- **BR037 | Beeper | Dan Horner** - PDF pp. 29. Instagram sync/history works and avoids doomscrolling. Findings: B02, B08. 

- **BR038 | Beeper | Yurii Bychenok** - PDF pp. 29, 30. Useful on Fold; Telegram channel comments and reply/citation UI limited. Findings: B03, B12, B14. 

- **BR039 | Beeper | Name unclear (looks like elp)** - PDF pp. 30. No ads praised; missing Instagram features and concern about data security. Findings: B14, B17. 

- **BR040 | Beeper | Name cropped, 7/25/26** - PDF pp. 31. One inbox and less reel scrolling work well. Findings: B01, B02. 

- **BR041 | Beeper | Just Jesse** - PDF pp. 31. Gets feed-free Instagram messaging but photos unavailable. Findings: B02, B13. 

- **BR042 | Beeper | Michelle Tasker** - PDF pp. 31, 32. Frequent disconnects require setup-like recovery; deletion and per-person sounds lacking; value questioned. Findings: B06, B15, B18, B20. 

- **BR043 | Beeper | Don Quixote** - PDF pp. 32. Messaging without reels helps reduce doomscrolling. Findings: B02. Review cropped at end.

- **BR044 | Beeper | Matt Molloy** - PDF pp. 33. Reliable unified accounts and feature coverage praised. Findings: B01, B03. 

- **BR045 | Beeper | Damien Thompkins** - PDF pp. 33, 34. Disconnects, failed sends, deletion and pricing concerns. Findings: B06, B07, B15, B18. Developer acknowledges reliability work; quoted price unverified.

- **BR046 | Beeper | Ezra Snow** - PDF pp. 34. Google Messages/WhatsApp mostly work; polls/group data, Discord/Google Chat and old-history gaps. Findings: B08, B14. 

- **BR047 | Beeper | Noreen B** - PDF pp. 34, 35. Instagram/WhatsApp reconnect loops; camera crash, video and voice playback errors. Findings: B06, B13. 

- **BR048 | Beeper | J. Dominic Jackson** - PDF pp. 35. Instagram/Facebook messages without feeds. Findings: B02. 

- **BR049 | Beeper | Name cropped, 10/16/25** - PDF pp. 36. Matrix appreciated; connection issues, pinch zoom and desktop polish requested. Findings: B04, B06, B11. 

- **BR050 | Beeper | Kris Bennett** - PDF pp. 36. Paywall change left scheduled message unsent; subscription value disputed. Findings: B18. 

- **BR051 | Beeper | Isaiah Hawkins** - PDF pp. 36, 38. Previously good, now messages no longer work. Findings: B08, B16. Symptom broad; no diagnosed cause.

- **BR052 | Beeper | Jl Rony** - PDF pp. 37. One inbox replaces many heavy apps. Findings: B01, B16. 

- **BR053 | Beeper | Joshua Cordova** - PDF pp. 37. Good organization; requests email, contacts and calls, prefers affordable lifetime pricing. Findings: B01, B18, B21. 

- **BR054 | Beeper | Alexander Gomez** - PDF pp. 38. Deletion does not remove all chats. Findings: B15. 

- **BR055 | Beeper | Vlad Levitsky** - PDF pp. 38, 39. Messenger reauth/zoom problems, archived chats reappear, polls/media gaps. Findings: B06, B09, B11, B13, B14. 

- **BR056 | Beeper | Harry Thompson** - PDF pp. 39. Works as secondary-device aggregator but expectations for main-phone SMS replacement unmet. Findings: B22. Review cropped at end; causality not verified.

- **BR057 | Beeper | Dev .B** - PDF pp. 40. Sync mostly good; new chats require opening on source app; avoids Instagram feed. Findings: B02, B08. 

- **BR058 | Beeper | Karas Elbardisy** - PDF pp. 40, 41, 104. Unified inbox good; WhatsApp group mute resets repeatedly and support unresolved. Findings: B01, B09, B19. 

- **BR059 | Beeper | Sonal Saha** - PDF pp. 42, 43, 102, 103. Texting via Instagram/LinkedIn without status and feeds is valuable. Findings: B02. 

- **BR060 | Beeper | Andy N (Andstein)** - PDF pp. 43, 102. Instagram photo variants missing, losing photos and context. Findings: B13. 

- **BR061 | Beeper | Name cropped, 6/10/26** - PDF pp. 44, 101. Google Chat tag loses first character of person name. Findings: B12. 

- **BR062 | Beeper | Kardam Nedyalkov** - PDF pp. 44, 101. Can chat with friends without using Instagram. Findings: B02. 

- **BR063 | Beeper | Sadman Sakib** - PDF pp. 44, 45, 100, 101. Pinned grid wastes space and hides last-message preview. Findings: B11. Discriminatory insults excluded from analysis; retain only actionable UI criticism.

- **BR064 | Beeper | Rizky** - PDF pp. 45, 100. Reduced screen time praised; reports Instagram security restriction. Findings: B02, B17. Ends mid-sentence; cause of security warning unproven.

- **BR065 | Beeper | R** - PDF pp. 46, 99. Expected WhatsApp without primary app; dependency disappointing. Findings: B22. Developer explains periodic primary-app use.

- **BR066 | Beeper | Rehan Zahid** - PDF pp. 46, 47, 98, 99. Likes experience but cannot delete messages. Findings: B15. Developer says deletion exists; route/failure/discoverability unresolved.

- **BR067 | Beeper | Vaclav Zak** - PDF pp. 47, 98. Cannot add two WhatsApp accounts free. Findings: B18. Historical entitlement claim, not current plan policy.

- **BR068 | Beeper | Micheal Hylman** - PDF pp. 48, 97. Likes app; wants mobile iMessage parity. Findings: B22. 

- **BR069 | Beeper | Amirhosein Haddadiyan** - PDF pp. 48, 97. Likes app; asks for iMessage-like sending sound. Findings: B20. 

- **BR070 | Beeper | XBHS** - PDF pp. 48, 97. One consistent interface across networks praised. Findings: B01, B03. 

- **BR071 | Beeper | Yousef Rekaaney** - PDF pp. 49, 96. One social inbox saves time and simplifies messaging. Findings: B01. 

- **BR072 | Beeper | Eric Graham** - PDF pp. 49, 96. Expected editing with Apple users; cannot find/use it. Findings: B15, B22. 

- **BR073 | Beeper | Ivan Maurizi** - PDF pp. 49, 50, 95, 96. Likes theme and removing social apps; frequent Facebook/Instagram authorization. Findings: B02, B03, B06. 

- **BR074 | Beeper | Papaya** - PDF pp. 50, 95. Likes idea; wants smoother performance and TikTok support. Findings: B16, B21. 

- **BR075 | Beeper | Maximusuperplus Mass** - PDF pp. 50, 94, 95. Centralization good; requests old UI option. Findings: B01, B11. 

- **BR076 | Beeper | Halbour** - PDF pp. 85, 87. Chat tasks easier. Findings: B01. 

- **BR077 | Beeper | Greek-script reviewer, name unclear** - PDF pp. 85, 87. Expected paid iMessage access for family communication. Findings: B18, B22. Developer says mobile iMessage offering disabled in 2023.

- **BR078 | Beeper | Tom Brand** - PDF pp. 85. Likes chatting with app. Findings: B01. Generic positive; little specific evidence.

- **BR079 | Beeper | Tapan 254 254** - PDF pp. 87, 88, 89. Requests delete chat history. Findings: B15. Developer says available on some networks.

- **BR080 | Beeper | Ureki Jeffery** - PDF pp. 88, 89. Wants WhatsApp archive preserved on new messages. Findings: B09. 

- **BR081 | Beeper | Anas Shahid** - PDF pp. 88, 89. Everything good; requests TikTok chat. Findings: B21. 

- **BR082 | Beeper | Jort** - PDF pp. 90, 91. Praises newly available polls. Findings: B14. Conflict with earlier polls complaints; provider/platform not specified.

- **BR083 | Beeper | Jackson Baker** - PDF pp. 90, 91. Likes app; requests Snapchat and starting Google Voice conversations. Findings: B21. 

- **BR084 | Beeper | Tomas Landovsky** - PDF pp. 90. Unified inbox relief and easy setup. Findings: B01, B03. 

- **BR085 | Beeper | Eric Lobdell** - PDF pp. 91, 92. Working unified messaging, support and frequent updates praised. Findings: B01, B19. 

- **BR086 | Beeper | Brandon Lam** - PDF pp. 91. Messaging friends without Instagram doomscrolling. Findings: B02. 

- **BR087 | Beeper | B (name unclear)** - PDF pp. 92, 93. Worries inability to delete history exposes chats if phone stolen. Findings: B15, B17. Concern is not a demonstrated encryption failure.

- **BR088 | Beeper | Saad Aamer** - PDF pp. 92. Good experience but cannot delete chats. Findings: B15. 

- **BR089 | Beeper | Jackson** - PDF pp. 93, 94. One inbox good; sometimes messages fail to load. Findings: B01, B08. 

- **BR090 | Beeper | Noah Roberts** - PDF pp. 93. Instagram messages without opening the distracting app. Findings: B02. 

- **BR091 | Beeper | Benjamin** - PDF pp. 94. Inbox removes excuses to extend Instagram screen time. Findings: B02. 

- **PR001 | PocketPal | Name cropped at top** - PDF pp. 51. Heavy app makes phone hang. Findings: P09. 

- **PR002 | PocketPal | ketan patel** - PDF pp. 51. App does not work. Findings: P07. No specific failure detail.

- **PR003 | PocketPal | Angel** - PDF pp. 51. Repeated crashes. Findings: P07. 

- **PR004 | PocketPal | Ayan Khan** - PDF pp. 51. Cannot use app; sees load-model requirement. Findings: P15. 

- **PR005 | PocketPal | Ammaar Mohammad** - PDF pp. 52, 53, 54. Crashes and wasted data; offline promotion raised expectations. Findings: P05, P07, P16. 

- **PR006 | PocketPal | cokeandcake** - PDF pp. 52. Sage Pal returns empty messages. Findings: P04, P13. 

- **PR007 | PocketPal | Weall Lovecashapp** - PDF pp. 52. Model installation redirects to Play Store. Findings: P05, P15. 

- **PR008 | PocketPal | Muhammad Mustafa** - PDF pp. 52. Only name/date visible. Findings: None; body absent. No review body visible; cannot infer sentiment or issue.

- **PR009 | PocketPal | Antony Lee** - PDF pp. 53, 54, 55, 73. Long load followed by filled context and frozen chat. Findings: P09, P11. 

- **PR010 | PocketPal | Joshua** - PDF pp. 53, 54, 55. Model-load crashes despite other loaders working. Findings: P07, P08. Developer suggests version update and Flash Attention settings; response clipped.

- **PR011 | PocketPal | D4rknecromancer** - PDF pp. 55, 56. New model download leaves app unreopenable; reinstalls required. Findings: P05, P07. 

- **PR012 | PocketPal | Abid osuli** - PDF pp. 55. Download crash near 90% in Afghanistan. Findings: P05. 

- **PR013 | PocketPal | Bryan Bunch** - PDF pp. 56, 57. Unspecified bug. Findings: P07. No reproducible detail; not counted as a diagnosed model-load failure.

- **PR014 | PocketPal | shervin tavakoli** - PDF pp. 56, 57. Force-close failures after update. Findings: P07, P18. 

- **PR015 | PocketPal | Asher** - PDF pp. 56. Completion HostFunction exception; previously worked. Findings: P13, P18. 

- **PR016 | PocketPal | Electi** - PDF pp. 57. Crash after Bonsai-8B download. Findings: P07, P08. Developer says engine updated in 1.13.3; not a verified fix here.

- **PR017 | PocketPal | Bengali-script reviewer, name unclear** - PDF pp. 58, 59. Startup crash after reported 64MB model on Galaxy A05, Android 15, 4GB RAM. Findings: P07, P08. 

- **PR018 | PocketPal | Abubakar S.** - PDF pp. 58. Requests fewer crashes. Findings: P07. 

- **PR019 | PocketPal | Cameron** - PDF pp. 57, 58, 74, 75. Slow/hanging/crashing on RedMagic 10 Pro with 16GB RAM. Findings: P08, P09. 

- **PR020 | PocketPal | Alessandro Zannone** - PDF pp. 59, 60. 1-2GB models crash on Galaxy S25 Ultra. Findings: P07, P08. 

- **PR021 | PocketPal | ch umer** - PDF pp. 59. SmolVLM2 does not load; Qwen 2.5 3B download timeout. Findings: P05, P08. 

- **PR022 | PocketPal | Prim Aski (Prim)** - PDF pp. 60, 63. Gemma load crashes despite perceived sufficient RAM. Findings: P07, P08. 

- **PR023 | PocketPal | liquidReverb** - PDF pp. 60, 63. App will not open. Findings: P07. 

- **PR024 | PocketPal | Tim J** - PDF pp. 60. Download, complete or incomplete, leads to persistent crashes. Findings: P05, P07. 

- **PR025 | PocketPal | Xenia** - PDF pp. 61, 62. Local Pal sign-in confusion and replies stop after a few turns. Findings: P11, P15. Developer says local Pals require no sign-in; clarify discoverability.

- **PR026 | PocketPal | Arnab Ghose** - PDF pp. 61. Broad complaint app does not function. Findings: P07. No diagnosed cause.

- **PR027 | PocketPal | Rajakishor Sahoo** - PDF pp. 61. Failed model download wastes 400MB. Findings: P05. Review continuation clipped.

- **PR028 | PocketPal | LunarLight** - PDF pp. 62, 64, 76. Initially works, later same model/settings load forever even for Hello. Findings: P09, P18. 

- **PR029 | PocketPal | RAFAELHWEJJ 1** - PDF pp. 62. App closes when model selected. Findings: P07. 

- **PR030 | PocketPal | rajeev kr.** - PDF pp. 62. Fresh install does not open. Findings: P07. 

- **PR031 | PocketPal | LuminumLX** - PDF pp. 63. Reports Galaxy S10 kernel panics. Findings: P08. Severe allegation; not reproduced or diagnosed.

- **PR032 | PocketPal | Mark Fleener** - PDF pp. 63. Repeated crashes regardless of attempts. Findings: P07. 

- **PR033 | PocketPal | nemesisx47** - PDF pp. 64. Responsive local LLM app with no hidden payment praised. Findings: P01. 

- **PR034 | PocketPal | Raivis Alksnins** - PDF pp. 64. Previously worked; model now crashes on Z Fold4. Findings: P07, P18. 

- **PR035 | PocketPal | Usmani Usmani** - PDF pp. 65, 66. Repeated near-end download failures waste costly slow internet. Findings: P05. 

- **PR036 | PocketPal | Rajdeep Barma** - PDF pp. 65. Models smooth and capable; requests proper LaTeX rendering. Findings: P02, P17. 

- **PR037 | PocketPal | Yordan Tuzsuzov** - PDF pp. 65. Only name visible. Findings: None; body absent. No review body visible; cannot infer sentiment or issue.

- **PR038 | PocketPal | Raymond Tracer** - PDF pp. 66, 67. Generation stops/model unloads on switching apps. Findings: P12. 

- **PR039 | PocketPal | Rishi Singh** - PDF pp. 66. Praises app and argues oversized models can overwhelm phones. Findings: P02, P08. Reviewer explanation is not proof of other reviewers causes.

- **PR040 | PocketPal | fredi1356** - PDF pp. 67, 68. Reports AI stops when all networking including SIM is disabled. Findings: P16. Beginning partly clipped; no proof of cloud processing.

- **PR041 | PocketPal | Mohamed Emad** - PDF pp. 67. Supertonic voice inconsistent and reportedly needs initial internet each time. Findings: P03, P16. 

- **PR042 | PocketPal | Mike Dean** - PDF pp. 68, 69, 80, 81, 83. Downloads interrupted on exit; model loads incorrectly. Findings: P05, P12. 

- **PR043 | PocketPal | kaestruu kaestru** - PDF pp. 68, 80, 81. Small model slow, inaccurate and crashes on Poco X7 Pro. Findings: P08, P09, P14. 

- **PR044 | PocketPal | Toyin Blessed** - PDF pp. 69. Voice/keyboard combo useful; suggests settings tuning and picture input. Findings: P03, P17. 

- **PR045 | PocketPal | James MC Lee** - PDF pp. 69, 83. Recommended model and benchmark button crash. Findings: P04, P07, P08. 

- **PR046 | PocketPal | Dawid Chmielewski** - PDF pp. 70, 71. Cannot download a model. Findings: P05. 

- **PR047 | PocketPal | Jake** - PDF pp. 70, 71. Local model loading praised. Findings: P01. 

- **PR048 | PocketPal | Bronze James** - PDF pp. 70, 71. Fast Hugging Face downloads praised. Findings: P02. 

- **PR049 | PocketPal | Justin Becker** - PDF pp. 70. Crashes loading any model. Findings: P07. 

- **PR050 | PocketPal | Name cropped, continuation on p72** - PDF pp. 72. Dissatisfied with factual/current-events answers. Findings: P14. Incomplete body; neither factual claims nor identity verified.

- **PR051 | PocketPal | Brennon** - PDF pp. 72. Downloaded model never loads. Findings: P07. 

- **PR052 | PocketPal | Mazhar Ali** - PDF pp. 72. Slow response and poor answers. Findings: P09, P14. 

- **PR053 | PocketPal | Vaidik bhoi** - PDF pp. 72. Downloaded models do not load. Findings: P07. 

- **PR054 | PocketPal | Jason Whitney** - PDF pp. 73, 74. Only a few paragraphs then off-topic/inappropriate output. Findings: P11, P14. Cause may be model, template or runtime; not established.

- **PR055 | PocketPal | Denis Plenert** - PDF pp. 73. Good but not all models work. Findings: P02, P08. 

- **PR056 | PocketPal | Seth Allen** - PDF pp. 73. Downloaded models fail and are slow. Findings: P07, P09. 

- **PR057 | PocketPal | W Anderson** - PDF pp. 74, 75. Gemma 4 e2b fails on Pixel 9 Pro. Findings: P08, P18. Developer says support added in 1.13.3; no follow-up confirming fix.

- **PR058 | PocketPal | Soumyadeep Ghosh** - PDF pp. 75, 77. CPU-only on Realme X2 Pro despite expected acceleration. Findings: P10. 

- **PR059 | PocketPal | Blake Houghton** - PDF pp. 76. Garbled outputs and repeated single Kanji. Findings: P13. 

- **PR060 | PocketPal | Kamil Pawelczyk** - PDF pp. 76. Repeated download attempts followed by gibberish. Findings: P05, P13. 

- **PR061 | PocketPal | Kurikutsu Nanami** - PDF pp. 76, 77. GPU/NPU unrecognized; slow CPU-only lightweight model. Findings: P09, P10. 

- **PR062 | PocketPal | Quincy Beezel** - PDF pp. 77. Requests lighter/faster model; smallest chosen model crashes. Findings: P07, P08, P09. 

- **PR063 | PocketPal | Hi Hay (name partly visible)** - PDF pp. 77. Downloads repeatedly fail and take too long. Findings: P05. 

- **PR064 | PocketPal | MONISHA KAWART** - PDF pp. 78, 82. Slow, generic/off-topic, repetitive replies and unwanted personality. Findings: P13, P14. 

- **PR065 | PocketPal | Mia** - PDF pp. 78. Hours spent downloading; advertised and observed model size differ. Findings: P05, P06. 

- **PR066 | PocketPal | A B** - PDF pp. 79. Many small models fail while a very large quantized model runs slowly. Findings: P08, P09. 

- **PR067 | PocketPal | Eduardo Farkas** - PDF pp. 79. MediaTek/Mali backend unsupported; very slow Hello prompt. Findings: P09, P10. 

- **PR068 | PocketPal | Scott Egerton** - PDF pp. 79, 82. Useful complex letter comparable to online AI. Findings: P02. 

- **PR069 | PocketPal | dnel** - PDF pp. 80, 81. Design good but models load forever. Findings: P09. 

- **PR070 | PocketPal | Vikash Kumar** - PDF pp. 82. Qwen model heavily lags Redmi Note 5 Pro and affects display. Findings: P08, P09. Exact quantization OCR uncertain; not used as a verified specification.

- **PR071 | PocketPal | Kosis Ray** - PDF pp. 83, 84. Large model download fails at end wasting time/data. Findings: P05. 

- **PR072 | PocketPal | KIM** - PDF pp. 84, 86. Setup instructions missing and replies stream extremely slowly. Findings: P09, P15. 

- **PR073 | PocketPal | Sparky Keffer** - PDF pp. 84. Keyboard crashes and system lag. Findings: P09. 

## Page coverage audit

All 104 pages are represented below. Pages may repeat the same reviews. Contextual app identification is used when the screenshot itself has no app title.
- Page 1: Beeper; Promotional screenshot; records No review body; promotional page.
- Page 2: Beeper; Store AI summary + reviews; records BR001.
- Page 3: Beeper; Review screenshots; records BR001, BR002.
- Page 4: Beeper; Review screenshots; records BR002, BR003.
- Page 5: Beeper; Review screenshots; records BR003, BR004.
- Page 6: Beeper; Review screenshots; records BR004, BR005.
- Page 7: Beeper; Review screenshots; records BR005, BR006.
- Page 8: Beeper; Review screenshots; records BR006, BR007.
- Page 9: Beeper; Review screenshots; records BR007, BR008.
- Page 10: Beeper; Review screenshots; records BR008.
- Page 11: Beeper; Review screenshots; records BR009, BR010.
- Page 12: Beeper; Review screenshots; records BR010, BR011.
- Page 13: Beeper; Review screenshots; records BR012, BR013.
- Page 14: Beeper; Review screenshots; records BR013, BR014.
- Page 15: Beeper; Review screenshots; records BR015, BR016, BR017.
- Page 16: Beeper; Review screenshots; records BR016, BR017, BR018.
- Page 17: Beeper; Review screenshots; records BR019.
- Page 18: Beeper; Review screenshots; records BR019, BR020.
- Page 19: Beeper; Review screenshots; records BR021, BR022.
- Page 20: Beeper; Review screenshots; records BR022, BR023.
- Page 21: Beeper; Review screenshots; records BR023, BR024.
- Page 22: Beeper; Review screenshots; records BR024, BR025, BR026.
- Page 23: Beeper; Review screenshots; records BR026, BR027.
- Page 24: Beeper; Review screenshots; records BR028, BR029.
- Page 25: Beeper; Review screenshots; records BR030, BR031.
- Page 26: Beeper; Review screenshots; records BR002, BR032, BR033.
- Page 27: Beeper; Review screenshots; records BR002, BR033.
- Page 28: Beeper; Review screenshots; records BR034, BR035, BR036.
- Page 29: Beeper; Review screenshots; records BR036, BR037, BR038.
- Page 30: Beeper; Review screenshots; records BR038, BR039.
- Page 31: Beeper; Review screenshots; records BR040, BR041, BR042.
- Page 32: Beeper; Review screenshots; records BR042, BR043.
- Page 33: Beeper; Review screenshots; records BR044, BR045.
- Page 34: Beeper; Review screenshots; records BR045, BR046, BR047.
- Page 35: Beeper; Review screenshots; records BR047, BR048.
- Page 36: Beeper; Review screenshots; records BR049, BR050, BR051.
- Page 37: Beeper; Review screenshots; records BR052, BR053.
- Page 38: Beeper; Review screenshots; records BR051, BR054, BR055.
- Page 39: Beeper; Review screenshots; records BR055, BR056.
- Page 40: Beeper; Review screenshots; records BR057, BR058.
- Page 41: Beeper; Review screenshots; records BR011, BR058.
- Page 42: Beeper; Review screenshots; records BR011, BR059.
- Page 43: Beeper; Review screenshots; records BR059, BR060.
- Page 44: Beeper; Review screenshots; records BR061, BR062, BR063.
- Page 45: Beeper; Review screenshots; records BR063, BR064.
- Page 46: Beeper; Review screenshots; records BR065, BR066.
- Page 47: Beeper; Review screenshots; records BR066, BR067.
- Page 48: Beeper; Review screenshots; records BR068, BR069, BR070.
- Page 49: Beeper; Review screenshots; records BR071, BR072, BR073.
- Page 50: Beeper; Review screenshots; records BR073, BR074, BR075.
- Page 51: PocketPal; Review screenshots; records PR001, PR002, PR003, PR004.
- Page 52: PocketPal; Review screenshots; records PR005, PR006, PR007, PR008.
- Page 53: PocketPal; Review screenshots; records PR005, PR009, PR010.
- Page 54: PocketPal; Review screenshots; records PR005, PR009, PR010.
- Page 55: PocketPal; Review screenshots; records PR009, PR010, PR011, PR012.
- Page 56: PocketPal; Review screenshots; records PR011, PR013, PR014, PR015.
- Page 57: PocketPal; Review screenshots; records PR013, PR014, PR016, PR019.
- Page 58: PocketPal; Review screenshots; records PR017, PR018, PR019.
- Page 59: PocketPal; Review screenshots; records PR017, PR020, PR021.
- Page 60: PocketPal; Review screenshots; records PR020, PR022, PR023, PR024.
- Page 61: PocketPal; Review screenshots; records PR025, PR026, PR027.
- Page 62: PocketPal; Review screenshots; records PR025, PR028, PR029, PR030.
- Page 63: PocketPal; Review screenshots; records PR022, PR023, PR031, PR032.
- Page 64: PocketPal; Review screenshots; records PR028, PR033, PR034.
- Page 65: PocketPal; Review screenshots; records PR035, PR036, PR037.
- Page 66: PocketPal; Review screenshots; records PR035, PR038, PR039.
- Page 67: PocketPal; Review screenshots; records PR038, PR040, PR041.
- Page 68: PocketPal; Review screenshots; records PR040, PR042, PR043.
- Page 69: PocketPal; Review screenshots; records PR042, PR044, PR045.
- Page 70: PocketPal; Review screenshots; records PR046, PR047, PR048, PR049.
- Page 71: PocketPal; Review screenshots; records PR046, PR047, PR048.
- Page 72: PocketPal; Review screenshots; records PR050, PR051, PR052, PR053.
- Page 73: PocketPal; Review screenshots; records PR009, PR054, PR055, PR056.
- Page 74: PocketPal; Review screenshots; records PR019, PR054, PR057.
- Page 75: PocketPal; Review screenshots; records PR019, PR057, PR058.
- Page 76: PocketPal; Review screenshots; records PR028, PR059, PR060, PR061.
- Page 77: PocketPal; Review screenshots; records PR058, PR061, PR062, PR063.
- Page 78: PocketPal; Review screenshots; records PR064, PR065.
- Page 79: PocketPal; Review screenshots; records PR066, PR067, PR068.
- Page 80: PocketPal; Review screenshots; records PR042, PR043, PR069.
- Page 81: PocketPal; Review screenshots; records PR042, PR043, PR069.
- Page 82: PocketPal; Review screenshots; records PR064, PR068, PR070.
- Page 83: PocketPal; Review screenshots; records PR042, PR045, PR071.
- Page 84: PocketPal; Review screenshots; records PR071, PR072, PR073.
- Page 85: Beeper; Review screenshots; records BR076, BR077, BR078.
- Page 86: PocketPal; Store AI summary + reviews; records PR072.
- Page 87: Beeper; Review screenshots; records BR076, BR077, BR079.
- Page 88: Beeper; Review screenshots; records BR079, BR080, BR081.
- Page 89: Beeper; Review screenshots; records BR079, BR080, BR081.
- Page 90: Beeper; Review screenshots; records BR082, BR083, BR084.
- Page 91: Beeper; Review screenshots; records BR082, BR083, BR085, BR086.
- Page 92: Beeper; Review screenshots; records BR085, BR087, BR088.
- Page 93: Beeper; Review screenshots; records BR087, BR089, BR090.
- Page 94: Beeper; Review screenshots; records BR075, BR089, BR091.
- Page 95: Beeper; Review screenshots; records BR073, BR074, BR075.
- Page 96: Beeper; Review screenshots; records BR071, BR072, BR073.
- Page 97: Beeper; Review screenshots; records BR068, BR069, BR070.
- Page 98: Beeper; Review screenshots; records BR066, BR067.
- Page 99: Beeper; Review screenshots; records BR065, BR066.
- Page 100: Beeper; Review screenshots; records BR063, BR064.
- Page 101: Beeper; Review screenshots; records BR061, BR062, BR063.
- Page 102: Beeper; Review screenshots; records BR059, BR060.
- Page 103: Beeper; Review screenshots; records BR011, BR059.
- Page 104: Beeper; Review screenshots; records BR011, BR058.
