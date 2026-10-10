# Gmail and Outlook Workflow Validation & Status

Date: **10 October 2026**. This is a historical evidence record. The current connector registry and product decision record supersede its implementation summary where they differ.

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
  - The current `PkceAccountConnector` handles token refresh via `grant_type=refresh_token`.
- **Incremental Android Gmail Connector:** `AndroidGmailConnector` is updated to run incremental history sync and support full thread body reads via `fetchThread`.
- **Runtime Gateway & Outbox Dispatch:** `MiniHubRuntime` initializes `ConnectorProviderGateway` with `GmailSender` and `OutlookSender`. `InboxViewModel` automatically routes drafts for connected official API accounts to `ActionRoute.Api` with exact approval review and status message feedback upon provider acceptance.
- **Accounts & Inbox UI:** Updated `InboxScreen` and `ConnectedAccountSettingsDialog` to support connecting, syncing, and disconnecting both Gmail and Outlook accounts alongside notification-only sources.

---

## 2. Test Verification Summary

- **Shared JVM Suite (`:shared:jvmTest`):** Passed, covering JSON parsing, catalog capability resolution, OAuth/routing contracts, Gmail incremental history resync, Outlook delta query tracking, Slack/Telegram chat sync & send, bridge boundary checks, sync engine atomic commits, and gated gateway dispatch.
- **Android Unit Tests (`:androidApp:testDebugUnitTest`):** All 34 passed (robolectric attempt reverted).
- **Android App Compilation:** `:androidApp:assembleDebug` built with 0 errors.
- **Historical device evidence:**
  - Earlier work installed a debug build on Pixel 10 Pro (`56270DLCH000KZ`) while preserving stored data. This record does not claim that the current uncommitted registry was installed or that the full connect-to-send flow was live-qualified.
  - The latest documented Gmail state was **Temporarily unavailable** because the build lacked `mini.googleOAuthClientId`; no live send or live Outlook sign-in was performed. The earlier `refreshed=true` wording is intentionally not treated as verified here.
  - At the time of the current verification pass, `adb devices` reported no connected device, so no new device check was performed.

---

## 3. Account Choice & Live Prerequisites

### 3.1 Personal vs Work/School Accounts
- **Gmail:** Supports consumer `@gmail.com` accounts and Google Workspace accounts.
- **Outlook:** Supports personal Microsoft accounts (`@outlook.com`, `@hotmail.com`, `@live.com`) and Microsoft 365 work/school accounts.

### 3.2 Prerequisites for Live Credentials & External Verification
Live send, API reading, and Outlook sign-in are disabled until real credentials are provided. MINI's UI visibly gates these routes.
1. **Google Cloud OAuth Client:** Supply the Android client ID in `native/local.properties` (`mini.googleOAuthClientId`).
2. **Microsoft Entra App Registration:** Supply `mini.outlookOAuthClientId` with the Entra App Client ID. Register `app.mini.hub://oauth2redirect/outlook` and `app.mini.hub://oauth2redirect/teams` as public client redirect URIs. Grant `Mail.Read`, `Mail.Send`, `Chat.Read`, `ChatMessage.Send`, `offline_access`, `openid`, `profile`, `User.Read` delegated permissions. Work/school tenants may require administrator consent depending on organizational policy.
3. **Slack App:** Supply `mini.slackClientId`. App must be a public client, enable PKCE, configure redirect `app.mini.hub://oauth2redirect/slack`, and list the user scopes (not bot scopes).
4. **X App:** Supply `mini.xClientId`. App must be an OAuth 2.0 Native App with DM read/write and `offline.access`. Paid tier required.
5. **Google Chat API:** Requires the Workspace cloud project to enable the Google Chat API and configure a Chat app. Uses the same Google OAuth client ID.
6. **Telegram Bot:** Paste a BotFather token directly into the MINI Accounts dialog.

**Review Check:** The `GmailAndOutlookWorkflowTest` fixture passed, but no live API send or live Outlook/Teams/Slack/X/Telegram API call has ever occurred on this device. Live accounts and evidence must be provided manually to clear the qualification release gates in `ConnectorCatalog`.
