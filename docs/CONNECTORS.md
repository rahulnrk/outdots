# MINI: Communication Connectors Architecture & Status

Research & verification date: **10 October 2026, Asia/Kolkata**.

This document is MINI's authoritative connector specification and release status for all 15 requested providers. It documents the architecture, implemented adapters, capability model, release gates, configuration setup, and live evidence boundaries.

---

## 1. Core Architecture & Privacy Contracts

MINI is a private, phone-local communication hub.
- **Account isolation:** Every account has a provider-scoped `AccountId` (`provider|localId`). Two accounts (or two contacts with the same name across accounts) never merge.
- **Route truthfulness:** Every connector declares its exact route (`OfficialProviderApi`, `NotificationOnly`, `ThirdPartyBridge`, `UserImport`, `NativeHandoff`). A notification fallback is never presented as an official provider API. Private scraping and unofficial session bridges are forbidden.
- **Capability-first defaults:** All capabilities (`ListHistory`, `ReadBody`, `IncrementalSync`, `Search`, `ReadAttachments`, `SendNew`, `Reply`, `EditOwn`, `DeleteOwn`, `MarkRead`, `React`, `Archive`, `LocalAiDrafting`) default to **disabled**. A capability turns on only when:
  1. The connector adapter implements it.
  2. The account granted the required OAuth/API scopes.
  3. All required **Release Gates** for that capability are cleared by written evidence.
- **Local AI boundary:** AI models propose drafts locally. Message content cannot authorize tools or trigger network sends. Sends run only through `ConnectorProviderGateway` behind exact human approval.
- **Disconnect vs Forget:** `disconnect` revokes credentials and marks the account `Disconnected`, keeping stored history so a reconnect reuses the same local ID without duplicating messages. `forget` erases account metadata.

---

## 2. Implementation Matrix (15 Requested Providers)

| Provider | Primary Route | Account Kind | Status in Code | Default Capabilities | Key Release Gates |
|---|---|---|---|---|---|
| **Gmail** | Gmail REST API (`gmail.readonly`, `gmail.send`) | Personal / Workspace | `AdapterImplemented` | History, Body, Incremental, Reply*, SendNew*, LocalAI | LiveQualification, SendQualification, GoogleRestrictedScopeVerification |
| **Outlook** | Microsoft Graph Mail (`Mail.Read`, `Mail.Send`) | Personal / Work | `AdapterImplemented` | History, Body, Incremental, Reply*, SendNew*, LocalAI | LiveQualification, SendQualification, MicrosoftAppRegistration |
| **Teams** | Graph Teams Chats (`Chat.Read`, `ChatMessage.Send`) | Work / School | `AdapterImplemented` | History, Body, Reply*, LocalAI | MicrosoftAppRegistration, TenantAdminConsent, SendQualification |
| **Slack** | Slack Web API (`im:history`, `chat:write`) | Work / Workspace | `AdapterImplemented` | History, Body, Reply*, LocalAI | SlackAppInstallation, SendQualification |
| **Google Chat** | Google Chat API (`chat.messages.readonly`, `chat.messages.create`) | Work / Workspace | `AdapterImplemented` | History, Body, Reply*, LocalAI | GoogleChatAppConfiguration, SendQualification |
| **Telegram** | Telegram Bot API | Bot account | `AdapterImplemented` | History, Body, Incremental, Reply*, LocalAI** | TelegramBotToken, SendQualification, TelegramContentUseTerms** |
| **X (Twitter)** | X API v2 Direct Messages (`dm.read`, `dm.write`) | Personal / Creator | `AdapterImplemented` | History (30d), Body, Reply*, LocalAI | XApiEntitlementAndBudget, SendQualification |
| **WhatsApp** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |
| **Instagram** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |
| **FB Messenger** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |
| **LINE** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |
| **LinkedIn** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |
| **Signal** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |
| **Discord** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |
| **Google Messages** | Notifications + Native Handoff | Personal Observer | `NotificationFallbackOnly` | Notification observation, Open in App | NotificationListenerAccess |

*\* Reply and SendNew require `SendQualification` gate evidence.*  
*\*\* Telegram local AI drafting is blocked by `TelegramContentUseTerms` until explicit consent model is qualified.*

---

## 3. Provider Setup & Configuration Guide

Unconfigured routes are visibly gated in the UI, stating exactly what is missing. Real keys must be supplied locally.

### 3.1 Gmail
- **Endpoint:** `https://gmail.googleapis.com/gmail/v1/users/me`
- **Scopes:** `https://www.googleapis.com/auth/gmail.readonly`, `https://www.googleapis.com/auth/gmail.send`
- **Auth:** Native Android AuthorizationClient / system browser PKCE. Custom scheme derived from Android Client ID.
- **Sync strategy:** `history.list` with `startHistoryId`. On 404 (expired history cursor) or 400 (invalidArgument format), performs a bounded resync snapshot and flags `gap = true` on `SyncCheckpoint`.
- **Sending:** RFC 5322 MIME builder (`MimeMessageBuilder`) with `inReplyTo` and `references` headers. Sent via `POST /messages/send`.
- **Configuration:** `mini.googleOAuthClientId` in `native/local.properties`.

### 3.2 Outlook (Microsoft Graph)
- **Endpoint:** `https://graph.microsoft.com/v1.0`
- **Scopes:** `Mail.Read`, `Mail.Send` (delegated)
- **Auth:** MSAL Android / PKCE (Public client, no client secret).
- **Sync strategy:** Inbox folder delta query (`GET /me/mailFolders/inbox/messages/delta`). Opaque `@odata.deltaLink` stored as cursor. 410 / `SyncStateNotFound` triggers a round restart with `gapCount++`.
- **Sending:** `POST /me/messages/{id}/reply` or `POST /me/sendMail`. Returns HTTP 202 Accepted.
- **Configuration:** `mini.outlookOAuthClientId` in `native/local.properties`. Needs the `app.mini.hub://oauth2redirect/outlook` redirect.

### 3.3 Microsoft Teams
- **Endpoint:** `https://graph.microsoft.com/v1.0`
- **Scopes:** `Chat.Read`, `ChatMessage.Send` (delegated)
- **Auth:** MSAL Android / PKCE using the `/organizations/` authority (rejects personal accounts).
- **Sync strategy:** `GET /me/chats` ordered by last message, fetching messages per chat with `ChatCursor` per-conversation high-water marks.
- **Sending:** `POST /chats/{id}/messages`.
- **Configuration:** Uses `mini.outlookOAuthClientId`. Needs the `app.mini.hub://oauth2redirect/teams` redirect.

### 3.4 Slack
- **Endpoint:** `https://slack.com/api`
- **Scopes:** `im:history`, `channels:history`, `groups:history`, `mpim:history`, `chat:write`
- **Auth:** PKCE public client flow. Refresh tokens expire after 30 days of inactivity. Requires `user_scope`, not bot scopes.
- **Sync strategy:** `conversations.list` -> `conversations.history` per channel with `oldest` cursor.
- **Sending:** `chat.postMessage` with `thread_ts` for replies.
- **Configuration:** `mini.slackClientId` in `native/local.properties`. Needs the `app.mini.hub://oauth2redirect/slack` redirect.

### 3.5 Google Chat
- **Endpoint:** `https://chat.googleapis.com/v1`
- **Scopes:** `https://www.googleapis.com/auth/chat.spaces.readonly`, `chat.messages.readonly`, `chat.messages.create`
- **Auth:** Google OAuth 2.0 PKCE.
- **Sync strategy:** `GET /spaces` -> `GET /spaces/{space}/messages` ordered by `createTime desc`.
- **Sending:** `POST /spaces/{space}/messages?requestId={id}` with `thread` object for replies.
- **Configuration:** Uses `mini.googleOAuthClientId` and its derived custom scheme with path `/oauth2redirect/chat`.

### 3.6 Telegram Bot
- **Endpoint:** `https://api.telegram.org`
- **Credential:** BotFather bot token (`12345:ABCDEF...`). Token is stored in platform Keystore per account.
- **Sync strategy:** `POST /bot<token>/getUpdates` long-polling with `offset = max(update_id) + 1`.
- **Sending:** `POST /bot<token>/sendMessage` with `reply_parameters`.
- **Critical AI Gate:** `TelegramContentUseTerms` blocks `LocalAiDrafting` by default.
- **Configuration:** Runtime copy/paste into the Accounts UI.

### 3.7 X (Twitter) Direct Messages
- **Endpoint:** `https://api.x.com/2`
- **Scopes:** `dm.read`, `dm.write`, `tweet.read`, `users.read`, `offline.access`
- **Auth:** OAuth 2.0 PKCE.
- **Sync strategy:** `GET /dm_events?event_types=MessageCreate` up to 30 days of history.
- **Sending:** `POST /dm_conversations/{id}/messages`.
- **Configuration:** `mini.xClientId` in `native/local.properties`. Needs the `app.mini.hub://oauth2redirect/x` redirect and Paid tier.

---

## 4. Shared Contracts & Module Structure

- `ConnectorContracts.kt`: Common `ConnectedAccount`, `StoredAccessToken`, `SyncCheckpoint`, `ProviderMessage`, `ProviderHttpRequest`, `ProviderHttpResponse`, `ProviderHttpTransport`, `ConnectorHttpException`, `ProviderErrorKind`.
- `ConnectorCatalog.kt`: Complete route registry, `Capability` enum, `ReleaseGate` enum, `CapabilityResolver`.
- `Json.kt`: Dependency-free JSON parser and serializer (`JsonValue`, `JsonWriter`).
- `ProviderApiClient.kt`: Unified bearer-auth HTTP request edge with error classification and `Retry-After` parsing.
- `Rfc3339.kt`: Dependency-free timestamp parser.
- `GmailApiConnector.kt`: Gmail REST adapter, history sync, full MIME thread reader, `MimeText` plain-text extractor.
- `OutlookGraphConnector.kt`: Graph mail adapter, delta query sync, HTML stripper, `OutlookSender`.
- `ChatConnectors.kt`: Slack, Google Chat, Teams, Telegram Bot, and X DM connectors and senders.
- `SendContracts.kt`: `ProviderSender`, `ConnectorProviderGateway`, `MimeMessageBuilder`, `GmailSender`.
- `ConnectorSyncEngine.kt`: Provider-neutral sync engine managing token refresh, atomic batch commit (observations + cursors in one transaction), deduplication, tombstone handling, and health state mapping.

---

## 5. Verification & Test Evidence

- **Shared JVM Test Suite (`:shared:jvmTest`):** 105 passed tests covering JSON parsing, catalog capability resolution, Gmail history resync, Outlook delta link tracking, Slack/Telegram sync & send, sync engine atomic commits, and gated gateway dispatch.
- **Android App Build (`:androidApp:assembleDebug`):** Compiled cleanly with 0 errors.
- **Live Device Evidence:** Recorded in `docs/GMAIL_WORKFLOW_VALIDATION.md` (commit `05eaa71`). Re-verified OAuth re-authorization (`Revoked -> Authorized -> Healthy`), non-duplication of local accounts, and persistent "Access removed" status across background syncs.
