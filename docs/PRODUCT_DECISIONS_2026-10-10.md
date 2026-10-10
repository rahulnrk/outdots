# MINI product decisions: local intelligence and connector hosting

Updated: 10 October 2026, Asia/Kolkata.

Sources: [initial product discussion](https://chatgpt.com/share/6aca269c-97d0-83e8-8024-df61549c71d3), [updated history and personalization discussion](https://chatgpt.com/share/6aca2980-3ed0-83ee-a1b9-0432de951892), read in the browser, and the connector choices made in this chat. The updated discussion and the user's latest correction supersede the earlier attachment-size and selected-message retention rules. Only explicit user selections are recorded as agreed. Assistant suggestions and unresolved questions are not approvals. This document records product intent; it does not establish implemented or live-qualified functionality.

## Agreed behavior

- Present a unified inbox alongside an AI dashboard.
- Classify accessible communications by purpose, urgency, deadline and needed action. Combine prompt classification with adaptive batching.
- Suggest actions; users control reminders and commitments.
- Generate or improve replies when requested; sending requires explicit review and authorization.
- Import available account history for browsing. Historical access varies by provider; incomplete history must remain visible as incomplete.
- Classify accessible new messages automatically. Build local knowledge of the user's professional and casual writing patterns from available authorized conversation history, without requiring contact-by-contact selection for writing-style learning. This supersedes the earlier selected-conversations restriction for style learning.
- Learn the user's style from their own authored messages, and use previous exchanges to understand the relationship and communication context. Adapt requested replies to the recipient, relationship, topic and current conversation, including changes between professional and casual tone with the same person. Refine this knowledge progressively from new messages and user edits; distinguish the user's writing from other participants' writing and unaccepted AI drafts.
- Use relevant history and relationship context for requested drafts, with editing options. Keep browsable history separate from compact AI context; both remain encrypted on the phone.
- Remove the former attachment-size rule and selected-message retention formula from agreed requirements. Their replacement policies are not finalized.
- Learn locally from user category corrections. No large-model retraining is assumed.
- Keep AI processing on the device; cloud-task delegation raises trust concerns and has not been authorized.
- Allow a notification relay only if it cannot read messages or AI data. This approval does not authorize a remote bridge to process readable messages.
- All requested providers remain in product scope; simultaneous launch is not an agreed requirement.

## Agreed connector direction

Use bridges for LinkedIn, WhatsApp Personal, WhatsApp Business mobile app and Instagram. Use official APIs for other providers where they support the intended account. Unsupported personal-inbox access remains explicitly limited. Telegram Bot access is not a personal Telegram inbox. WhatsApp Business mobile app is distinct from the Business Platform Cloud API.

The desired bridge location has not been agreed. Choosing a bridge did not by itself authorize server-side plaintext message processing. Resolve this against the local-data requirement before treating the current remote Matrix path as the default product architecture.

## Recommendations awaiting agreement

1. Implement the approved unreadable notification relay boundary while keeping AI and personal storage local. Decide separately whether a user-controlled remote bridge mode is allowed.
2. Prefer on-device bridge execution for the default privacy direction, subject to Android packaging, encryption, memory, battery and session qualification. A standard Matrix client does not prove on-device bridge execution.
3. Treat raw homeserver/token entry as an advanced setup option. Normal account connection should guide users through supported provider authorization/device linking and show the actual linked provider account.
4. Perform a lightweight initial classification, then defer heavier understanding according to resource availability. Preserve user corrections and show stale/uncertain classification. Do not promise continuous heavy background inference.
5. Preserve evidence for relationship inferences, make limited context apparent, and let users control learned profiles. Cross-platform identity linking should require user confirmation; that suggestion remains unapproved.
6. Define attachment processing and history/cache lifecycle separately. Do not reintroduce the superseded size rule or storage formula as requirements.

## Current implementation gaps observed

Source inspected: `native/shared/src/commonMain/kotlin/app/mini/hub/connector/MatrixBridgeConnector.kt` and its registry/catalog wiring. These are static code findings, not reproduced live failures.

- Matrix `whoami` identifies a Matrix user, not a logged-in LinkedIn/Instagram/WhatsApp account or a running provider bridge. The current token entry therefore proves homeserver access only.
- Sync iterates all joined rooms without selecting the bridge's network/account. Reusing a Matrix account across connector entries could misattribute rooms to multiple providers. Room/provider account binding is needed.
- The filter accepts only `m.room.message`, with no encrypted-event decryption path. The current adapter cannot supply encrypted-room message reading. The blanket documentation claim that Matrix always stores plaintext is inaccurate: this is the current implementation's mode, not an inherent Matrix limit.
- A random suffix is added to the outbox command's Matrix transaction ID. Retries cannot rely on a stable transaction ID for idempotency until this is corrected.
- A returned Matrix event ID establishes homeserver acceptance. Provider-bridge forwarding success must be distinguished before reporting successful network sending.
- Limited timelines/history continuation and provider edits/deletions need qualification before claiming complete history. In the current parser, an empty redacted content object is skipped by the message-type check before the deletion flag is evaluated.
- `docs/CONNECTORS.md` still contains notification-only rows and a prohibition on unofficial session bridges that conflict with the newer catalog and user-selected bridge direction. Reconcile those descriptions after the hosting decision.

References: [Matrix Client-Server specification](https://spec.matrix.org/latest/client-server-api/), [mautrix encryption boundary](https://docs.mau.fi/bridges/general/end-to-bridge-encryption.html). End-to-bridge encryption can conceal message contents from a homeserver; the bridge still processes plaintext at its execution location. It does not make a remote bridge phone-local.

## Still open

- On-device-only bridges versus an optional disclosed server mode.
- Android-first versus simultaneous Android/iOS launch. The shared conversation questioned notification-only acquisition; it did not select a launch option.
- Models, number of models, execution backend, residency and hardware selection. These remain brainstorm topics, not finalized implementation requirements.
- Resource budgets, attachment processing, history/cache lifecycle and live performance targets.
- Whether to link identities and combine permitted context across providers. The updated discussion proposes this but contains no user approval.

## Proposed next step

Agree on the bridge/privacy boundary first. Then give Jcode the agreed product behavior and let it propose how to close the connector correctness gaps and implement the local intelligence harness. Keep the current bridge adapter classified as a foundation needing qualification, rather than completed provider-session integration. No app behavior or server deployment was changed by this decision record.
