# Implementation status

Factual status per feature. Legend: **Implemented+tested**, **Partial**, **Simulated** (fixture/demo only),
**Blocked** (external dependency), **Not started**. Updated 2026-10-08 after independent verification.

| Feature | Requirement (arch v1.1) | Status | Evidence | Next work |
|---|---|---|---|---|
| Shared domain rules (TS reference) | s6, s7, s9 | Implemented+tested | `npm test`: 59 tests including 33 adversarial security tests | Keep parity with KMP |
| Shared domain core (Kotlin Multiplatform) | s3 | Implemented+tested core | KMP scaffold, scoped IDs, injectable UTC clock, action core, observation models, and pipeline; 29 JVM tests pass | Port full conversation/workflow model |
| Approval exact binding | s9 | Implemented+tested in TS and native contract | TS SHA-256 and native exact payload contract bind account, route, recipients, body, attachments, evidence, grants/policy and expiry | Platform SHA-256 adapter and durable integration |
| Injectable clock | Stage D | Implemented+tested | Web `clock()` and KMP `Clock`; invalid/rollback tests | Use in all native business rules |
| Durable outbox with restart recovery | s9 | Partial | Native state machine plus Android SQLCipher transaction implementation and interrupted recovery contract. Device restart qualification is pending | Wire native lifecycle and qualify restart behavior on device |
| Encrypted local store (SQLCipher + Keystore) | s11 | Partial+compiled | Android `SqlCipherActionStore` and `SqlCipherObservationStore` use SQLCipher, random DB keys, and AES-GCM Keystore envelopes. In-memory stores pass JVM tests; debug APK packages SQLCipher ABIs. Runtime qualification is blocked on an available Android device/runtime | Emulator/device migration, key invalidation and restart tests |
| FTS5 scoped retrieval | s3, s6 | Partial | TS in-memory retrieval filters account/compartment; SQLCipher probe checks dynamic FTS5 availability | Encrypted SQLite FTS5 integration |
| Gmail connector | s4 | Simulated | Local fixture provider only | OAuth, cursor sync and provider fixture adapter. Live use **Blocked** |
| WhatsApp notification observation | s4, s5 | Implemented+tested | Android `MiniNotificationListenerService`, non-blocking bounded queue, normalizer, deduplicator, 1000-event test suite | Real device listener verification |
| Native handoff | s9 | Simulated | Domain launcher contract; browser fixture does not open a real source app | Android intent/handoff |
| Local inference engine | s8, s20 | Partial | KMP replaceable deterministic engine contract and evidence validator | Qualify a real device-local backend |
| Resource Supervisor | s20.4 | Implemented+tested contract | Bounded queue/cache, cancellation, pressure lifecycle, hardware tier budgets, failure recovery, eval harness and host benchmark | Integrate with real runtime and device signals |
| Android app (Compose) | s3 | Scaffold only | Debug APK builds; shell explicitly says not functional | Compose inbox, permissions, storage |
| iOS app (SwiftUI) | s3 | Blocked | No macOS/Xcode | Build shared core and validate on macOS |
| Outlook, Teams, Meta, LinkedIn adapters | s4 | Not started | Capability matrix documents boundaries | Typed adapters only after qualification |
| Publisher service (no private content) | s2, s13 | Partial+tested | Strict aggregate diagnostics schema; no entitlement fabrication; 13 tests | Signed manifest and real entitlement authority |
| Web hub UI | Stage E | Partial, fixture-backed | Exact review, async guards, outcome states, coverage warnings; production build passes | Playwright and accessibility automation |
| Documentation | Stage 8 | Implemented baseline | Plan, status, security, ADR, connector, test and setup docs | Keep synchronized with integration |
