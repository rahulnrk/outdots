# Gmail and Outlook Workflow Validation & Status

Date: **10 October 2026**. Repository baseline: commit `625be4e` + Android Gmail & Outlook workflow completion.

---

## 1. Verified System Status & Capabilities

### 1.1 Shared Multiplatform Engine
- **JSON & Transport Edge:** Built dependency-free `JsonValue`/`JsonWriter` parser and `ProviderApiClient` supporting GET, POST, PATCH, DELETE, `Retry-After` rate-limit handling, and error classification across all 15 provider routes.
- **Provider Catalog & Capability Resolver:** Implemented `ConnectorCatalog` with default-disabled capabilities (`ListHistory`, `ReadBody`, `IncrementalSync`, `SendNew`, `Reply`, `LocalAiDrafting`). Capabilities are enabled dynamically only when required scopes and release gates are cleared.
- **Gmail Incremental & MIME Thread Support:**
  - `GmailApiConnector.syncIncremental` implements `history.list` token tracking.
  - Expired history tokens (HTTP 404) trigger an automatic bounded snapshot and flag a visible gap (`gap = true`).
  - `MimeText` extracts plain-text bodies from complex MIME structures, and `fetchThread` retrieves full thread context for reading and on-device AI drafting.
- **Outlook Graph Adapter:**
  - `OutlookGraphConnector` implements Graph Inbox delta query (`GET /me/mailFolders/inbox/messages/delta`).
  - Stores opaque `@odata.deltaLink` tokens as per-folder cursors. 410 / `SyncStateNotFound` triggers a round restart with `gapCount++`.
  - `OutlookSender` handles reviewed API sending via `POST /me/sendMail` and `POST /me/messages/{id}/reply`, returning HTTP 202 Accepted.
- **Gated Outbound Gateway:** `ConnectorProviderGateway` bridges approved outbox commands (`ExactActionPayload`) to provider senders. It re-verifies account health, tokens, granted scopes, and release gates at dispatch time.

### 1.2 Android App Integration
- **Upgraded Android Transport:** `AndroidProviderHttpTransport` handles full HTTP methods, content-type headers, request bodies, and response header extraction.
- **Android Outlook Connector & OAuth Client:**
  - `OutlookOAuthClient` implements MSAL / PKCE S256 authorization code exchange against Microsoft Entra v2.0 endpoints (`https://login.microsoftonline.com/common/oauth2/v2.0/authorize` and `/token`).
  - Passes `prompt=select_account` allowing the user to select either a personal Microsoft account (`@outlook.com`, `@hotmail.com`, etc.) or a work/school account.
  - Handles custom scheme redirect (`app.mini.hub://oauth2redirect/outlook` and `msauth`).
  - `MicrosoftTokenRefresher` handles silent token refresh via `grant_type=refresh_token`.
- **Incremental Android Gmail Connector:** `AndroidGmailConnector` is updated to run incremental history sync and support full thread body reads via `fetchThread`.
- **Runtime Gateway & Outbox Dispatch:** `MiniHubRuntime` initializes `ConnectorProviderGateway` with `GmailSender` and `OutlookSender`. `InboxViewModel` automatically routes drafts for connected official API accounts to `ActionRoute.Api` with exact approval review and status message feedback upon provider acceptance.
- **Accounts & Inbox UI:** Updated `InboxScreen` and `ConnectedAccountSettingsDialog` to support connecting, syncing, and disconnecting both Gmail and Outlook accounts alongside notification-only sources.

---

## 2. Test Verification Summary

- **Shared JVM Suite (`:shared:jvmTest`):** 108 passed tests covering JSON parsing, catalog capability resolution, Gmail incremental history resync, Outlook delta query tracking, Slack/Telegram chat sync & send, sync engine atomic commits, and gated gateway dispatch.
- **Android Unit Tests (`:androidApp:testDebugUnitTest`):** All 40 passed.
- **Android App Compilation:** `:androidApp:assembleDebug` built with 0 errors.
- **Device Installation & Live Background Sync:**
  - Build installed on connected Pixel 10 Pro (`56270DLCH000KZ`).
  - Live logcat verification at 14:34:36 IST: Account B (`gmail-de284ee8-1d73-4c3d-be10-cd1d3b931fdb`) automatically refreshed its access token (`refreshed=true`), synced 50 messages, and remained `Healthy`. Account A synced 50 messages and remained `Healthy`. Zero message duplication observed.

---

## 3. Account Choice & Live Prerequisites

### 3.1 Personal vs Work/School Accounts
- **Gmail:** Supports consumer `@gmail.com` accounts and Google Workspace accounts.
- **Outlook:** Supports personal Microsoft accounts (`@outlook.com`, `@hotmail.com`, `@live.com`) and Microsoft 365 work/school accounts.

### 3.2 Prerequisites for Live Credentials & External Verification
1. **Google Cloud OAuth Client:** Build with `-Pmini.googleOAuthClientId=<your Android client ID>`. Request `gmail.readonly` for reads and `gmail.send` for reviewed API sending.
2. **Microsoft Entra App Registration:** Build with `-Pmini.outlookOAuthClientId=<your Entra App Client ID>`. Register `app.mini.hub://oauth2redirect/outlook` as a public client redirect URI with `Mail.Read`, `Mail.Send`, `offline_access`, `openid`, `profile`, `User.Read` delegated permissions. Work/school tenants may require administrator consent depending on organizational policy.
