# MINI / JCode — Engineering Knowledge Pack

**Research cut-off:** 9 October 2026. **Status:** reference + proposed design; this pack does **not** assert any feature has been implemented or benchmarked on a device. Official APIs, app approvals, prices and SDK compatibility must be rechecked at integration time.

## What MINI is

MINI is a **private, phone-local AI communication hub** that brings together authorized communication sources, detects what matters, retrieves trustworthy context, creates natural drafts in the user's own voice, and lets the user approve actions. AI memory is a supporting subsystem—not the entire product. Correctness, user control, low battery/RAM usage and transparent source limitations are non-negotiable.

## Start with this file, then give JCode the short prompt

1. Put this whole folder under the existing MINI repository, e.g. `docs/mini-reference/` (or keep paths intact elsewhere in the repo).
2. Paste the contents of **`01_JCODE_START_PROMPT.md`** into JCode (the file is intentionally short).
3. JCode must read relevant references selectively, inspect the real repository and report any conflicts. Do **not** ask it to implement every connector and model simultaneously.
4. Follow **one milestone / test / review / commit** at a time; the next phase must be justified by evidence, not promotional benchmark claims.

## Files

| File | Purpose |
|---|---|
| `01_JCODE_START_PROMPT.md` | Short, pasteable first assignment for JCode |
| `02_MODELS_AND_RUNTIMES.md` | Model selection, capabilities, licensing, optimizations, integration/benchmark guide |
| `03_CONNECTIONS_AND_API_ENTRY_POINTS.md` | Integration routes, API entry points, access restrictions, Android/iOS differences |
| `04_CONNECTOR_IMPLEMENTATION_PLAYBOOK.md` | OAuth, webhook, event schemas, sync, receipts, encryption, privacy and verification |
| `05_AI_HARNESS_AND_EFFICIENCY.md` | Resource-aware harness, memory architecture, execution, measurable optimization |
| `06_ADAPTIVE_STYLE_INTELLIGENCE.md` | Recipient/context-specific style learning, privacy, safety and natural replies |
| `07_JCODE_SWARM_WORKFLOW.md` | JCode capabilities, cautious swarm/task graph workflow, anti-waste rules |
| `08_QUALITY_GATES_AND_TEST_PLAN.md` | Performance/accuracy/reliability gates, eval cases, benchmark procedure |
| `09_OFFICIAL_SOURCES_AND_UNKNOWNs.md` | Primary source URLs, research limitations, required re-verification |

## Strategic decisions (proposals, not verified implementations)

- **Android first for event capture**, because notification listener functionality exists; iOS requires other legitimate access routes and cannot promise parity with Android third-party notification observations.
- **Email first for authoritative historical threading** via Gmail/Microsoft Graph (subject to OAuth app review); **business social** channels through Meta-approved account APIs; **personal WhatsApp/Instagram/LinkedIn** are not interchangeable with business APIs.
- **One inference owner per process**, lazy model loading, bounded queues, deterministic fast-path, retrieval-first context, and budget-aware AI execution. Do not run an agent swarm on the phone.
- **Generation candidates, not a permanent selection:** Gemma 4 E2B text-oriented QAT, LFM2.5-1.2B-Instruct, Qwen3.5-4B. **Retrieval challenger:** EmbeddingGemma 2 text-only. Evaluate on real target phones and languages.
- **Style learned from observed user-authored messages and final user edits**, consented, on-device, with relationships/recipient profiles; style never becomes authority for money, promises, approvals or sending.
- **Platform limitations are displayed as product facts**, not hidden as bugs or bypassed via scraping, accessibility abuse, cookie capture, or private APIs.

## Last reported repository checkpoint (from the user-provided JCode log; not independently audited here)

- Web: **59 tests passed**; native KMP: **17 JVM tests passed**; Android debug APK built.
- Android SQLCipher/Keystore-backed action store implemented and APK packaged with SQLCipher libraries; **on-device/runtime acceptance was not verified** (no usable adb/emulator/device in that run).
- Live connectors, real local inference, iOS builds, notification listener and full persistence validation are **not proven** by this report.
- The log ended with implementation commit `af1fa42` and documentation/acceptance checkpoint `b1d5ef7`; JCode must independently inspect actual HEAD and reconcile any later changes.

## Repository hygiene

Avoid putting real user emails, OAuth tokens, private conversations, provider credentials, screenshots of real content, or model license-restricted weights into Git. Use synthetic fixtures. Use per-platform key storage and encrypt local data. Always distinguish *observed*, *authorized*, *inferred*, *unknown*, and *user-approved* facts.
