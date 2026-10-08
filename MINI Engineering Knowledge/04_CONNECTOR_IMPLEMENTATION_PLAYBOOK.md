# MINI — Connector Engineering Playbook

**Purpose:** translate the API inventory in `03_CONNECTIONS_AND_API_ENTRY_POINTS.md` into a reliable, least-privilege, auditable implementation. This is a proposed design. Provider-specific details must be verified before shipping.

## 1. One connector interface, multiple truthful capability types

Separate **ingest**, **outgoing**, **authorization**, and **coverage**. Don't create a giant generic `readEverything()` API.

```text
ConnectorIdentity: provider, provider_account_id, account_type, user_local_id,
                   display_name, auth_method, last_permission_check

ConnectorCapabilities: read_mode, write_mode, history_backfill,
                       outgoing_visibility, realtime_mode, policy_windows,
                       attachment_types, receipts, platform_limits,
                       granted_scopes, verified_at, approval_state

ConnectorEvent: event_id, source_provider, source_account_id, source_event_id,
                conversation_id, provider_message_id?, author_id?, direction?,
                provider_timestamp?, observed_timestamp, version?, type,
                payload_or_pointer, content_coverage, provenance, tombstone

ConnectorHealth: authorized, latest_success_at, latest_event_at,
                 sync_cursor, lag, permission_errors, rate_limit_reset_at,
                 gaps, last_known_policy, user_action_required

OutgoingCommand: command_id, authorized_sender, approved_recipient,
                 draft_hash, content_hash, source_versions,
                 permission_snapshot, route, approval_timestamp, expires_at

SendOutcome: NEVER_SENT | HANDOFF_OPENED | ATTEMPTED |
             PROVIDER_ACCEPTED | DELIVERED | READ |
             REJECTED | OUTCOME_UNKNOWN
```

Any provider adapter must declare whether source events are **authoritative**, **partial notification observations**, or **user-imported snapshots**. Missing IDs/timestamps/edits are not magically reconstructed. Link events across accounts only after explicit identity confirmation or safe, reversible matching.

## 2. Authentication and permission design

**OAuth mobile:** system browser + Authorization Code with PKCE (where supported); exact redirect/deep link/app association; state + nonce where appropriate; short-lived access token and refresh token only with appropriate scope. Android Keystore and iOS Keychain store sensitive key material. Don't embed client secrets in a mobile binary. Prefer incremental consent: read first, send later; do not request scopes unused by shipped functions.

**Business onboarding:** organization/Page/phone/tenant ownership must be checked. Meta may use business integration tokens, Page access tokens, WABA phone IDs and app review; Microsoft tenants may require admin consent; Slack requires workspace app installation. Never treat a successful OAuth sign-in as authorization for every API.

**Revocation:** implement immediate UI disable, cancel queued work, purge cached API tokens and associated local data according to user's choice. Recheck permissions on provider 401/403, OS policy change, app resume and prior to send. Permission scopes belong in the account manifest, not global state.

**Login state machine:** `unconfigured -> user_consent_pending -> authorized_limited | authorized_full -> syncing -> healthy | degraded | expired | revoked | suspended`. Make 'connected but limited' visible. Don't automatically downgrade to unofficial access when OAuth fails.

## 3. Two integration topologies

### A. Phone-local direct API integration (preferred where feasible)

Phone holds credentials in Keychain/Keystore and uses official provider endpoints over TLS. Sync cursors and records remain encrypted on device. Works especially for direct user-initiated or periodic email API fetching, subject to mobile background limitations. Cheapest trust model when allowed.

### B. Minimal webhook relay (required for some business APIs)

Public webhook endpoint validates HMAC/signature/verification token, rejects replays and oversized bodies, performs minimum necessary processing, securely queues **opaque event hints** when provider supports it, and signals device to fetch authoritative content using the user's tokens. Some providers push content directly and may require a trusted short-lived processing step; disclose this honestly. Prefer no server-side persistent conversation database, encrypt in transit/at rest where unavoidable, define deletion and short retention, and measure relay availability/cost. An ordinary cloud webhook is **not** phone-local by itself; don't promise 100% on-device processing if message content traverses a relay.

**Required relay questions:** Does provider let us receive only IDs? Is a publicly reachable HTTPS callback mandatory? Does provider require a long-lived Page/business token server-side? What is the minimum data retained? How do we resume if user phone offline for 5 days? What is our plan for 99.9% delivery, retries, webhook duplication and token revocation? No answers => do not mark approved.

## 4. Reliable inbound sync

```text
Receive event → signature/auth check → bounded queue → durable raw envelope (encrypted)
→ provider-specific normalization → idempotency/dedupe → version/time handling
→ conversation linkage → coverage update → local indexing → UI state
→ optional inference task (lowest priority)
```

**Idempotency key:** use provider + account + message ID + event/version when available. For Android notifications use notification key + observed update revision and other stable metadata; *never* pretend a notification text hash uniquely identifies a message. Same content twice can be two legitimate messages. Persist **before ack** for server webhooks where protocol permits; if a provider retries, protect against reprocessing.

**Ordering:** use event-time and arrival-time separately; do not use clock timestamps as strict ordering without sequence/version. Handle edit, delete, reaction and group-summary events, tombstones and stale replays. Track `history_cursor`, `gap_start`, `gap_end` and `backfill_supported` per account.

**Sync:** initial authorized backfill (bounded by policy, disk limit and retention), incremental cursor, missed-webhook recovery, periodic health checks and a user-visible `last_synced`/coverage status. Exponential backoff with jitter for 429/5xx and respect `Retry-After`. Never retry a send blindly if provider acceptance might already have happened.

**Coverage classes:** `complete-within-provider-permission`, `partial-API`, `notification-only`, `imported-snapshot`, `not-connected`, `permission-expired`, `known-gap`. MINI's UI should display relevant warnings inline before presenting evidence-backed answers.

## 5. Reliable outbound pipeline

The existing action/outbox contracts remain authoritative. Recommended sequence:

1. Create a **draft** from grounded evidence; store source version IDs and exact recipient/account/channel.
2. Show recipient, channel, message, attachments, price/date/commitment claims and policy eligibility at review time.
3. **User approves exact digest**; approval cannot be reused if recipient/body/attachments/source facts change.
4. Recheck scope/token, policy window, recipient, route and current source versions at dispatch.
5. Persist a dispatch lock and attempt status **before** network call; associate provider-supported idempotency key when available.
6. Map provider response to **provider-accepted**, not 'delivered' unless provider genuinely attests to delivery; handoff merely means a native app was opened.
7. If timeout or connection drop makes send status ambiguous, mark **OUTCOME_UNKNOWN** and reconcile using provider message IDs/history when API supports it. **Never blindly resend**.
8. Any new attempt requires safe reconciliation or renewed user approval; preserve audit trail and cancellation handling.

**Policy evaluator:** separate per-account capabilities from per-conversation eligibility. Examples: user-initiated vs business-initiated Meta window; permitted templates/tag; role restrictions for Slack/Teams; send-as identity for email. Time windows are determined by qualifying provider events, not a guessed timer from the last observed notification.

## 6. Platform-specific design limits

Android NotificationListener: user explicitly enables permission; OS can suppress/trim/redact notifications; no retrospective complete history, sending or durable provider acknowledgement by default. Work on callbacks must be short and exception-safe. Maintain non-blocking queue + crash-resistant encrypted state; test multiple OEM battery-optimization policies.

iOS: no general public third-party notification listener; only supported provider/user import/share/compose methods. iOS process may be suspended or killed; sync/draft progress must be resumable. Do not assert same notification coverage on Android and iOS.

## 7. Privacy and regulated-data minimization

- Explicit per-account consent, purpose and retention; do not assume 'training' consent from connector access. Users can disable style learning independently of message sync.
- Full local-database encryption, key storage, scoped exports, sensitive-data-free logs and crash analytics; ensure notifications on lock screen do not leak reconstructed private content.
- Separate message author identity from contact display name. No demographic, health, financial or relationship inference except what is needed for a user-configured action.
- Provider API user-data policies govern **derived** data too; check restrictions before retaining text, embeddings, summaries or style examples. Particularly important for Google restricted scopes, LinkedIn member data and Microsoft organizational data.
- Implement deletion propagation: remove original, indexes, embeddings, cached summaries, exemplars, profiles and queued tasks; checkpoint backups and retention windows must also be considered.
- If the user revokes connector access, disable future fetch/sends immediately; offer data deletion. Local content should not remain indefinitely by accident.

## 8. Test matrix for each connector

| Test | Expected evidence |
|---|---|
| Valid OAuth scope and expired token | Graceful renewal or explicit disconnect; no stuck sync |
| Wrong account/tenant/recipient | Correctly rejected, no cross-account access |
| Webhook replay/bad signature | Reject + no duplicate event |
| Partial/out-of-order events | Idempotent merged state with provenance |
| Offline for extended period | Cursor-based recovery or visible unrecoverable gap |
| Permission change mid-draft | User review invalidated, no send |
| 429/503/timeout | Respects Retry-After; correct backoff |
| Timeout after possible provider acceptance | `OUTCOME_UNKNOWN`; no automatic duplicate |
| Long-running thread and edits | Relevant evidence, changed facts supersede stale context |
| User deletes account/profile | Purge all associated derived indexes, caches and saved tokens |
| App process kill while ingesting | Durable queue replay once, no message corruption |
| No authorized endpoint | Clear blocked/limited UI, safe handoff only |

## 9. Release gating worksheet (complete one per connection)

```markdown
### Connector: ______ / account type: ______ / app id: ______
- [ ] Latest official API contract linked, Graph/API version pinned
- [ ] Provider eligibility verified (not just an example code snippet)
- [ ] Exact OAuth scopes permitted & obtained, privacy review complete
- [ ] User transparency, consent, revoke and deletion tested
- [ ] Real authorized read path tested, coverage documented
- [ ] Backfill + incremental sync and gap recovery tested
- [ ] Independent send approval + receipts / unknown outcome tested
- [ ] Webhooks signature and replay defense tested, if applicable
- [ ] Android platform test passed on physical phone
- [ ] iOS route independently tested (not assumed from Android)
- [ ] Error states, rate limits, pricing and policy windows surfaced to user
- [ ] Production publishing/API provider review complete
```

## 10. MVP advice

For the first **real user pilot**, do not build ten connectors in parallel. Select one reliable authoritative source (email, if viable) plus Android notification observation, share/import and manual handoff. Test one end-to-end use case: notification → priority → retrieve limited context → natural approved draft → user edits → learns their style → safe action/receipt where official API exists. Everything else remains behind a capability manifest and honest 'not yet available' UI.
