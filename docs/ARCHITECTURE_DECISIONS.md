# Architecture decisions

Updated 2026-10-08. Decisions are scoped to this repository and preserve the architecture document's privacy contract.

## ADR-001: Kotlin Multiplatform is the product core

**Decision:** The native product core is KMP with Android Compose and a future SwiftUI edge. The existing TypeScript
package remains a browser reference harness and executable safety specification while KMP parity is built.

**Why:** The architecture calls for shared deterministic rules plus native authorization, storage, lifecycle and UI.
A browser demo cannot prove Android notification permissions, Keystore, WorkManager or iOS behavior. The Android store now has a SQLCipher and Keystore implementation, but device qualification remains a separate acceptance step.

**Consequence:** The TypeScript and KMP cores need parity tests before either is called release-ready. The current
KMP scaffold builds JVM tests and an Android debug APK. Android SQLCipher persistence is implemented behind the shared store boundary, while native connectors and device qualification remain outstanding.

## ADR-002: Injectable clocks for business rules

**Decision:** All approval, expiry and dispatch paths accept an explicit time. The web app defaults to an ISO wall clock;
KMP uses an injectable `Clock`. Synthetic fixture timestamps remain test data only.

**Why:** Fixed demo time made expiry and route rechecks inert, and clock rollback must fail closed.

**Consequence:** Tests can deterministically cover expiry, clock skew and window transitions.

## ADR-003: Exact approval digest and one-use action

**Decision:** Approval binds a SHA-256 digest over account, conversation, route, sorted recipients, subject, body,
attachments and hashes, evidence revisions, grants and policy versions. Dispatch rechecks every binding and consumes
approval before any gateway call. Ambiguous results become outcome unknown and are never blindly retried.

**Why:** A UI confirmation that is not bound to the exact payload is a confused-deputy and duplicate-send risk.

**Consequence:** Durable storage must transactionally persist the consumed approval and dispatching state. The current
TS implementation has a process-local lock; Android now persists the native action state transactionally in SQLCipher, pending device restart qualification.

## ADR-004: Provider outcomes are separate states

**Decision:** Provider acceptance, delivery, read, native handoff, external handling and unknown outcome are distinct.
A native handoff has no provider receipt. The browser fixture is visibly simulated.

**Why:** Claiming delivery from an app launch or a mocked response violates the product contract.

## ADR-005: Publisher cannot receive private content

**Decision:** Publisher endpoints are limited to release metadata and strictly sanitized aggregate diagnostics. No
inbox, prompt, message body, memory or cloud inference endpoint is permitted. The manifest is explicitly unsigned and
has no model package until signing and artifact verification exist.

**Why:** A publisher webhook or diagnostic sink receiving plaintext would violate the local-only boundary.

## ADR-006: Local intelligence is a proposal subsystem

**Decision:** `InferenceEngine` and `ResourceSupervisor` expose bounded, evidence-backed proposals. Model output cannot
approve, mutate trusted state or execute a command. Compartment and evidence validation happens outside the model.

**Why:** Prompt injection and hallucinated commitments must not cross into action authorization.

## Rejected shortcuts

- Treating Android notification observations as complete WhatsApp history.
- Returning fake provider receipts to make a demo look complete.
- Adding a publisher-hosted conversation database or cloud model.
- Replacing KMP/native requirements with a browser-only claim.
