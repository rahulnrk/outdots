# Claude Code handoff: Outdots product implementation

## Context to paste into Claude Code

Work in `C:\Users\nande\Downloads\Mini`. Read `AGENTS.md`, `docs/OUTDOTS_HOSTED_AI_PLAN_2026-10-29.md`, then only the source files relevant to the current package. Inspect `git status --short` first: this repository contains substantial existing uncommitted and untracked implementation. Preserve it. Do not reset, clean, stash, bulk-stage, commit unrelated changes or create a checkout that silently omits that work.

Outdots is a communication assistant. Initial services are Gmail, WhatsApp, Facebook Messenger, Instagram DMs and LinkedIn messaging. One hosted product LLM replaces the local-model direction. AI proposes responses, deadlines and follow-ups; the user finalizes actions and reviews every exact outgoing message. Codex owns hosted inference, permissioned multi-account evidence, matter/deadline semantics, protocol blockers and final integration review. Your half is bounded UI/integration implementation and regression coverage.

Execute **C1 only** in this session. Do not implement the entire roadmap, a hosted provider adapter, local model upgrades, a new matter schema or connector protocol changes.

## C1: honest account capability presentation

Build an isolated presentation model and reusable Compose account-readiness component for the five initial providers, using existing connection/capability inputs. It should distinguish:

- Signed in versus receiving messages.
- Foreground-only receive versus qualified background coverage.
- Partial/unknown history versus supported coverage.
- Reply available versus disabled with a readable reason.
- Reconnect required, probe-only and unavailable states.

A connection marked Healthy must never imply complete history, background delivery or send readiness. Missing evidence defaults to unknown/unavailable rather than success. Do not turn historical evidence in markdown into runtime qualification or hard-code five green providers. Notification observation/handoff must remain distinguishable from an authenticated connector.

Starting reads:

- `native/androidApp/src/main/kotlin/app/mini/hub/android/connector/AndroidConnectorRegistry.kt` (`ConnectRouteInfo`, existing account/capability results).
- `native/shared/src/commonMain/kotlin/app/mini/hub/connector/ConnectorContracts.kt` and existing capability resolver definitions.
- Existing Compose conventions in `native/androidApp/src/main/kotlin/app/mini/hub/android/ui/`.

Own new narrowly named files such as `ui/accounts/AccountReadinessPresentation.kt` and `ui/accounts/AccountReadinessCard.kt`, plus their host tests/previews. Reuse the repository's theme/components and add no dependency unless justified. Preview data is explicitly synthetic and must not become production state.

Do not edit `MiniDailyApp.kt`, `InboxViewModel.kt`, `MiniHubRuntime.kt`, `AndroidConnectorRegistry.kt`, shared rules, database schemas or Go runtime in this first package. Provide a small integration note with exact insertion point and required inputs; Codex will own the initial wiring. If the source cannot express a capability, represent unknown and document the missing input instead of inventing domain truth.

Acceptance:

1. Healthy + send disabled renders those facts independently.
2. Foreground-only or unknown background receive never renders “always connected” or an equivalent guarantee.
3. Partial/unknown history stays visible without claiming completeness.
4. Reconnect, probe and unavailable states have clear labels; callbacks only expose supported next steps.
5. No raw token, cookie, email body or internal exception appears in UI/logs. Sending identity remains available in the existing exact-review flow.
6. New component compiles and follows existing accessibility/light/dark conventions. Verify narrow layout and large text if a preview/device is available; state honestly if not exercised.
7. Focused host tests cover the truth table above. Run `native/gradlew.bat :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain` when practical; report baseline versus introduced failures. Never use `connectedDebugAndroidTest` or wipe app data. No live sends, new authentication attempts or gate clearance are part of C1.

Deliver a short report in `docs/CLAUDE_C1_HANDOFF.md`: files changed; public interface; proposed integration point; exact checks/results; remaining unknowns. Do not rewrite the historical implementation-status document or claim live qualification.

## Later reserved packages — do not start yet

- **C2:** Hosted-AI settings and loading/cancel/offline/budget/error UI once Codex supplies the processing-permission and request-state contract. Never show a working toggle backed only by ephemeral UI state.
- **C3:** Focus matter cards, cited evidence, confirm/correct/dismiss controls and canonical composer entry after Codex supplies projection/action interfaces. No parallel draft repository or automatic approval.
- **C4:** Android reminder host/presentation integration from Codex's durable scheduler contract; restart/cancellation fixtures, accessibility and acceptance evidence. Do not replace durable scheduling with an in-process timer.

Each package gets a fresh bounded assignment and exact file ownership. If overlapping changes appear, stop editing that file and report the conflict; do not revert another agent's work. Keep handoff concise so Codex can review the diff without repeating discovery.
