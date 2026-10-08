# MINI — Communication/Social Connection Points, APIs, Alternatives and Limits

**As of 2026-10-09 — research inventory, not a promise of access.** Official product approval, account types, scopes, permitted purposes and pricing vary. **An API documented for a business Page, bot, workspace or partner does not grant access to the user's personal conversations.** Never offer covert scraping, credential/cookie collection, forbidden automation, accessibility-based mass reading or undocumented private APIs as a shipping solution.

## Connection architecture and legend

**Access types:** `A` = authoritative user-authorized provider API; `B` = business/page/bot/tenant-only; `N` = Android notification *observation*, partial; `I` = user-initiated import/share; `H` = handoff to original app with draft; `X` = no suitable approved generic access verified. **Read** and **send** are separately gated. A connector can offer multiple access paths without claiming equivalence.

**Status labels:** `candidate` means a documented integration route exists, NOT that MINI is approved; `conditional` means app review/account eligibility/partner terms; `limited` means partial metadata/events; `unverified` means no route is established for MINI. The endpoint patterns below are examples to orient implementation; version, app scopes and request parameters must be checked in live official API docs before coding.

## Summary matrix

| Channel / use case | Available route(s) | Read coverage | Outbound | Main restriction / MINI fallback |
|---|---|---|---|---|
| **Gmail** | `A` Gmail REST OAuth; optional IMAP with permitted OAuth | Authorized mailbox/thread + sent mail | Draft/send with appropriate consent | `gmail.readonly`/`gmail.compose` are restricted scopes; verification and possibly security review. User-controlled sync; OAuth separately reviewed. |
| **Outlook / Microsoft 365 email** | `A` Microsoft Graph / Mail.Read; some IMAP OAuth | Authorized folders/mail | Draft/send with Mail.Send | Tenant policies, admin consent, subscription renewal; Microsoft personal/enterprise eligibility varies. |
| **WhatsApp consumer** | `N` Android notification; `I` user export/share; `H` native handoff | Partial notifications or user-exported history | Only supported handoff/manual action | **WhatsApp Cloud API is NOT a consumer personal inbox API.** No blanket access to personal chat archive or own sent replies. |
| **WhatsApp Business Platform** | `B` Cloud API, Meta business onboarding and webhooks | Conversations/events for connected business number where provided | Cloud API message/templates within allowed rules | Business number, verification, pricing, template categories, 24h customer service conversation rules; not all prior history backfilled. |
| **Instagram personal DMs** | `N` Android observation; `H` handoff | Partial | Manual handoff | No generic official API for all personal Instagram DMs. |
| **Instagram professional/business messaging** | `B` Instagram API with Instagram Login or supported Facebook Login route | Qualified professional-account conversations/webhooks | Qualified business messaging, windows | Requires professional account, approved permissions, interaction eligibility, access mode/review, Meta limits. |
| **Facebook personal Messenger** | `N`, `H` | Partial notifications | Manual handoff | Page messaging API != private consumer inbox API. |
| **Facebook Page Messenger** | `B` Messenger Platform Graph API + webhook | Page conversations with relevant events | Page send API under policies/window | Page permissions, app access mode and Meta messaging windows. |
| **LinkedIn personal inbox** | `N`, `H`; selected partner APIs not generally available | Partial | Manual handoff | No broadly available read-all personal inbox API; Communications API use may be partner-gated. Never promise it. |
| **LinkedIn Pages inbox** | `B` via LinkedIn's approved partner platforms | Page messages via supported partner | Partner-mediated | LinkedIn explicitly lists approved partner platforms; generic API grant not established for MINI. |
| **Slack** | `A/B` Slack OAuth + Web API / Events API / Socket Mode | Workspace conversations and events within authorized scopes | `chat.postMessage` subject to permissions | Bot/user token scopes, workspace installation, event subscription, restrictions on DM access and retention. |
| **Microsoft Teams** | `A/B` Microsoft Graph delegated/tenant APIs | Authorized chats/channels, subject to permissions | Graph/Bot framework as allowed | Tenant and admin approval, protected APIs and special permissions; don't assume all channel/DM reads. |
| **Telegram bots** | `B` official Bot API (webhook / getUpdates) | Messages delivered to bot or accessible under group privacy rules | Bot sends | Bot cannot read a user's entire personal inbox. |
| **Telegram user account** | MTProto user client (`A?` conditional developer pathway) | Technically broader with account login, subject to user authorization | User-client actions subject to platform policies | High trust/security and abuse risk; review Telegram app terms, auth flow and user expectations before proposing production. Not first-release path. |
| **Discord** | `B` OAuth/bot REST + Gateway | Servers/channels/DMs bot can legitimately access | Bot messages | Message-content privileged intent and guild/channel perms; not arbitrary personal DMs. |
| **SMS on Android** | default-SMS-role APIs (`A` only if MINI qualifies), `N` notifications, `I/H` share | Full only for eligible default SMS app; otherwise partial | Default SMS role or system handoff | Google Play restricted SMS/Call Log policy; don't request SMS permissions solely as an assistant. |
| **SMS / iMessage on iOS** | `H` MessageUI compose, share; platform-owned message handling | No public general-purpose third-party iMessage history API | User-controlled compose | Cannot silently read iMessage/SMS inbox or send without user action. |
| **Signal** | `N` Android, `H` handoff | Partial notifications | Native handoff | No sanctioned generic consumer-message API verified; avoid unofficial server/client workarounds in consumer release. |
| **Google Chat** | `B` Google Chat API + event/app subscriptions | Spaces/messages authorized for app/user/tenant | Chat app/messages API | Workspace/admin controls, API scopes, event authorization; not blanket personal Gmail Chat. |
| **LINE** | `B` LINE Messaging API | Events sent to official account/channel | Reply/push subject to consent and quota | Official account only; not personal LINE inbox. |
| **Viber** | `B` bot/commercial API (eligibility may change) | Bot traffic | Bot messaging | Access/cost/operator approval; unverified general personal inbox API. |
| **Reddit** | Reddit OAuth API if approved and permitted | Account inbox/comments where authorized | Reply/comment/messages subject to product permissions | Developer access, rate limits, commercial API terms and app policy; verify exact inbox endpoints before implementation. |
| **X/Twitter** | Paid/restricted X APIs and DM endpoints if plan permits | Account-specific and tier-limited | Conditional | API pricing, DM approval/eligibility, policy/rate limits change frequently; treat as unverified until contract. |
| **Threads** | Meta Threads publishing/insights API; other routes case-specific | Posts/replies within authorized product | Publishing only where documented | Not equivalent to Instagram/Facebook DMs; verify actual DM API separately. |
| **TikTok** | Login/content/posting/creator business APIs | Qualified account data/public interactions | Posting where API permits | Do not imply personal DMs can be read; messaging access unverified. |
| **YouTube** | YouTube Data API OAuth | Video comments/live chat where authorized | Post comments/chat as permitted | Not private messages (YouTube doesn't provide general user DMs). |
| **Calendar/meetings** | Google Calendar / Microsoft Graph Calendar | Events, attendees, RSVP, reminders | Create/update only with explicit approval | Not communication transcript; contextual *facts* only; never auto-accept financial/scheduling commitments. |
| **Generic email IMAP/SMTP** | OAuth2 IMAP/SMTP if provider supports; mailto handoff | Mailbox if user and server allow | SMTP with authorization / handoff | Providers may ban password auth; OAuth support and server privacy requirements vary. |
| **Share sheets/imports** | Android ACTION_SEND; iOS share extension/document picker | User-selected text/file only | User-initiated draft export | Low-permission universal fallback; no passive inbox monitoring. |

## 1. Email — Gmail

**Registration:** Google Cloud Console project → Gmail API enabled → OAuth consent + branding → native OAuth Authorization Code with PKCE where supported → sensitive/restricted scope approval. Use least privilege and separate read and write consent.

**REST base:** `https://gmail.googleapis.com/gmail/v1` (some official examples use `https://www.googleapis.com/gmail/v1`). Entry points:

```http
GET  /users/me/messages?q=...&pageToken=...
GET  /users/me/messages/{id}?format=full
GET  /users/me/threads/{id}
GET  /users/me/history?startHistoryId=...
POST /users/me/drafts
POST /users/me/messages/send   # explicit user approval
POST /users/me/watch           # Cloud Pub/Sub push registration
```

**Important:** `messages.list` returns IDs/metadata, so fetch messages separately. Use `historyId` incremental sync with backfill recovery; do not assume push notifications are the message body or each individual email. Gmail push uses Google Cloud Pub/Sub, **a server-side component**, with watch renewal at least every 7 days (Google recommends daily). Google's docs say polling-based synchronization remains recommended for user-owned mobile clients. Consider a local incremental sync before implementing any relay. `gmail.readonly`, `gmail.compose`, `gmail.modify` are **restricted** scopes; `gmail.send` is **sensitive**. Storing/transmitting restricted-scope data on a server can trigger a security assessment. Check product viability and costs of verification early. [Official overview](https://developers.google.com/workspace/gmail/api/guides) · [Scopes](https://developers.google.com/workspace/gmail/api/auth/scopes) · [Push](https://developers.google.com/workspace/gmail/api/guides/push).

**MINI approach:** local OAuth token in OS-protected storage; encrypted local message history and sent messages; local priority/retrieval; use native-mail deep-link/share as a read-only pilot fallback if verification not approved. OAuth read consent does not imply write consent.

## 2. Microsoft Graph — Outlook and Teams

**Authorization:** Entra app registration, Authorization Code + PKCE, delegated permissions (`Mail.Read` / `Mail.ReadBasic` / `Mail.Send` as appropriate). Work/school tenants may require admin consent. Personal Microsoft account support depends on permission and app configuration.

**Base:** `https://graph.microsoft.com/v1.0`.

```http
GET  /me/messages
GET  /me/mailFolders/{id}/messages
GET  /me/messages/{id}
GET  /me/mailFolders/{id}/messages/delta
POST /me/messages                # create draft
POST /me/sendMail               # explicit approval
POST /subscriptions             # change notification subscription
GET  /me/chats                  # Teams permission dependent
GET  /chats/{chat-id}/messages  # Teams permission dependent
GET  /teams/{team-id}/channels/{channel-id}/messages
```

Graph's mail resource supports delta and change notifications. Observe `Prefer: IdType="ImmutableId"` for messages when appropriate since default ID may change after moving a message. Teams chat/channel message access can require privileged permissions and admin approval; Graph ability does not equal your app eligibility. [Mail resource](https://learn.microsoft.com/en-us/graph/api/resources/message?view=graph-rest-1.0) · [Teams message resource](https://learn.microsoft.com/en-us/graph/api/resources/chatmessage?view=graph-rest-1.0) · [Subscriptions](https://learn.microsoft.com/en-us/graph/api/subscription-post-subscriptions?view=graph-rest-1.0).

**MINI approach:** start with email; add Teams only after a tenant pilot and explicit scope consent. For actionable commands, synchronize/send server response with draft/outbox state and never claim 'delivered' just because Graph returned success without a matching provider semantics check.

## 3. WhatsApp — separate consumer vs business

### Consumer app (ordinary WhatsApp chats)

No publicly available Cloud API granting arbitrary consumer personal inbox history. **Valid limited options**: Android NotificationListener observations with explicit permission; user-driven chat export/import where permitted; user share action; native app handoff/deep-link for manual sends. Observe limitations and provenance. Native handoff is **not** a delivery receipt. Do not attempt notification-reply automation to circumvent access policies.

### WhatsApp Business Platform / Cloud API

Official workspace: https://www.postman.com/meta/whatsapp-business-platform/overview ; official portal: https://developers.facebook.com/docs/whatsapp/cloud-api/ . Setup typically involves Meta developer app, WhatsApp Business Account (WABA), business phone number/ID, access token/approved permissions, webhook subscription, test numbers and business onboarding. Embedded Signup applies to authorized solution providers.

```http
POST https://graph.facebook.com/{VERSION}/{PHONE_NUMBER_ID}/messages
POST https://graph.facebook.com/{VERSION}/{PHONE_NUMBER_ID}/media
GET  https://graph.facebook.com/{VERSION}/{WABA_ID}/message_templates
# Incoming message and status notifications are webhooks delivered to the registered public endpoint.
```

**Version/path template only; don't copy without current Graph version.** Distinguish freely-formatted customer-service replies in an eligible window from approved templates for allowed business-initiated messaging; pricing categories and rules are mutable. Verify media limits, message types, inbound window timestamp, business verification and webhook signatures before shipping. Business number ownership and account coexistence conditions may limit onboarding. A webhook server or approved relay is usually required; MINI remains local-first by minimizing relay state/content.

## 4. Instagram professional + Facebook Page Messenger

### Instagram

Meta official: https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-instagram-login/messaging-api and Business Login: https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-instagram-login/business-login . Intended for eligible professional Instagram accounts, not generic personal DMs. Access route may use Instagram Login or Facebook Login connected Page; permission sets differ. Common account messaging permissions include `instagram_business_manage_messages` in the Instagram Login route, subject to version/app-review validation. **Do not hardcode scopes without reading current permission docs.**

```http
GET  https://graph.instagram.com/{VERSION}/{IG_USER_ID}/conversations
GET  https://graph.instagram.com/{VERSION}/{CONVERSATION_ID}/messages
POST https://graph.instagram.com/{VERSION}/{IG_USER_ID}/messages
# Exact host/path/permissions may differ by login product; confirm before use.
```

Meta messaging-window restrictions, recipient interaction eligibility and allowed tagged uses apply; don't treat '24h' as universal to every route or scenario. Build a capability/eligibility evaluator based on actual approval and the last qualifying user interaction. Do not represent all incoming DMs as available for backfill.

### Facebook Page Messenger

Official: https://developers.facebook.com/documentation/business-messaging/messenger-platform/get-started and policy https://developers.facebook.com/documentation/business-messaging/messenger-platform/policy . Requires a Page and Page access token; `pages_messaging` and related Page permissions/access review vary. Webhooks + Graph Send API. Not access to personal Facebook Messenger inbox. Window and tag rules apply; test receipt/status variations. Product should show time eligibility and avoid offers it cannot legally send.

```http
POST https://graph.facebook.com/{VERSION}/me/messages
# authenticated as Page, includes recipient Page-scoped identifier and message payload
```

**Research caveat:** Meta's developer pages were rate-limited in this research session, so API paths/scopes/window exceptions are illustrative; JCode must reconfirm in the official Meta app dashboard/docs and against a sandbox before claiming implementation.

## 5. LinkedIn

LinkedIn authorization docs: https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication . Public OAuth/sign-in/share do **not** imply direct message inbox access. LinkedIn explicitly documents **Pages messaging through currently named partner platforms**, not generic access for every developer: https://www.linkedin.com/help/linkedin/answer/a6246714 . Recruiter InMail and approved Communications APIs are separate commercial/partner scenarios.

**MINI launch behavior:** LinkedIn personal message notifications on Android + user-initiated draft handoff; display 'partial observation'. Mark personal LinkedIn inbox sync `unverified / not generally available`; don't use browser-cookie or unofficial scraping as the plan.

## 6. Slack

Official: https://docs.slack.dev/apis/events-api/ ; https://docs.slack.dev/authentication/installing-with-oauth/ . OAuth app installation per workspace with explicitly scoped bot/user token. Relevant endpoints/examples:

```http
GET  https://slack.com/api/conversations.list
GET  https://slack.com/api/conversations.history
GET  https://slack.com/api/conversations.replies
POST https://slack.com/api/chat.postMessage
POST https://slack.com/api/oauth.v2.access
```

Events API or Socket Mode for notifications; verify Slack signatures/replay windows. Scope/visibility rules restrict private channels and DMs, and some conversation APIs require suitable channel membership/approval. Read backfills may be limited by retention, plan and scopes. OAuth installation for your own workspace is not consent for another workspace.

## 7. Telegram

- **Bot API:** https://core.telegram.org/bots/api ; bot token, `getUpdates` long polling **or** `setWebhook` (not both concurrently). Bots receive messages delivered to the bot; group privacy mode can restrict visibility. `sendMessage` is a **bot** send, not a personal user-account send.
- **MTProto user API:** https://core.telegram.org/api/obtaining_api_id ; broader user-account APIs technically exist, but require sensitive login/session handling and compliance/abuse review. For MINI, defer until a separately reviewed security and policy design. No blanket promise to access secret chats or protected content.

## 8. Discord / Google Chat / LINE / Reddit / X / others

- **Discord** https://docs.discord.com/developers/events/gateway and https://discord.com/developers/docs/resources/channel ; bot Gateway events + REST sending, permission-scoped, privileged message-content intent approval may apply. No full personal inbox.
- **Google Chat** https://developers.google.com/workspace/chat/api/guides/v1/messages/list ; OAuth / Google Workspace chat app + event delivery; space-scoped with Workspace admin constraints.
- **LINE Official Account** https://developers.line.biz/en/docs/messaging-api/overview/ ; webhook + push/reply endpoints for official account, not a personal LINE inbox.
- **Reddit** https://www.reddit.com/dev/api/ ; OAuth endpoints such as `/message/inbox`, `/api/comment` vary by developer approval and commercial terms; verify current app access before implementation.
- **X** https://docs.x.com/x-api ; user access, DM endpoints, rates, commercial plans and app reviews vary; no claim until authorization and pricing confirmed.
- **TikTok** https://developers.tiktok.com/doc/ ; Login Kit/Content Posting/Research APIs have different eligibility; generic personal DM inbox not verified.
- **Threads** https://developers.facebook.com/docs/threads/ ; publishing/post engagement does not imply open personal DM access.
- **YouTube** https://developers.google.com/youtube/v3 ; comments/live-chat access for authorized channel; not personal DM inbox.
- **Viber** https://developers.viber.com/docs/api/rest-bot-api/ ; commercial/bot eligibility and availability must be rechecked.

## 9. Android and iOS OS connection points

**Android**: `NotificationListenerService` (`onNotificationPosted`, `onNotificationRemoved`) and user-granted notification listener settings; ephemeral events that may be grouped, truncated, redacted or absent. Optional `RemoteInput` is NOT a generic authorization bypass and must not be used to claim authoritative sends. System share sheet, user-selected file import and appropriate native app deep links are separate safe routes. Default SMS role may enable official SMS APIs only if the app genuinely qualifies under Android/Google Play rules. Primary doc: https://developer.android.com/reference/android/service/notification/NotificationListenerService .

**iOS**: `UserNotifications` notification service extensions handle your **own app's** remote notifications, not other third-party apps' entire notification feed. User-selected share extensions/document picker and `MessageUI` compose sheets are the main generic handoffs. App Intents and Siri hooks can expose **MINI's own** actions; they do not grant other apps' private databases. Docs: https://developer.apple.com/documentation/usernotifications and https://developer.apple.com/documentation/messageui .

## 10. Connector priorities for MINI

**P0**: Android notification observation; local UI/coverage provenance; user share/import and native handoff; developer fixture adapters and capability manifest. These prove product feel quickly without claiming server approvals.

**P1**: Email Gmail and Graph delegated OAuth (with verification/compliance path), only if pilot user consents and scope approval cost is justified.

**P2**: WhatsApp Business Cloud API + Meta professional Instagram + Facebook Page Messenger, only for qualifying business accounts and webhook relay design.

**P3**: Slack workspaces / Teams tenant / Telegram Bot; LinkedIn Pages only through feasible approved partner relationship. Individual-account LinkedIn/WhatsApp/IG universal inbox remains unsupported without legitimate future API changes.

## 11. Mandatory per-connector capability manifest

```yaml
connector_id: example
account_type: personal | business | page | workspace | bot
platforms: [android, ios]
read_mode: none | notification_observed | import | provider_authoritative
read_scopes: []                # verified exact names + official URL
send_mode: none | manual_handoff | approved_api
send_scopes: []
history_backfill: yes | no | partial | unverified
outgoing_history: yes | no | partial | unverified
webhook_required: true
webhook_transport: platform | relay | none
requires_business_approval: false
eligibility:               # per conversation and per moment
  policy: 'provider_specific'
  qualifying_event_time: null
  send_until: null
  template_if_outside_window: false
limitations: []
receipt_semantics: attempted | provider_accepted | delivered | read | unavailable
last_verified_date: '2026-10-09'
verification_status: documentation_only | sandbox_tested | approved | production_verified
```

Do not expose an action in the user interface if its `send_mode` or current `eligibility` does not allow it. **Provider acceptance is not necessarily delivery.** Consult `04_CONNECTOR_IMPLEMENTATION_PLAYBOOK.md` for operational details.
