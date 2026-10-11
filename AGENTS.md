# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## What this is

MINI ("Private AI Communication Hub"): a local-first unified inbox. It pulls messages from many
services into one encrypted on-device store, flags what needs attention, drafts replies with an
on-device model, and sends only after the user reviews the exact text. The product spec is
`private-ai-communication-hub-architecture-v1-2.md`; docs cite it as "arch s<N>".

The repo holds two parallel implementations of the same rules:

- **`native/`** — the real product. Kotlin Multiplatform core (`shared`) + Android Compose app (`androidApp`). iOS targets are declared but only on macOS.
- **`packages/domain`, `apps/hub`, `packages/publisher`** — the earlier TypeScript reference: domain rules, a React demo over synthetic data (no live providers), and a publisher service skeleton. Rule changes must be kept in parity by hand; nothing is generated.

`README.md`, `docs/SETUP_AND_RUN.md` and `docs/SECURITY_REVIEW.md` predate the native app and understate it. `docs/IMPLEMENTATION_STATUS.md` is the current per-feature status.

## Commands

TypeScript (repo root, Node 20+):

```
npm install
npm test                      # vitest, all workspaces
npx vitest run packages/domain/test/security.test.ts -t "<test name>"
npm run typecheck
npm run build
npm run dev                   # web demo at http://127.0.0.1:5173
```

Native (from `native/`, JDK 17, Android SDK; `native/local.properties` needs `sdk.dir`):

```
gradlew.bat :shared:jvmTest
gradlew.bat :shared:jvmTest --tests "app.mini.hub.connector.GmailApiConnectorTest"
gradlew.bat :androidApp:testDebugUnitTest
gradlew.bat :androidApp:assembleDebug
```

There is no lint, formatter or CI configuration.

OAuth client IDs are public IDs supplied at build time, via `-Pmini.googleOAuthClientId=…`
(also `mini.outlookOAuthClientId`, `mini.slackClientId`, `mini.xClientId`) or the gitignored
`native/local.properties`. Never add a client secret. The Google ID also determines the manifest
redirect scheme, so changing it requires a rebuild.

Device tests (`androidApp/src/androidTest`) need a physical device and, for Gmail tests, connected
accounts. Run them with `adb install -r` + `adb shell am instrument -w -e class <Class>#<method>
app.mini.hub.test/androidx.test.runner.AndroidJUnitRunner`. Do **not** use
`connectedDebugAndroidTest`: it uninstalls the app and wipes stored authorizations.

The on-device model (`qwen2.5-0.5b-instruct-q8.task`) is not in the repo and not downloaded by the
app; it must be pushed to the app's external `files/models/` directory.

## Architecture (native)

`MiniHubRuntime` (androidApp) is the process-wide singleton that wires everything. It is
initialised from `MainActivity`, `MiniNotificationListenerService` and `GmailSyncJobService`, so
ingestion works with no UI. Android classes are thin hosts; rules live in
`shared/src/commonMain/kotlin/app/mini/hub/`:

- **`observation/` + `persistence/`** — every incoming item, from any source, becomes an `ObservedNotification` in `ObservationStore`. Two inputs feed it: the notification listener (bounded queue → normalise → dedupe → store, tracked by `DeliveryAccountingLedger`) and API connectors (`ConnectorObservationFactory`, keys of the form `provider:<accountKey>:<messageId>`). Android stores are SQLCipher with Keystore-wrapped keys (`shared/src/androidMain`); the JVM target uses sqlite-jdbc for tests.
- **`connector/`** — `ConnectorCatalog` declares each provider's route, scopes and release gates. `AccountConnector` implementations read (Gmail, Outlook Graph, Teams/Slack/Google Chat/Telegram/X in `ChatConnectors.kt`, Matrix bridge for WhatsApp/Instagram/LinkedIn). `ConnectorSyncEngine` is the generic per-account sync host; Gmail has its own incremental-history path in `AndroidGmailConnector`. All HTTP goes through `ProviderHttpTransport`, and JSON through the hand-written `Json.kt` (the shared module has no third-party dependencies by design).
- **`action/`** — the send path. `ActionController.approve` binds a SHA-256 digest of the `ExactActionPayload` to a short-lived approval; `dispatch` re-checks digest and expiry, then goes either to `ConnectorProviderGateway` (API send) or a `HandoffLauncher` (opens the native app with the text). "Accepted" means provider acceptance, never delivery; failures after the call started are `OutcomeUnknown` and are never auto-retried.
- **Gates** — API sending is off unless `CapabilityResolver` finds the scope granted and every `ReleaseGate` cleared. Qualification gates (e.g. `SendQualification`) are cleared only by owner-recorded evidence in `GateEvidenceStore`, written solely by the debug-build `GateEvidenceDebugReceiver`. Tests and defaults must never clear them.
- **`attention/`, `understanding/`, `retrieval/`, `style/`, `intelligence/`** — inbox triage state, deterministic deadline/action signals, account-scoped context retrieval, opt-in style learning (default off), and the inference contract. The MediaPipe engine and its lifecycle supervisor are in `androidApp/.../intelligence/`.

IDs are account-scoped (`AccountId` = `provider|localId`, `ScopedId` adds kind and value); cross-account access is refused by construction. Time comes from an injected `Clock`.

## Conventions

- Logs carry local account IDs, states and counts only — never message content, addresses or tokens.
- MVP drafting scope is English; romanised Hindi/Telugu cases are exploratory (`docs/MVP_LANGUAGE_SCOPE.md`).
- Status docs distinguish "fixture-tested" from "qualified on device/live"; keep that distinction when updating them.
- `tmp/` is scratch (device helper scripts with a hard-coded device serial, research OCR output).
