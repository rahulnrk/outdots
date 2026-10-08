# Security review

Updated 2026-10-08 after commits `d99858b` and `b67fc36`. This is an engineering review of the
synthetic/local implementation, not a production certification.

## Trust boundaries

1. Provider and notification adapters are untrusted inputs. Messages and notification fragments are evidence,
   not instructions to the model or authorization to act.
2. `packages/domain` is trusted decision logic. It has no network or credential access.
3. The React hub is a presentation layer over in-memory synthetic state. It is not a vault.
4. `packages/publisher` is outside the device boundary. It exposes release metadata and sanitized aggregate
diagnostics only. It does not accept inbox bodies, prompts or memory.
5. The Kotlin intelligence layer treats model output as an untrusted proposal. It cannot approve or execute actions.

## Findings and mitigations

| ID | Threat | Mitigation | Status |
|---|---|---|---|
| SR-01 | Concurrent dispatch can duplicate a send | Per-command in-process dispatch lock; approval consumed and command enters dispatching before gateway call; interrupted dispatch recovers to outcome unknown | Fixed and tested in `security.test.ts`; durable DB lock still open |
| SR-02 | Invalid or rolled-back clock bypasses expiry | Invalid clocks fail closed; dispatch rejects time earlier than approval | Fixed and tested |
| SR-03 | Incoming/edit/deletion changes reviewed source | Approval stores source versions; send rechecks source versions, digest and draft status | Fixed and tested |
| SR-04 | Approval replay, recipient or attachment drift | Cryptographic SHA-256 digest binds account, route, recipients, body, attachments, evidence, policy and grant versions; IDs are unique | Fixed and tested |
| SR-05 | Fake provider acceptance or native-send claim | Empty receipts become outcome unknown; thrown gateway calls become unknown; handoff requires an injected launcher and never creates a receipt | Fixed and tested |
| SR-06 | Permission revocation or wrong provider policy | Route capability, connector qualification, send grant, provider window and policy are rechecked immediately before dispatch | Fixed and tested |
| SR-07 | Cross-account or cross-compartment retrieval | Conversation account filters messages/observations; memory is compartment scoped and evidence tombstones invalidate facts; omissions are surfaced | Fixed and tested |
| SR-08 | Prompt injection in sender/message text or fabricated commitments | Greeting sanitization, account-owned context checks and mandatory user decisions for price/date; model contracts only return evidence-backed proposals | Fixed in TS reference; full local model evaluation open |
| SR-09 | Same-millisecond priority override collision | Override IDs include a sequence and selection uses insertion order as tie-breaker | Fixed and tested |
| PUB-01 | Fabricated entitlement | Publisher returns unavailable when no billing authority is configured | Fixed and tested |
| PUB-02/03 | Private content smuggling or oversized diagnostic body | Strict schemas, safe error-code/counter grammar, JSON content type and byte limit | Fixed and tested |

## Open risks

- The web hub state is memory-only and does not survive restart.
- The current dispatch lock is process-local. Durable transactional outbox and recovery require the native store.
- SQLCipher, Android Keystore, OAuth token storage, notification permissions and real provider adapters are not implemented.
- The deterministic intelligence backend is only a contract/test double. It is not a qualified local model.
- No native device, low-memory, thermal, accessibility or malicious-app test has run.
- The publisher has no billing authority or signed manifest verification and correctly does not claim one.

No private content, credentials or real provider account was used in this review.
