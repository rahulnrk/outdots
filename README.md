# MINI: Private AI Communication Hub

A local-first unified inbox. MINI pulls messages from several services into one encrypted on-device
store, flags what needs attention, drafts replies with an on-device model, and sends only after the
user reviews the exact text. Product spec: `private-ai-communication-hub-architecture-v1-2.md`.

Per-feature status lives in [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md). It
separates "fixture-tested" from "qualified on device/live"; passing unit tests do not imply live
provider access.

## Layout

- **`native/`**: the product. Kotlin Multiplatform core (`shared`) plus an Android Compose app
  (`androidApp`). iOS targets are declared but not buildable on Windows.
- **`packages/domain`, `apps/hub`, `packages/publisher`**: the earlier TypeScript reference. Domain
  rules, a React demo over synthetic data (no live providers), and a publisher skeleton. Rules are
  kept in parity with `native/` by hand.

## Sources

- Release builds offer Gmail, Outlook and Android notification observation.
- Slack, Teams, Google Chat, Telegram and X appear only in debug builds, labelled "not
  live-qualified".
- The Matrix bridge route (WhatsApp/Instagram/LinkedIn) exists as a foundation only and is not
  offered in the UI.

## Sending

Every send binds a SHA-256 digest of the exact reviewed payload to a short-lived approval. API
sending is off unless the scope is granted and every release gate is cleared by owner-recorded
evidence. Otherwise MINI hands the text off to the native app. No live API send is claimed.

## Quick start

```cmd
npm install
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1
```

See [docs/SETUP_AND_RUN.md](docs/SETUP_AND_RUN.md) for details and
[docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) for the threat review. MVP drafting is English
only ([language scope](docs/MVP_LANGUAGE_SCOPE.md)).
