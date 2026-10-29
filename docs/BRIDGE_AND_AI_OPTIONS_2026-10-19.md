# MINI bridge hosting and local AI options

> **Decision after this comparison:** The user chose phone-local Android connections for WhatsApp, Facebook Messenger, Instagram and LinkedIn, using the existing WhatsApp architecture as the pattern. Earlier recommendations to use a local computer/server are retained as comparison history, not the selected implementation. No cloud bridge deployment is selected for these providers. This changes implementation direction, not their qualification status.

## Selected phone-local implementation sequence

1. Preserve the working WhatsApp host, its encrypted session snapshots and existing accounts. The current `native/whatsappRuntime` binds whatsmeow through gomobile; it does not embed the complete mautrix-whatsapp server.
2. Inspect and pin Messenger/Instagram and LinkedIn protocol packages, dependency licenses and Android build compatibility. The existing Go runtime must remain one coordinated Android artifact; do not independently add conflicting gomobile runtimes. A future rename to a shared bridge runtime must preserve existing generated APIs and stored data.
3. Add one provider at a time behind the existing `PhoneLocalBridge` route with real provider identity, encrypted per-account session storage and explicit login/challenge/reconnect/logout states. Unsupported or unbuilt clients remain unavailable; do not label Matrix-user login a provider session.
4. Feed received messages through canonical observations and sends through exact review/outbox. Prove receive, bounded history, provider acceptance, account isolation, process-death reconnect and revocation on Android before calling each connector live.
5. Retain the existing phone-local privacy gate. No remote Matrix plaintext route needs enabling to implement this decision. Qualify background delivery, battery and memory alongside both local AI configurations.

The WhatsApp dependency is MPL-2.0; the Meta/LinkedIn implementation reviewed is AGPL-covered. The decision to run locally does not settle combined-work licensing. Before distributing an APK containing those packages, establish compliant source distribution for the covered combination or an applicable alternative permission. This is a release-packaging requirement, not permission to change the user's selected privacy architecture.

19 October 2026. Decision support for the [unified roadmap](UNIFIED_DEMO_ROADMAP_2026-10-19.md). User requires all selected connectors live on Android; **iMessage is on hold**. Android Messages remains included; SMS/MMS versus RCS coverage is not yet specified. No hosting choice or deployment has been authorized by the comparison request.

## Bridge hosting

### Phone-local

Protocol sessions and message plaintext are processed on the Android phone. This best preserves the prior privacy contract and avoids an always-on external host. Existing phone-local WhatsApp work can inform packaging and encrypted session handling, but it does not qualify other networks or establish a working stock Matrix deployment on Android.

Stock server bridges are not Android SDKs. Running a homeserver plus the selected bridges locally, or extracting their protocol engines, creates substantial packaging, lifecycle, memory, background execution and upgrade work. Reusing protocol libraries is a different architecture from adopting complete Matrix bridge services. The existing single Go/JNI runtime constraint remains relevant. Feasibility must be demonstrated per provider.

For the all-connector deadline, this option carries the largest new Android integration burden. That is an engineering assessment based on current MINI code, not a measured prototype result.

### User-controlled self-hosted Matrix

The Android app is a Matrix client. A user-controlled computer/server runs a homeserver, persistent storage and separate provider bridge services. Gmail/Outlook/Teams still connect through official APIs; Android telephony stays a separate native route. Bridge login/session state, account ownership, encryption and health must be integrated into MINI.

This follows the upstream bridge deployment model more closely and reduces Android protocol packaging work. It adds operational dependencies: host availability, TLS, encrypted session storage, restart/recovery, upgrades and monitoring. The bridge process handles plaintext. With end-to-bridge encryption, the homeserver can store encrypted message events, but this does not remove bridge plaintext processing. See [upstream encryption documentation](https://docs.mau.fi/bridges/general/end-to-bridge-encryption.html) and [deployment prerequisites](https://docs.mau.fi/bridges/general/docker-setup.html?bridge=whatsapp).

**Recommendation for evaluation:** trial user-controlled self-hosting first for the all-connector demo, subject to the user's explicit hosting choice and available hardware. Keep AI and the canonical encrypted MINI store on Android. Do not change the runtime's existing phone-local policy until the chosen trust boundary is documented and implemented. The current plaintext-only Matrix adapter is not adequate for an encrypted deployment.

### Hybrid

A homeserver may run separately from bridges, with the bridges on a user-controlled local machine. This allows different operational/privacy boundaries, but still needs an available bridge host and a compatible encrypted Android Matrix client. Mixing MINI's existing WhatsApp phone-local route with Matrix routes for other networks could reuse work, but would diverge from the user's selected Matrix route for WhatsApp; it is an option to discuss, not a silent substitution.

### What must be demonstrated for every selected connector

- Official routes: Gmail, Outlook and Teams; Android Messages through a separately defined SMS/MMS or RCS route.
- Matrix routes: WhatsApp, Messenger, Instagram, LinkedIn and Slack. iMessage is held out of current acceptance.
- Real account login and verified provider identity; receive an actual new message; open correct conversation/history with honest gaps; compose/edit; exact review; send only after user approval and qualification evidence permits; report the actual acceptance level.
- Account isolation, duplicate handling and reconnect after app/bridge restart. An available bridge project does not establish working MINI integration.
- Track provider credentials/consent, bridge provisioning and Android integration independently so infrastructure blockers are visible early.

## Local AI experiment

The user is considering **EmbeddingGemma 2 text + Qwen3.5-0.8B**, or Qwen alone. This replaces the earlier interpretation of Gemma as a competing generative model. It is an experiment, not a selected production configuration.

[Google's EmbeddingGemma 2 model card](https://huggingface.co/google/embeddinggemma-2) documents a 270M text-only configuration with vision/audio disabled. It describes task-specific retrieval prefixes, compatible query/document vectors, optional truncated dimensions with normalization, and a warning against ordinary float16 inference. A desktop example does not establish Android backend/conversion support. Pin and verify the exact text-only artifact, runtime, numeric precision and tokenizer before claiming compatibility.

### Baseline A: rules + FTS5 + Qwen

Rules classify communication and apply authorization/attention policy. SQLCipher stores canonical observations and FTS5 supplies keyword results. Scoped recent context plus keyword candidates are packed within Qwen's input budget. Qwen proposes summaries, understanding and replies. Deterministic validators and the person retain control of priorities/actions.

This configuration has fewer model lifecycles and avoids vector indexing. It does not promise semantic search equivalent to an embedding model. Qwen is not used to embed or regenerate the entire mailbox on every search.

### Experiment B: rules + FTS5 + EmbeddingGemma + Qwen

Authorized changed messages are chunked and embedded in bounded background batches. At query time, retrieve independently scoped lexical and semantic candidates, merge/rank them, then pass a limited evidence bundle to Qwen. Qwen generates the answer/draft; embeddings do not draft messages or establish factual evidence by themselves.

Enforce scope before selecting candidates, not just after top-k ranking. Preserve account, message ID/revision and chunk identity with each vector. Keep vectors encrypted at rest; do not use an unencrypted side database. Deletion, edits, revocation and exclusions invalidate corresponding vectors. Changing model/tokenizer/prefix/dimension requires a new index version or rebuild. Generation must recheck evidence and consent after asynchronous retrieval.

Initially compare model scheduling that unloads or suspends embedding work during generation. Do not assume both models fit resident RAM merely because their parameter counts are small. Memory includes runtime state, token buffers, index and application storage. Pre-embedding should be incremental; thermal pressure pauses background work.

### Evaluation and promotion

Use the same synthetic corpus, accounts, generation model/settings and phone. Compare A and B on paraphrased search, relevant conversation retrieval, exact names/numbers, near-duplicate wording, contradictory messages, deleted/edited evidence and forbidden-account decoys. Measure Recall@5/MRR against human labels, final factual support, retrieval and total answer latency, index build/update cost, peak memory and cancellation/restart behavior. Keep cold and warm measurements separate.

Promote B only if it improves the application's retrieval on a held-out set without account leakage, stale evidence, unacceptable latency, crashes or background resource interference. Define quantitative thresholds before the held-out run. If it fails, keep A usable and mark semantic retrieval unavailable; do not silently replace it with generated guesses.

### Design consequences

E3 should show generator readiness and semantic-index readiness separately. Inbox/search/manual composition continue when either model is absent. Search identifies keyword versus hybrid behavior without promising exhaustive coverage. B4/D2 show the actual retrieved sources; unindexed history is a coverage limit. No model receives a sending permission or clears a release gate.

## Open inputs

Android phone model/RAM, available always-on host, final bridge hosting choice, Android Messages SMS/MMS versus RCS, exact converted artifacts/quantization and per-provider account availability remain unknown. iMessage is explicitly on hold and does not block the current scope. No model benchmark, bridge provisioning or deployment was performed for this comparison.

## Follow-up: three upstream bridges, licensing and privacy

Read on 19 October 2026: each project's root LICENSE, LICENSE.exceptions, README, Go dependency manifest and feature roadmap; upstream setup, encryption and provider authentication docs; selected LinkedIn and Meta client source. This is a targeted licensing/architecture review, not an audit of all code or all transitive dependencies. Upstream main is mutable; implementation must pin commits and inventory the selected artifacts.

### Licensing findings

All three root licenses are GNU AGPL version 3: [WhatsApp](https://github.com/mautrix/whatsapp/blob/main/LICENSE), [Meta](https://github.com/mautrix/meta/blob/main/LICENSE), [LinkedIn](https://github.com/mautrix/linkedin/blob/main/LICENSE). Commercial use is allowed. Preserve notices/licenses; distributed covered binaries require corresponding source through a compliant mechanism. Modified versions used through a network must prominently offer corresponding source to remote users under section 13. Source obligations concern covered software, not users' communications or credentials.

Each project also has a material [LICENSE.exceptions file](https://github.com/mautrix/whatsapp/blob/main/LICENSE.exceptions), with matching [Meta](https://github.com/mautrix/meta/blob/main/LICENSE.exceptions) and [LinkedIn](https://github.com/mautrix/linkedin/blob/main/LICENSE.exceptions) files. These name Beeper and Element, with particular permissions and conditions; MINI is not named. Do not treat the existence of a proprietary Beeper client as permission to embed the same bridge in a closed MINI binary.

Separately deploying a bridge and having an independently written client communicate through a standard protocol does not automatically place every client/server under the bridge's license. Conversely, embedding/copying/linking AGPL implementation into MINI can create combined-work obligations. A process boundary alone does not settle the legal question. Evaluate the actual integration/distribution with licensing counsel before a proprietary release, or obtain an applicable exception from authorized rights holders. This review found no general MINI exception or published commercial entitlement.

WhatsApp's protocol dependency [whatsmeow](https://github.com/tulir/whatsmeow/blob/main/LICENSE) has a different MPL-2.0 license. That supports a separate library-based architecture, with its own source/notice obligations; it does not relicense the AGPL Matrix bridge. Meta/LinkedIn packages inside their repositories must not be assumed to share whatsmeow's license.

### Provider-specific behavior and experience

- **WhatsApp:** upstream supports QR and phone-code pairing, with possible additional verification. It is a linked web-device route; reconnect and primary-phone inactivity matter. Use a phone-code flow where scanning a QR on the same Android device is awkward. Upstream describes configurable bounded history, not a guarantee of the entire mailbox. [Authentication](https://docs.mau.fi/bridges/go/whatsapp/authentication.html).
- **Messenger/Instagram:** the Meta repository contains separate bridge binaries. Auth supports provider/version-specific cookie or credential flows; security challenges can interrupt setup. MINI needs a supported login integration and explicit recovery, rather than exposing developer-tools/cookie-copy instructions to ordinary users. [README](https://github.com/mautrix/meta), [authentication](https://docs.mau.fi/bridges/go/meta/authentication.html).
- **LinkedIn:** documented auth uses browser session cookies, with a manager recommended and browser identity compatibility considerations. Treat cookies as account-access secrets. The [feature roadmap](https://github.com/mautrix/linkedin/blob/main/ROADMAP.md) leaves outgoing reply relationships and voice messages unchecked; basic send support does not imply those features. [Authentication](https://docs.mau.fi/bridges/go/linkedin/authentication.html).

These are unofficial user-session bridges, not proof of provider authorization for every product use. Protocol changes, session expiry and provider challenges affect reliability. Do not promise zero relinking, exhaustive history, full source-app feature parity or provider-approved status.

### What each deployment actually protects

**On the Android phone:** the device is the bridge plaintext endpoint. MINI's cloud can remain out of the message path. Existing upstream deployment instructions target servers, so Android adaptation is engineering work, not a supported drop-in APK. Measure battery/background delivery and all native runtime costs before promising the same experience as an always-on server.

**On the user's own computer/home server:** the bridge sees plaintext there, but MINI's cloud need not. A cloud homeserver can relay/store encrypted events if end-to-bridge encryption and the Android client are implemented correctly. The user-owned machine must stay available; if it sleeps, new bridged messages wait and history recovery depends on provider support. A user-owned VPS still adds its infrastructure operator to the threat model.

**On MINI's cloud:** the managed bridge holds provider sessions and sees live message plaintext. TLS and disk encryption reduce specific risks but do not exclude the running service/operator from plaintext. Per-user isolation also does not make the service unable to read messages. Keep the product claim accurate: private managed processing with disclosed trust, rather than phone-only processing or operator-inaccessible content.

**Cloud relay with a user-side bridge:** useful privacy-preserving split. Message translation stays at a user-controlled endpoint; the cloud receives encrypted Matrix events. Homeserver metadata, message timing, routing and account/network information still need minimization. This preserves cloud exclusion from content under the intended cryptographic design, but does not remove user-side availability and setup costs. [Upstream encryption explanation](https://docs.mau.fi/bridges/general/end-to-bridge-encryption.html).

Confidential computing could be separately researched for managed bridges; ordinary containers or per-user VMs do not provide that guarantee. It would need attestation, protected key release and lifecycle/update analysis. It is not a verified deadline solution.

### Concrete implementation proposal for MINI

1. Keep SQLCipher canonical storage, Qwen, optional embeddings and exact-review decisions on Android. Add a Matrix client with actual encrypted-room/device-key support; do not enable the current plaintext-only foundation adapter as if it met that requirement.
2. Package a version-pinned bridge stack with a compatible homeserver, separate databases/service identities and separate WhatsApp/Messenger/Instagram/LinkedIn services. Upstream's current [setup guide](https://docs.mau.fi/bridges/go/setup.html) requires appservice-capable hosting and registration; it is more than running one bridge container.
3. For the strongest privacy mode, locate bridges on the phone when feasible or on a user-controlled local host. Put only encrypted event transport/storage in the cloud. Prefer restricted, non-federated demo infrastructure and explicit device verification; validate the actual server settings rather than assuming defaults.
4. If an optional managed-cloud mode is selected, disclose the bridge processing location before connecting; isolate users, encrypt session databases/backups, restrict key/admin access, strip message/credential/contact content from logs, minimize media caches and retention, and implement provider logout plus service-side deletion. Do not claim minimal retention until storage paths have been measured.
5. Encrypt both message and media transport; keep notification payloads free of message content by default. Test key loss, expired sessions, host outage, encryption mismatch, stale room ownership, media expiry and disconnect. Debug logs are not inherently safe: WhatsApp's upstream docs warn they may contain phone numbers.
6. Build ordinary in-app connection/recovery screens over supported provisioning/auth flows. Separate Matrix transport health from provider-session health and history coverage. Exact review binds the actual provider account, conversation, route and payload; report Matrix receipt separately from provider confirmation.
7. Preserve AGPL source/notice obligations for bridge components and review the independent-client boundary. Do not copy AGPL provider packages into a proprietary APK while assuming their server placement changes the license.

Recommendation: use a user-controlled local bridge host plus encrypted Matrix transport for the privacy-focused demo if available. Offer managed cloud only as a separately disclosed processing choice. Phone-only is the preferred research direction for removing external-host setup, but all-network Android packaging is not established by these upstream repositories. The user has not selected or authorized deployment of any topology yet.
