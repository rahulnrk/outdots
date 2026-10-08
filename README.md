# Private AI Communication Hub

This repository turns the supplied architecture document into a buildable product foundation.

## What is built now

The current implementation is an end-to-end local-first MVP slice that proves the core product mechanics before native Android/iOS work:

- A shared domain core for accounts, connector capability snapshots, evidence, workflow, priority, reply eligibility, drafts, approvals and outbox commands.
- A React web hub that demonstrates the intended first slice: Gmail plus WhatsApp notification evidence in one inbox, visibly different coverage, scoped local context, user decision capture, draft generation, exact review, approval and route-specific execution.
- A tiny publisher service skeleton for release manifests and entitlement checks. It intentionally has no private inbox, prompt or memory endpoint.
- Tests for the critical safety rules: user priority override, reply-window blocking, approval digest stability, send-time payload invalidation and no blind duplicate send.

## Run locally

```bash
npm install
npm test
npm run build
npm run dev
```

Then open the Vite URL printed by `npm run dev`.

## Important product boundary

This is not yet a native Android/iOS app and it does not connect to live Gmail, WhatsApp or local LLM runtimes. That is deliberate for this first code drop because the folder originally contained only an architecture document. The repo now isolates the durable product rules in a testable domain package so native KMP/Android/iOS adapters can be added without rewriting workflow correctness.

## Near-term native path

1. Port the pure domain types/reducers to Kotlin Multiplatform or generate equivalent contracts from the TypeScript model.
2. Replace demo adapters with native Gmail OAuth/sync and Android notification observation adapters.
3. Replace deterministic drafting with the local inference engine behind the `DraftingEngine` boundary.
4. Replace browser local state with SQLCipher-backed SQLite and platform key storage.
5. Preserve the same exact-review, approval digest and outbox state tests as release gates.
