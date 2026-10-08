# Implementation status

Factual status per feature. Legend: **Implemented+tested**, **Partial**, **Simulated** (fixture/demo only),
**Blocked** (external dependency), **Not started**. Updated by the coordinator only after inspecting test output.

Last verified: 2026-10-08 at commit 0aeb2f3.

| Feature | Requirement (arch v1.1) | Status | Evidence | Next work |
|---|---|---|---|---|
| Shared domain rules (TS reference) | s6, s7, s9 | Implemented+tested (TypeScript only) | `npm test`: 13 passing in packages/domain | Port to KMP (B2) |
| Shared domain core (Kotlin Multiplatform) | s3 | Not started | none | B1, B2 |
| Approval bound to payload, account, recipients, route, source/grant/policy versions, expiry | s9 | Partial (TS) | domain tests for payload drift and grant drift (74fcb1b) | Attachments binding, KMP port, Agent A audit |
| Injectable clock, no fixed production timestamps | brief Stage D | Implemented in web hub (e8ea976). Sample data still has fixed synthetic dates | App.tsx uses `clock()` | Synthetic scenario generator relative to clock |
| Durable outbox with restart recovery | s9 | Not started (in-memory only) | none | B4 |
| Encrypted local store (SQLCipher + Keystore) | s11 | Not started | none | B3 |
| FTS5 scoped retrieval | s3, s6 | Not started (TS retrieval is in-memory filtering) | none | B3 |
| Gmail connector | s4 | Simulated (fixture gateway in tests) | domain tests | Adapter contract with fixtures. Live use **Blocked**: OAuth client + consent |
| WhatsApp notification observation | s4, s5 | Simulated (seeded observation) | sample data | Android NotificationListenerService (B) |
| Native handoff | s9 | Simulated (state only, no intent launched) | domain tests | Android intent (B) |
| Local inference engine | s8, s20 | Not started (draft text is a deterministic template, not a model) | drafting.ts | C1 |
| Resource Supervisor | s20.4 | Not started | none | C1 |
| Android app (Compose) | s3 | Not started | none | B1, D2 |
| iOS app (SwiftUI) | s3 | **Blocked**: no macOS/Xcode in this environment | none | Shared core only |
| Outlook, Teams, Meta, LinkedIn adapters | s4 | Not started | none | Agent A capability matrix first |
| Publisher service (no private content) | s2, s13 | Partial skeleton | packages/publisher/src/server.ts | Agent A audit |
| Web hub UI | brief Stage E | Partial reference harness | `npm run build` | Agent D |
