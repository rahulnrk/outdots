# MINI: connector implementation guide for Jcode

Research date: **10 October 2026, Asia/Kolkata**. Audience: Jcode implementing MINI's Android/Kotlin Multiplatform communication hub.

This is a scoped engineering handoff covering the 15 requested providers: Gmail, Outlook, Teams, WhatsApp, Instagram, Facebook Messenger, Discord, Google Chat, Google Messages, LINE, LinkedIn, Signal, Slack, Telegram, and X. It summarizes provider documentation and links to implementation references; it is not a copied archive of every provider's documentation. Provider rules, preview tools, prices, quotas, and approvals must be rechecked before release.

## 1. Product goals and repository evidence

MINI is a private, phone-local communication hub: multiple accounts, one inbox, reliable history where available, honest partial coverage elsewhere, user-controlled attention, local AI drafting, exact review before sending, and recovery without duplicates. English is the MVP drafting language. Romanized Hindi/Telugu remain exploratory. Notifications are observations, not authenticated inbox access.

Repository materials inspected for this handoff:

- `docs/PRODUCT_CONVENIENCE_PRIORITIES.md`: daily workflow, privacy, reliability, battery, account isolation.
- `docs/CONNECTION_PLAN_2026-10-09.md`: official accounts first; business/bot accounts and personal accounts are separate.
- `docs/GMAIL_WORKFLOW_VALIDATION.md`: bounded read-only Gmail path and missing live qualification evidence.
- `native/shared/src/commonMain/kotlin/app/mini/hub/connector/ConnectorContracts.kt`: account, token, cursor, transport, and sync contracts.
- `native/shared/src/commonMain/kotlin/app/mini/hub/connector/GmailApiConnector.kt`: Gmail profile, list, metadata reads, page cursor, known-ID skip, HTTP failures.
- `native/androidApp/src/main/kotlin/app/mini/hub/android/connector/`: Android authorization, storage, and sync host.
- `packages/domain/src/types.ts`, `approval.ts`, and `eligibility.ts`: capability snapshots, approvals, and outbox state.

These are code-inspection findings, not proof that live accounts connect. Some existing documents describe older milestones. Check current source and device evidence before declaring a feature implemented. In particular, the Gmail Android source uses a custom-scheme callback; Google's current [installed-app OAuth guide](https://developers.google.com/identity/protocols/oauth2/native-app) says custom URI schemes are unsupported for Android clients. **Jcode must qualify the supported Android authorization flow before reusing that path.** Follow the Android authorization documentation linked by Google rather than treating an existing callback as provider approval.

### Route decisions

- **Best initial personal inbox routes:** Gmail REST and Outlook Graph, using supported native user authorization.
- **Conditional work inbox routes:** Teams Graph, Slack API, Google Chat API; workspace membership, scopes, tenant policies, and installation matter.
- **Official personal client route with extra qualification:** Telegram TDLib. Its AI/content terms are a separate release gate.
- **Conditional paid personal DM route:** X API; bounded history, endpoint entitlements, and current pricing apply.
- **Business inbox routes:** WhatsApp Cloud API, Instagram professional messaging, Messenger Page messaging, LINE Official Account messaging. These do not connect the corresponding ordinary personal inbox.
- **Personal coverage fallback:** Android notification observation and source-app handoff for unsupported or unqualified accounts, including personal WhatsApp, Instagram, Messenger, LINE, LinkedIn, Signal, Discord, and Google Messages RCS.
- **Separate SMS product choice:** Android SMS/MMS integration is an OS role/permission path, not a Google Messages account API.

## 2. API versus MCP: what Jcode should build

An API is the underlying authorized provider interface. MCP exposes operations as tools to an MCP client. It does not grant missing permissions, unlock personal inboxes, remove quotas, provide unlimited history, or replace durable sync. A hosted provider MCP can work with a local model; MCP alone does not require a cloud model. However, tool responses and prompts must remain inside MINI's approved data boundary.

**Recommended design:** implement deterministic provider adapters for sync and execution. Optionally expose narrowly scoped tools from those adapters to MINI's local AI. The model proposes a draft/action; the app validates it, obtains exact approval, and executes through the same outbox. Do not let a model directly call a remote send tool outside the approval boundary.

Provider-owned MCP findings from this research:

- **Gmail:** remote developer preview, `https://gmailmcp.googleapis.com/mcp/v1`; read/search, labels, drafts. The documented tool list does not include direct send. [Official setup](https://developers.google.com/workspace/gmail/api/guides/configure-mcp-server).
- **Google Chat:** remote developer preview, `https://chatmcp.googleapis.com/mcp/v1`; conversation/message search, message reads/send, read state, memberships. [Official setup](https://developers.google.com/workspace/chat/api/guides/configure-mcp-server).
- **Slack:** remote Streamable HTTP, `https://mcp.slack.com/mcp`; search, messages, and additional collaboration tools. App eligibility and OAuth requirements apply. [Official server](https://docs.slack.dev/ai/slack-mcp-server/).
- **Microsoft:** current Work IQ MCP exposes generic resource operations for Microsoft 365; qualify organizational access and native embedding. Older application-specific Mail/Teams MCP references explicitly say they are for backward compatibility and should not be used for new projects. [Current Work IQ overview](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/overview), [old Mail reference](https://learn.microsoft.com/en-us/microsoft-copilot-studio/mcp-mail-tools), [old Teams reference](https://learn.microsoft.com/en-us/microsoft-copilot-studio/mcp-teams-tools).
- **LINE:** provider-owned preview source server, [line/line-bot-mcp-server](https://github.com/line/line-bot-mcp-server); Official Account tools, not personal chat access.
- **WhatsApp, Instagram, Messenger, Discord, Google Messages, LinkedIn, Signal, Telegram, X:** a supported provider-owned messaging/inbox MCP route was **not established by this research**. This is not a claim that none exists. Community MCP wrappers must be classified by their underlying official API, bot token, device-link session, or unofficial bridge.

Use [MCP authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization) and [MCP security guidance](https://modelcontextprotocol.io/specification/latest/basic/security_best_practices) if implementing an MCP transport. Discover tools/schemas at runtime, allowlist operations, bind credentials to a server/account, validate token audiences, and keep message text from becoming execution instructions. A local process wrapper is not necessarily local processing: Work IQ's `ask` invokes Microsoft services.

## 3. Common implementation contract

These are MINI engineering requirements, not claims that every provider supplies the listed fields or actions.

### 3.1 Account and capability model

Store separately for every connection:

- Provider, stable local account ID, provider user/business/bot identity, tenant/workspace/Page/phone/channel identity, account type, route, and data-processing location.
- Granted scopes, denied capabilities, credential reference, expiry/refresh state, connection health, last sync, and required recovery action.
- Per-folder/channel/conversation cursors where needed; subscriptions and expiry; API version and capability/policy version.
- Coverage: authorized scope, historical start, gap intervals, inbound/outbound visibility, notification-only evidence, and last successful reconciliation.
- Explicit action flags: list/read/search history, body/attachment read, send/reply, edit/delete own message, provider read state, reaction, archive/label, typing, delivery/read receipt, thread/group support.
- API entitlement, administrator approval, reply-window/template eligibility, quota/budget, and AI-use eligibility.

Populate capabilities from the actual account, token, route, and test evidence. Default unknown actions to disabled. Extend existing provider enums for Discord, Google Chat, Google Messages, LINE, Signal, and Telegram; choose explicit migration mappings for existing `facebook`/`twitter` identifiers. Do not merge old account keys merely because display names change.

Notification account identity may remain unknown. Never bind a notification to an OAuth account based only on sender name, app package, or subject. Preserve its provenance and possible duplicates until matching is supported by reliable evidence.

### 3.2 Authentication and storage

Use provider-supported native OAuth or official SDK authorization, a system browser, state validation, PKCE where supported, exact redirect validation, and refresh/revocation handling. Do not put confidential client secrets, app signing secrets, service account private keys, shared bot tokens, or business system-user tokens into a distributed mobile binary. Providers requiring confidential flows need an explicitly designed service, or a documented public-client alternative. Store user credentials in platform-protected account-scoped storage; encrypted inbox content stays local.

Separately implement disconnect, provider-token revocation where supported, and local data deletion. Disconnect need not erase existing history; local deletion must remove the selected account's messages, indexes, drafts, media, and cursors without affecting another account. Show these choices clearly.

### 3.3 Sync and transport extensions

The current `ProviderHttpTransport` exposes only GET, and `ProviderMessage` carries a snippet rather than a complete body/revision model. Before sends or richer connectors, add request method/body, structured JSON decoding, cancellation/timeouts, media streaming, provider error classification, revision/tombstone fields, and account-scoped cursor types. A page token is not an incremental history cursor.

Persist observations and the checkpoint atomically after a successful batch. Handle pagination, deleted messages, edits, duplicate events, stale cursors, lost events, process death, 401/403, 429 with Retry-After, and provider outages. Use bounded sync and controlled media downloads; long-lived sockets need a measured Android background strategy. Show stale coverage when background work cannot run.

### 3.4 Outbound execution

1. Capture account, route, conversation, reply target, recipients, text, attachment digests, policy/window state, and evidence revision in the review payload.
2. Obtain approval for that exact payload; edits or changed routing invalidate approval.
3. At dispatch, recheck permission, account health, policy eligibility, recipient identity, window, and budget.
4. Record the provider request and operation ID; use provider-supported idempotency where available.
5. Distinguish provider acceptance, delivery, read, handoff opened, and unknown outcome. Graph 202, notification PendingIntent dispatch, and opening another app do not prove recipient delivery.
6. After timeout/connection loss, reconcile if supported. Never blindly send again when the first result is ambiguous.

Local snooze/priority/done/hidden state are MINI features. Do not translate them into provider archive, mute, read, or deletion unless the user explicitly chooses that action.

### 3.5 Server boundary

The existing publisher service is not an inbox backend. Gmail polling, Outlook delta, and bounded REST calls can run phone-to-provider. Meta and LINE webhooks require reachable infrastructure. A relay receiving plaintext messages changes MINI's phone-local contract even if it immediately encrypts them afterwards. Put business/server routes behind a separately described deployment mode; do not silently reuse the publisher.

For any approved webhook service: verify signatures/challenges; acknowledge and queue promptly; deduplicate; partition by account; authenticate device delivery; encrypt retained content; minimize retention; redact logs; recover from replay, ordering errors, and downtime. Describe exactly whether plaintext bodies cross that service. Business messaging may need server execution for confidential tokens as well as inbound events.

## 4. Gmail

**Fit:** core personal and Google Workspace mailbox connector. Prefer REST for durable local sync. No separate paid API subscription is normally required, but quotas, mail sending limits, verification, assessment, and optional Pub/Sub costs remain.

**Setup:** enable Gmail API in a Google Cloud project; configure branding/audience/test users, privacy policy, and OAuth verification; register correct platform clients and app signing identity; use supported Android authorization. Start with `https://www.googleapis.com/auth/gmail.readonly`. Add `gmail.send` for sending, `gmail.compose` for provider drafts, or `gmail.modify` for mail changes only when the corresponding feature is needed. Metadata cannot supply message bodies; read/compose/modify/metadata scopes are restricted, while send is sensitive. Restricted data transmitted/stored on servers can trigger a security assessment. [Scopes and verification](https://developers.google.com/workspace/gmail/api/auth/scopes), [native OAuth](https://developers.google.com/identity/protocols/oauth2/native-app).

**Operations:** under `https://gmail.googleapis.com/gmail/v1/users/me`, use `/profile`, `/messages`, `/messages/{id}`, `/threads/{id}`, attachments, labels, and `/history`. The API supports mailbox search, drafts, send, label changes, trash/untrash, and additional settings operations with appropriate grants. Replies need correctly constructed MIME, recipient headers, reply references, and thread association. Local AI needs the actual selected thread body, not just the current adapter's snippet. [REST resource/method index](https://developers.google.com/workspace/gmail/api/reference/rest).

**Sync:** initial paginated snapshot, then persist `historyId` and process `history.list`; recover an invalid/expired history cursor with a bounded resync and visible gap. Pub/Sub watch notifications require Cloud setup and renewal; they signal changes rather than contain a whole email. Start with local polling/history. Gmail API quota units and mailbox sending limits are separate constraints. [Sync](https://developers.google.com/workspace/gmail/api/guides/sync), [push](https://developers.google.com/workspace/gmail/api/guides/push), [quota](https://developers.google.com/workspace/gmail/api/reference/quota).

**MCP:** developer preview enrollment plus Gmail and Gmail MCP APIs, OAuth, and Streamable HTTP. Documented tools include `search_threads`, `get_thread`, `create_draft`, `list_drafts`, labels and label/unlabel actions. Use discovered schemas and tool-specific grants; do not infer a send tool from REST send support. The published web-client secret examples cannot be copied into Android. [MCP setup and tool list](https://developers.google.com/workspace/gmail/api/guides/configure-mcp-server).

**Jcode acceptance:** two accounts; denied consent; supported Android auth; revoked/expired tokens; MIME and attachments; label/edit/delete history; cursor gap; reply recipient correctness; ambiguous send reconciliation. Keep current adapter read-only until sends pass approval tests.

## 5. Outlook

**Fit:** personal Outlook.com and Microsoft 365 work/school mailboxes; register supported account types explicitly. Prefer Graph delegated authorization through MSAL/native public-client flow. A work tenant can restrict consent even when personal sign-in succeeds.

**Setup:** Entra app registration, Android redirect/signature configuration, user sign-in and offline refresh. Read with `Mail.Read`; provider drafts/flags/moves use `Mail.ReadWrite`; send uses `Mail.Send`. Add `User.Read` only if `/me` identity discovery is needed. Mail.ReadWrite does not itself grant send. Avoid app-only tenant-wide mailbox access for this personal hub. [MSAL Android](https://learn.microsoft.com/en-us/entra/msal/android/), [permissions](https://learn.microsoft.com/en-us/graph/permissions-reference).

**Operations:** `https://graph.microsoft.com/v1.0`; list `/me/mailFolders` and messages, fetch bodies/attachments, search where supported, create/update drafts, reply/reply-all/forward, `/me/sendMail`, moves, categories, importance, and read flags. Preserve attachments and recipient identities in approval. Threading uses Graph conversation IDs, not Gmail thread IDs. [Mail overview](https://learn.microsoft.com/en-us/graph/api/resources/mail-api-overview), [sendMail](https://learn.microsoft.com/en-us/graph/api/user-sendmail?view=graph-rest-1.0).

**Sync and limits:** message delta is per mail folder; persist opaque `@odata.nextLink`/`@odata.deltaLink`, reconcile folder moves, and use immutable IDs where qualified. Webhook subscriptions require reachable endpoints, validation and renewal. Throttling, Exchange sending limits, tenant policy, and retention apply. A 202 send response means accepted for processing, not delivered. [Delta](https://learn.microsoft.com/en-us/graph/api/message-delta?view=graph-rest-1.0), [immutable IDs](https://learn.microsoft.com/en-us/graph/outlook-immutable-id), [subscriptions](https://learn.microsoft.com/en-us/graph/change-notifications-overview), [throttling](https://learn.microsoft.com/en-us/graph/throttling).

**MCP:** qualify current Work IQ for organizational Microsoft 365 access; discover `fetch /me/messages`, action `/me/sendMail`, and supported schemas. Its permissions/tenant governance differ from direct Graph grants. Do not assume consumer Outlook support, native Android support, or licensing from the CLI example. Use direct Graph for MINI's baseline. [Work IQ MCP](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/overview), [entity model](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/entity-model).

**Work IQ deployment/cost:** its local CLI example can run `workiq mcp` over stdio, but it calls Microsoft services and is not an Android-local mailbox engine. The Work IQ API documentation describes usage-based Copilot Credits; verify the selected MCP/client licensing and tenant availability separately. Use the current tool/auth reference to discover the correct endpoint and grants instead of hardcoding legacy Agent365 Mail/Teams URLs. [API, deployment examples and billing](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/api-overview).

**Work IQ qualification detail:** the current tool reference says tenant policy blocks mutations by default, caps chat reads at 10 messages per request, and blocks `$skip`/`$skiptoken`. Therefore it is not established as a complete backfill/sync replacement. The permissions page documents admin-enabled delegated `api://workiq.svc.cloud.microsoft/WorkIQAgent.Ask`; do not assume that agent permission supplies every MCP entity operation. Discover current MCP auth metadata, schemas and allowed paths with the tenant administrator. [Tool constraints](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/tool-reference), [permissions](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/permissions).

**Jcode acceptance:** consumer and work accounts, tenant-denied consent, folder moves, attachments, cursor invalidation, immutable IDs, multi-account isolation, and 202/timeout states.

## 6. Microsoft Teams

**Fit:** qualify Microsoft 365 work/school chats and channels separately. Outlook authorization does not establish Teams grants; consumer Teams is not assumed supported.

**Setup:** Entra/MSAL user authorization, eligible tenant, and exact delegated permissions. Candidate read scopes are `Chat.Read` and `ChannelMessage.Read.All`; sending uses `ChatMessage.Send` or `ChannelMessage.Send`. Chat creation/membership and files need additional method-specific grants. For application access, distinguish tenant-wide permissions from resource-specific consent (RSC); these are separate installation/admin models. [Permissions reference](https://learn.microsoft.com/en-us/graph/permissions-reference), [Teams API concepts](https://learn.microsoft.com/en-us/graph/teams-concept-overview), [RSC](https://learn.microsoft.com/en-us/microsoftteams/platform/graph-api/rsc/resource-specific-consent).

**Operations:** list `/me/chats`; messages under `/chats/{id}/messages`; channel posts under `/teams/{teamId}/channels/{channelId}/messages`; channel replies have separate endpoints. Authorized reads can feed local summaries/drafts. Sending, editing, reactions and deletion must be qualified by the specific method and ownership. Files referenced by messages can need OneDrive/SharePoint grants. Bots are a separate sender identity and installation route.

**Limits:** ordinary Graph send is principally delegated; do not use migration-only application permission to send everyday replies. Tenant retention, guest/federated access, DLP, administrator consent, and channel/chat visibility apply. Poll bounded history initially; add subscriptions only after checking supported resources, subscription lifetime, current licensing/payment requirements, and infrastructure. Do not assume a universal mailbox-style delta API. [Send method](https://learn.microsoft.com/en-us/graph/api/chatmessage-post?view=graph-rest-1.0), [Teams change notifications](https://learn.microsoft.com/en-us/graph/teams-changenotifications-chatmessage).

**MCP:** current Work IQ can expose Teams resource reads and authorized operations through generic tools; discover capabilities with `search_paths`/`get_schema`. Older `mcp_TeamsServer` examples are backward-compatibility references, not a new integration target. Work IQ cloud reasoning is outside local-only drafting. [Current entity model](https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/work-iq/mcp/entity-model), [legacy status](https://learn.microsoft.com/en-us/microsoft-copilot-studio/mcp-teams-tools).

**Jcode acceptance:** chat versus channel threading, guests/private channels, missing consent, deletion/edit events, inaccessible files, denied sends, and provider identity shown in review.

## 7. WhatsApp

**Personal account:** this research did not establish an official general personal-inbox API. Use Android notification observation plus source-app handoff; notification reply only when a current notification exposes a usable action and MINI has qualified it. Never label this Cloud API sync.

**Business account setup:** Meta business app/portfolio, WhatsApp Business Account (WABA), registered phone number ID, tokens scoped to the business assets, permissions such as `whatsapp_business_messaging` and `whatsapp_business_management`, webhook verification, and WABA subscription. Multi-business onboarding uses Embedded Signup and the applicable provider/partner process. Keep business secrets on an explicitly designed service. [Meta Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api/), [Meta-owned API collections](https://www.postman.com/meta/whatsapp-business-platform/overview), [Embedded Signup collection](https://www.postman.com/meta/whatsapp-business-platform/documentation/du6gzjv/embedded-signup).

**Operations:** versioned Graph `POST /{phone-number-id}/messages` for supported text, media, template and interactive messages; media upload/download; inbound messages and status events through webhooks; supported read indicators and business profile/template management. This is business/customer messaging, not the user's private chats. History availability and Business App coexistence must be verified for the exact onboarding mode; do not assume all previous messages or personal groups import. [Cloud API collection](https://www.postman.com/meta/whatsapp-business-platform/collection/wlk6lh4/whatsapp-cloud-api), [webhook payloads](https://www.postman.com/meta/whatsapp-business-platform/folder/tduohwq/webhook-payload-reference).

**Limits/cost:** customer-service windows, template approval/category, opt-in, business quality, throughput, recipient limits and message pricing govern sending. Model the standard service-window/free-form versus approved-template distinction, but recheck current rules/rates before enabling send. Meta pricing pages could not be fully retrieved during research; no numeric price or free-message entitlement is asserted. Also verify current AI-provider/business-use rules against MINI's drafting use case. [Pricing and rate cards](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing), [business policy](https://business.whatsapp.com/policy), [Cloud documentation](https://developers.facebook.com/docs/whatsapp/cloud-api/).

**MCP:** no provider-owned personal-inbox MCP established. A Cloud API wrapper inherits business restrictions; a device-link wrapper is an unofficial bridge and needs separate qualification.

**Jcode acceptance:** separate personal/business labels; window expiry while a draft waits; template mismatch; duplicate webhook; accepted/sent/delivered/read failures; revoked asset access; service downtime; no personal history claim.

## 8. Instagram

**Fit:** official messaging for eligible professional accounts (businesses/creators). Ordinary consumer DMs remain notification/handoff coverage.

**Setup:** choose one login family. Instagram Login uses permissions including `instagram_business_basic` and `instagram_business_manage_messages` and does not require a linked Facebook Page. Facebook Login has its own Page linkage/token and permission requirements, including `instagram_basic`/`instagram_manage_messages` in that route. Do not mix scope names or token families. Configure app review/Advanced Access for external customers and account webhooks. [Meta-owned Instagram Login reference](https://www.postman.com/meta/instagram/folder/6raa77c/instagram-api-with-instagram-login), [Facebook Login reference](https://www.postman.com/meta/instagram/folder/u4g5a2a/instagram-api-with-facebook-login).

**Operations:** professional conversation/message reads, supported text/media replies, and messaging webhooks; Instagram Login send uses versioned `graph.instagram.com/{ig-user-id}/messages`. Supported comments, publishing, mentions and insights are additional products/scopes, not required for MINI messaging. [Official messaging guide](https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-instagram-login/messaging-api), [Meta collection and messaging limitations](https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api?entity=request-23987686-e2cfad80-45dd-4111-9ac4-9ae96b9d7ed0).

**Limits:** user-initiated eligible conversations; provider messaging windows; account/app review; no group messaging in the documented professional route; inactive message requests can be omitted after 30 days; shared media may expose a URL rather than a complete object. Do not assume personal inbox parity, arbitrary cold outreach, complete request history, or provider draft/edit/delete capabilities. Recheck current windows and Human Agent exemptions; automated AI replies must not be disguised as an exempt human-agent action.

**MCP:** no provider-owned messaging MCP established. Wrap only approved API methods if needed; preserve professional account, recipient, window and approval checks.

**Jcode acceptance:** creator/business versus consumer rejection; correct login-family scopes; request-folder gaps; blocked out-of-window reply; webhook echoes/deduplication; unsupported group/media action; external user access after app review.

## 9. Facebook Messenger

**Fit:** Messenger Platform connects Page/customer conversations. It is not a generic consumer Messenger inbox API.

**Setup:** Meta app and Facebook Page; Page access token obtained by an authorized Page operator; `pages_messaging` and the relevant discovery/webhook/conversation permissions. Conversations documentation also lists `pages_manage_metadata` and `pages_read_engagement` for its route. Configure Page subscription, webhook challenge/signature, and review/Advanced Access for non-role users. [Messenger Platform](https://developers.facebook.com/docs/messenger-platform/), [Meta-owned Conversations API](https://www.postman.com/meta/messenger-platform-api/folder/22794852-255610cd-47f5-4f4d-b3fa-71aec360be9a).

**Operations:** Page-scoped conversations/messages, versioned `POST /{page-id}/messages`, text/media/templates/quick replies, sender actions, webhook inbound/echo/status events where supported. Bind recipients to Page-scoped IDs (PSIDs); they are not universally interchangeable Facebook user IDs. Useful for an optional business inbox, customer follow-up drafts and human-reviewed replies. [Meta Send API collection](https://www.postman.com/meta/messenger-platform-api/documentation/iyp204x/messenger-platform-api).

**Limits/cost:** the standard documented reply window is 24 hours, with separately qualified opt-in/exemption mechanisms outside it. App review, Page roles, retention and Graph business-use-case limits apply. Read/delivery signals do not establish universal receipt coverage. Do not infer personal E2EE chat access from a Page token. No fixed per-call price asserted; check any paid messaging feature and service hosting separately. The Meta-owned collection was readable; several developers.facebook.com pages were rate-limited, so current exemptions require rechecking.

**MCP:** no supported provider-owned personal Messenger MCP established. A Page API wrapper exposes Page capabilities only.

**Jcode acceptance:** Page-scoped identity isolation, app-role versus external users, reply-window expiry, signature rejection, message echo reconciliation, Page-token revocation, and no fake consumer login.

## 10. Discord

**Fit:** official bot/server integration and user-invoked app interactions. This route does not supply a general mirror of a user's private conversations with other users.

**Setup:** register an application, bot, install context, OAuth `bot`/`applications.commands` where needed, server/channel permissions, and Gateway intents. Reading ordinary server content may require the privileged `MESSAGE_CONTENT` intent and approval according to app eligibility. Bot DMs are conversations with the bot. A user identity OAuth token alone does not authorize full inbox scraping. [Gateway/intents](https://docs.discord.com/developers/events/gateway), [OAuth](https://docs.discord.com/developers/topics/oauth2), [permissions](https://docs.discord.com/developers/topics/permissions).

**Operations:** REST channel history/messages, send as bot, replies, attachments, reactions, supported own-message edits/deletion; Gateway message/reaction updates. Slash/context-menu commands can let the user explicitly share a selected message with MINI. Webhooks can post to configured channels; they are not inbox readers. [Messages](https://docs.discord.com/developers/resources/message), [application commands](https://docs.discord.com/developers/interactions/application-commands).

**Limits:** forbidden self-bot/user-token automation; channel access and content intent; rate-limit buckets, heartbeat/resume/session-start limits; bot attribution; secret management; background socket cost. [Self-bot policy](https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots), [rate limits](https://docs.discord.com/developers/topics/rate-limits).

**Additional route:** Discord's Social SDK supports approved game/social experiences and messaging. Do not treat this as nonexistent, but do not assume MINI qualifies as a general communication-client use case. Ask Discord to establish eligible distribution, platform support, OAuth scopes, message coverage and sender semantics before selecting it. [Official Social SDK documentation](https://discord.com/developers/docs/social-sdk).

**MCP:** no provider-owned general inbox MCP established. Bot wrappers inherit bot visibility and cannot become personal connectors.

**Jcode acceptance:** content redaction without intent, removed channel access, bot sender clearly shown, Gateway resume loss, own versus other-message mutations, and scoped message sharing.

## 11. Google Chat

**Fit:** authorized user's accessible Chat spaces and conversations, especially Workspace. Qualify actual account eligibility; do not assume every consumer Gmail account has equivalent API or preview access.

**Setup:** Google Cloud project, Chat API, user OAuth and consent; configure a Chat app when required. Candidate user scopes: `chat.spaces.readonly`, `chat.memberships.readonly`, `chat.messages.readonly`; send with `chat.messages.create` or a broader qualified scope. Read state uses separate `chat.users.readstate`/readonly grants. Service-account `chat.bot` acts as the app in its accessible spaces, not as the user's full inbox. [Auth matrix](https://developers.google.com/workspace/chat/authenticate-authorize), [user authorization](https://developers.google.com/workspace/chat/authenticate-authorize-chat-user).

**Operations:** `https://chat.googleapis.com/v1`; spaces, memberships, `spaces/{space}/messages`, thread replies, supported reactions and read state. Method-specific auth governs edits/deletions. User sends are attributed to the user with app attribution; don't assume all app-auth rich cards are supported in stable user-auth sends. [Create message method](https://developers.google.com/workspace/chat/api/reference/rest/v1/spaces.messages/create), [API reference](https://developers.google.com/workspace/chat/api/reference/rest).

**Sync/limits:** poll authorized spaces with bounded history or use Workspace Events subscriptions and Pub/Sub after infrastructure qualification. History-off spaces, retention, membership, method quota, admin policy and preview feature availability constrain coverage. Incoming webhooks are posting routes, not personal inbox sync. [Chat events](https://developers.google.com/workspace/events/guides/events-chat), [limits](https://developers.google.com/workspace/chat/limits).

**MCP:** developer preview; enable `chat.googleapis.com` and `chatmcp.googleapis.com`, configure OAuth and use Streamable HTTP. The documented tools are `search_conversations`, `list_messages`, `search_messages`, `send_message`, `mark_as_read`, `mark_as_unread`, and `list_memberships`. Writes require configured Chat app and matching grants. [MCP setup](https://developers.google.com/workspace/chat/api/guides/configure-mcp-server), [release notes](https://developers.google.com/workspace/chat/release-notes).

**Jcode acceptance:** user/app sender distinction, space membership loss, thread targeting, retention/history gaps, read-state grants, duplicate subscription events and preview-disabled accounts.

## 12. Google Messages

**Fit:** three different products must be kept separate: personal Google Messages notifications, Android SMS/MMS, and RCS for Business. No supported public personal Google Messages RCS inbox API was established.

**Personal notification route:** opt-in Android notification listener; scoped observations, draft locally only where content use is permitted, source-app handoff or qualified notification action. Google Messages for Web pairing is not a public developer API contract. Do not use private web protocols as an official connection.

**SMS/MMS route:** Android Telephony content providers and SMS send APIs, subject to user-granted permissions, applicable default SMS role or allowed policy exception, and Play distribution approval. Implementing a default SMS handler is a separate product commitment: receiving/sending, multipart texts, MMS, SIM selection, failures and storage behavior need qualification. It does not automatically give access to Google's RCS store or RCS sending. [Telephony](https://developer.android.com/reference/android/provider/Telephony), [SmsManager](https://developer.android.com/reference/android/telephony/SmsManager), [SMS permission policy](https://support.google.com/googleplay/android-developer/answer/10208820).

**RCS business route:** approved business agent, partner/onboarding and carrier/region launch approval, service authorization and webhooks; endpoint `https://rcsbusinessmessaging.googleapis.com`. Supports agent messages/events, media, rich business interactions, capability checks and supported status events. These are business-to-user conversations, not personal RCS history. [Official RCS for Business overview](https://developers.google.com/business-communications/rcs-business-messaging/guides/get-started/overview), [REST reference](https://developers.google.com/business-communications/rcs-business-messaging/reference/rest).

**Limits/cost/MCP:** no personal inbox MCP established; wrapping SmsManager does not add RCS. SMS carrier/roaming costs and business RCS commercial terms vary. Notifications can be suppressed/redacted; SMS/RCS receipts differ; desktop pairing is not guaranteed mobile integration.

**Jcode acceptance:** SMS/RCS distinction, no unauthorized SMS permissions, dual-SIM targeting, multipart handling, send versus delivery callback, business agent isolation, and truthful notification-only status.

## 13. LINE

**Fit:** Messaging API for LINE Official Accounts. LINE Login is identity/consent, not authorization to read all personal chats.

**Setup:** Official Account, LINE Developers provider/Messaging API channel, channel access token, channel secret and HTTPS webhook. Verify `x-line-signature` against the raw body. Use server-side secrets for a distributed product. [Overview](https://developers.line.biz/en/docs/messaging-api/overview/), [getting started](https://developers.line.biz/en/docs/messaging-api/getting-started/), [signature verification](https://developers.line.biz/en/docs/messaging-api/verify-webhook-signature/).

**Operations:** API `https://api.line.me/v2/bot`; `/message/reply`, `/message/push`, supported multicast/broadcast/narrowcast, user profiles, group/room metadata, rich menus, quotas, inbound webhook content fetch. Reply tokens are short-lived and single-use; a human-reviewed draft may outlive one, so qualify a permissible push route rather than silently sending an expired reply. Store future webhook events locally; the API is not a general historical conversation archive. [API reference](https://developers.line.biz/en/reference/messaging-api/).

**Limits/cost:** recipient relationship and group membership, account quotas and region-specific paid plans, endpoint rates, transient content access, webhook redelivery and unsend events. Prefer documented retry keys where supported. No assumption of personal chat access, arbitrary recipient outreach, provider draft storage or universal delivery/read receipts. [Retry requests](https://developers.line.biz/en/docs/messaging-api/retrying-api-request/), [pricing](https://developers.line.biz/en/docs/messaging-api/pricing/).

**MCP:** LINE-owned preview repository under Apache-2.0. Documented tools include text/Flex push and broadcast, profile, quota, followers and rich-menu actions. Run a pinned audited source build in an appropriate process; a Node server is not automatically an Android library. Discover its current tool list. It does not replace webhook ingestion or provide personal history. [Official repository/setup](https://github.com/line/line-bot-mcp-server).

**Jcode acceptance:** expired/consumed reply token; quota exhaustion; duplicate/redelivered webhook; unsend; unavailable media; blocked user; bulk-send review distinct from single reply. Personal LINE remains notification/handoff.

## 14. LinkedIn

**Fit:** no generally available personal member-inbox API was established. Begin with notification observation and native handoff. LinkedIn sign-in does not grant messaging access.

**Setup for public products:** LinkedIn developer app, OAuth, and approved products. OpenID Connect grants establish identity; marketing/community permissions serve those specific products. They must not be reused as a personal DM connection. [Authentication](https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication), [sign-in with OIDC](https://learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/sign-in-with-linkedin-v2), [product documentation](https://learn.microsoft.com/en-us/linkedin/).

**Conditional partner route:** LinkedIn documents restricted Compliance integrations for approved developers under agreements and data-use restrictions; `r_compliance`/`w_compliance` are not self-serve inbox permissions. Older message/mailbox references are not proof of present public entitlement. Page messaging, Sales Navigator, and compliance archiving must each be backed by written product access, current methods and allowed purpose before building their adapters. [Compliance overview](https://learn.microsoft.com/en-us/linkedin/compliance/compliance-api/overview), [historical API timeline](https://learn.microsoft.com/en-us/linkedin/compliance/upcoming-release-and-deprecation-timeline).

**Useful MINI actions:** capture available notification evidence, source-local attention, manual import/share, and source-app reply handoff. If partner messaging access is secured, implement only its approved read/send/archive features; do not invent endpoint paths or scopes while waiting.

**Limits/cost/MCP:** partner contracts, consent, purpose restrictions, versioning and quota; developer sign-in is not full member-message access. No provider-owned personal-inbox MCP established. A wrapper using private Voyager endpoints/cookies is an unofficial session integration. Public posting/comment features are optional expansions, not substitutes for DMs. [Marketing data-use restrictions](https://learn.microsoft.com/en-us/linkedin/marketing/restricted-use-cases).

**Jcode acceptance:** identity-only OAuth cannot enable inbox controls; partner denial has actionable status; personal evidence stays partial; handoff stays unverified; no invented DM sender.

## 15. Signal

**Fit:** personal Signal notification evidence and native handoff initially. No sanctioned general third-party Signal inbox/bot API was established.

**Official technology boundary:** Signal publishes protocol specifications and client/cryptographic source, but `libsignal` is not a hosted message inbox API; its repository explicitly describes external use as unsupported and APIs as changeable. Implementing Signal Protocol does not itself connect a Signal account or obtain its message history. [Signal specifications](https://signal.org/docs/), [libsignal source and support boundary](https://github.com/signalapp/libsignal).

**Account/device route:** official linked-device behavior belongs to Signal clients. Current official materials include expanded linked Android/iOS device support; do not rely on outdated desktop-only assumptions. This does not establish an embeddable MINI third-party API. [Linked-device update](https://signal.org/blog/linked-devices-and-android-tablets/), [official device support](https://support.signal.org/hc/en-us/articles/360007320451-Troubleshooting-multiple-devices).

**Useful MINI actions:** user-selected notifications, local attention, handoff, and manually shared content, subject to the visibility and content-use boundary. Disappearing/view-once content and safety-number semantics require special treatment; do not preserve restricted content indefinitely or fabricate delivery/read states.

**MCP/alternative:** no official inbox MCP established. Community `signal-cli`/bridges and MCP wrappers are unofficial account/device clients, not endorsed API routes. Any pilot needs pinned dependencies/licensing, device-link/identity keys, account lifecycle, disappearing-message behavior, provider-compatibility testing, process isolation and measured battery use. It is a separately qualified future route, not enabled by this document.

**Limits/cost:** no official third-party API price/limits to quote; unofficial clients have no guaranteed supported quota or service agreement. Notifications may conceal sender/body and miss outgoing history.

**Jcode acceptance:** redacted notifications produce no invented content; expiring content is excluded/expired according to policy; disconnect removes device credentials when an experimental route is ever approved; handoff is never marked delivered.

## 16. Slack

**Fit:** official workspace inbox integration. Keep each workspace/enterprise/user connection distinct; bot access differs from the signed-in user's access.

**Setup:** Slack application, workspace installation/approval, OAuth and token type. Candidate grants by conversation: `channels:read/history`, `groups:read/history`, `im:read/history`, `mpim:read/history`; sending `chat:write`; reactions and files require their own scopes. Search grants differ by API/tool (`search:read` for relevant legacy methods versus scoped search grants in MCP/RTS). Select method/token-specific grants rather than combining every scope. [OAuth](https://docs.slack.dev/authentication/installing-with-oauth/), [method index](https://docs.slack.dev/reference/methods/).

**Operations:** `conversations.list`, `conversations.history`, `conversations.replies`, `chat.postMessage`, supported update/delete/scheduling, reactions, file access, user profiles; Events API or Socket Mode for updates. Preserve `channel` and `ts`/`thread_ts`, workspace identity, subtype and edits. Useful for work attention and reviewed thread replies. Bot tokens cannot read every user DM merely because the app is installed. [Events](https://docs.slack.dev/apis/events-api/), [Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/).

**Limits:** workspace retention, private membership, administrator controls, token type, and method/workspace rates. History/replies distribution-specific limits can substantially restrict backfill for commercially distributed apps outside Marketplace; inspect the exact method pages before sizing sync. Socket Mode avoids inbound HTTP callbacks but adds credentials and continuous connection/battery requirements. [Rate limits](https://docs.slack.dev/apis/web-api/rate-limits/), [history](https://docs.slack.dev/reference/methods/conversations.history/), [replies](https://docs.slack.dev/reference/methods/conversations.replies/).

**MCP:** official Streamable HTTP, fixed registered Slack app identity; currently internal or Marketplace-published apps, not unlisted distribution. Supports search/messages, scheduling/reactions, files, canvases, users and lists. OAuth follows documented metadata/endpoints; review current PKCE public-client support before deciding whether a confidential service is necessary. Same permission/rate limits apply. [Server/auth/tool reference](https://docs.slack.dev/ai/slack-mcp-server/).

**Native OAuth detail:** Slack now documents public-client PKCE for desktop/mobile. Enabling it is a one-way app setting; custom-scheme redirects require PKCE and rotating tokens. The documented desktop-style redirects cannot request bot scopes, and refresh tokens issued with PKCE expire after 30 days. Exchange the code with its verifier without embedding a client secret, then implement rotation and reconnect. Confirm how that public-client route maps to the MCP user-token endpoints before choosing it. [Official PKCE flow and constraints](https://docs.slack.dev/authentication/using-pkce/).

**Data-use gate:** local drafting must comply with Slack API/data terms and workspace controls; do not use Slack messages for model training. Avoid expanding attention features into unsupported individual sentiment scoring. [Marketplace requirements](https://docs.slack.dev/slack-marketplace/slack-marketplace-app-guidelines-and-requirements/).

**Jcode acceptance:** two workspaces, user/bot visibility, private/DM scope denial, thread backfill quotas, retention gaps, edits/deletes, revoked installation, and public-distribution MCP eligibility.

## 17. Telegram

**Fit:** TDLib/Telegram API is an official route for building a personal Telegram client. Bot API is a separate connector to the bot's conversations. This is a stronger personal-client possibility than a generic bot wrapper, but needs full client/policy qualification.

**Personal setup:** obtain MINI's own `api_id` and `api_hash`; integrate TDLib through an Android/native boundary; implement its authorization-state machine, phone/code/2FA (and supported login alternatives), encrypted local database/session lifecycle, logout/revocation and multi-account isolation. Code follows current authorization states rather than assuming SMS always arrives. [API choices](https://core.telegram.org/api), [TDLib](https://core.telegram.org/tdlib), [application credentials](https://core.telegram.org/api/obtaining_api_id), [TDLib method reference](https://core.telegram.org/tdlib/docs/).

**Personal operations:** chat/history/search, new/update events, messages/media, read state, supported edit/delete/reactions, group/channel actions and account settings as exposed by the approved client API. Secret chats are device-specific and not automatically imported from other clients. Cloud chats and secret chats have different encryption/availability semantics. Follow supported client behavior for expiring content and statuses.

**Bot setup/actions:** BotFather token; `https://api.telegram.org/bot<TOKEN>/METHOD`; `getUpdates` polling or webhooks (not both simultaneously), `sendMessage`, media, supported edits/deletion and chat actions. Bot visibility depends on chat membership/privacy mode; bots cannot import the user's entire history or freely initiate every personal conversation. Token URLs must be redacted from diagnostics. [Bot API](https://core.telegram.org/bots/api).

**Critical AI-use gate:** Telegram's API terms include restrictions on AI use. Its content-licensing terms restrict training, validation, benchmarking and deployment use, with narrowly described consent exceptions involving all relevant users and specific contexts. Do not assume the connecting user's consent or local inference alone clears this restriction. Ship manual client workflows separately; keep Telegram-derived AI drafting/embedding/summary pipelines disabled until the exact use and consent model is qualified. [API terms](https://core.telegram.org/api/terms), [AI/content licensing terms](https://telegram.org/tos/content-licensing).

**Limits/cost/MCP:** official APIs are offered free, but flood waits, anti-spam limits, client transparency/branding requirements and sponsored-message rules apply. No provider-owned inbox MCP established. Community wrappers must say TDLib user versus bot explicitly.

**Jcode acceptance:** 2FA/revocation, per-account session DB, flood wait, process restart, history/edit/delete events, secret-chat gaps, expiring media, bot privacy, and AI blocked without qualification.

## 18. X (Twitter)

**Fit:** official authenticated-user DM route, conditional on app entitlement and budget. Prefer API over notification evidence when coverage qualifies; do not promise unlimited history or encrypted-chat parity.

**Setup:** approved developer app/project, supported user authorization (OAuth 2.0 PKCE or applicable OAuth 1.0a route), identity verification and current DM scopes. Candidate OAuth 2.0 scopes include `dm.read`, `dm.write`, `users.read`, `tweet.read`, and `offline.access` where required by the exact methods. Confirm the current authentication mapping before consent. App-only bearer access is not private-user DM authorization. [OAuth PKCE](https://docs.x.com/fundamentals/authentication/oauth-2-0/authorization-code), [DM lookup](https://docs.x.com/x-api/direct-messages/lookup/introduction).

**Operations:** API v2 DM event reads, `POST /2/dm_conversations`, `/2/dm_conversations/with/{participant_id}/messages`, `/2/dm_conversations/{id}/messages`, and `DELETE /2/dm_events/{id}` where entitled. Supports one-to-one/group events and supported media. Deleting a DM event is described as deletion for the authenticated user; do not label it universal unsend. Public posts/replies/mentions are separate optional capabilities. [DM management reference](https://docs.x.com/x-api/direct-messages/manage/introduction).

**Sync/limits/cost:** lookup documentation exposes events up to 30 days old. Persist a local archive only within applicable data-use/deletion rules and display the initial coverage boundary. Pagination, recipient settings, anti-spam, endpoint-specific rates, app entitlements and the API's current pay-per-use credit system constrain operation. Activity subscriptions/webhooks need separate qualification; don't assume historical Account Activity examples still match the selected API product. [DM lookup bounds](https://docs.x.com/x-api/direct-messages/lookup/introduction), [pricing](https://docs.x.com/x-api/getting-started/pricing), [rate limits](https://docs.x.com/x-api/fundamentals/rate-limits).

**MCP:** no provider-owned personal-inbox MCP established. An official-API wrapper inherits paid usage, scopes and the 30-day lookup limit; a cookie/scraper wrapper is a different unqualified route.

**Jcode acceptance:** missing DM entitlement, budget depletion, bounded initial history, expired refresh, recipient rejection, duplicate sends after timeout, group targeting, and no inference that API covers newer encrypted chat features.

## 19. Shared Android fallback for personal networks

Use this only for user-selected installed sources when direct API capability is absent or not qualified. It is a real partial-coverage product mode, not a temporary fake account connector.

- Ask for OS notification-listener access with clear source selection and disclosure; maintain a package allowlist. Observe only allowed sources and preserve source timestamp/key, text fragments, revision and account uncertainty.
- Notifications may hide bodies, combine conversations, be disabled, be absent while the source app is open, or reveal no sent messages/history. Work profiles and device policies can restrict access.
- Native handoff uses supported intents/links and user interaction. Show the review text, but do not assume another app accepts recipient/text prefill or provides a completion receipt.
- Notification reply uses a currently present qualified `RemoteInput` action/PendingIntent only. Revalidate target and action at send time; a stale token or canceled notification may fail. PendingIntent dispatch does not prove delivery.
- Do not use accessibility scraping, password capture, cookie harvesting, notification dismissal as message-read proof, or hidden self-bots to manufacture complete coverage.
- iOS cannot inherit this Android cross-app observation design. Use provider APIs and explicit share/import/handoff features that the target platform supports.

References: [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [RemoteInput](https://developer.android.com/reference/android/app/RemoteInput), [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent), [Android intents](https://developer.android.com/guide/components/intents-filters).

## 20. Implementation order and deliverables

This is an engineering recommendation derived from MINI's goals, not a claim that every connector can ship now.

### Phase A: usable personal email

1. Audit current Gmail Android auth against supported provider flow; configure real clients/test users and record live evidence.
2. Implement full selected-thread body/MIME read, incremental history and gap handling.
3. Add exact-review Gmail send and provider outcome reconciliation.
4. Add Outlook delegated auth, per-folder delta, bodies/attachments and reviewed send.

Deliverable: two real accounts per qualified provider, truthful coverage, reconnect, and a verified review/send workflow.

### Phase B: work communications and truthful personal fallback

1. Add Teams chat/channel adapters after tenant qualification.
2. Add Slack workspace installation, scoped history/thread reads and user/bot attribution.
3. Add Google Chat user-auth adapter; evaluate official preview MCP as an optional tool route.
4. Extend notification source selection and native handoff to the requested personal networks; qualify notification replies separately.

Deliverable: supported work scopes and visible notification-only personal coverage, with no full-history claim.

### Phase C: conditional personal clients

1. Qualify TDLib build/account lifecycle and Telegram content-use requirements; manual client features may precede AI eligibility.
2. Qualify X DM entitlement, privacy scope, costs and 30-day coverage.
3. Decide separately whether MINI should become an Android default SMS/MMS app; RCS remains a distinct gap.

### Phase D: optional business deployment

1. Specify the accepted server/privacy mode, token custody and customer onboarding.
2. Add WhatsApp Business, Instagram professional, Messenger Page and LINE Official Account adapters.
3. Handle webhook ingestion, sender identity, window/template eligibility, billing and durable recovery.
4. Evaluate LINE/Slack/Google/Microsoft MCP only where eligibility and process constraints fit.

### Deferred qualification

Personal WhatsApp/Instagram/Messenger/LINE/LinkedIn/Signal full inbox bridges; general personal Discord client; personal Google Messages RCS protocol. Record them as unsupported/unqualified routes rather than attempting private endpoints. Provider-approved new products or explicit partner access can change this decision.

## 21. Configuration Jcode needs from project/account owners

Jcode can implement adapters and fixtures without production secrets. Live qualification needs the following; supply secret values through a credential mechanism, not this file or source control.

- App identity: Android package, signing certificates, iOS bundle if applicable, domains, accepted redirects, privacy policy, support and deletion URLs.
- Google: project ID, enabled APIs, platform OAuth client IDs, consent audience, approved test users, verification status; preview enrollment only for MCP pilots.
- Microsoft: tenant/client IDs, supported account types, platform redirects, delegated permissions, administrator consent; Work IQ-specific permission/license/tenant setup if selected.
- Slack: app/workspace IDs, approved install/scopes/token families, distribution status, token rotation, and supported public-client or service authorization configuration.
- Meta: app/business/Page/Instagram/WABA/phone IDs, business access/review, exact login route, webhook deployment, templates and billing; recheck current version and policies.
- LINE: provider/channel/Official Account, protected credentials, webhook deployment and region plan; pinned MCP release only if selected.
- Discord: app/bot IDs, installation scopes, channel permissions/intents, secret service, or explicit Social SDK eligibility.
- Telegram: MINI's api_id/api_hash, TDLib build revision, user auth test accounts, session-storage design, and qualified AI/content consent model.
- X: app/project and user auth configuration, DM entitlements, current endpoint scopes, credit budget and quota evidence.
- LinkedIn: written product/partner messaging access if pursued; otherwise no pretend inbox permission.
- Google Messages/SMS: approved OS-role/distribution decision; RCS business agent only for a separate business route.
- Signal/personal bridge pilots: separately approved integration strategy; no official inbox secret/token is established here.

## 22. Release checks for every connector

Run fixture/contract checks first, then controlled live-account/device checks. A successful build or mock does not establish provider approval or completeness.

1. Correct account kind and provider identity discovered; second account stays isolated.
2. Consent cancellation, reduced grants, expired/revoked credentials, reinstall, and reconnect recover honestly.
3. Initial history bounds and gaps are visible; cursor replay/process death do not duplicate or lose stored events.
4. Inbound/outbound, groups/threads, edits/deletions, attachments and retention are represented only where supported.
5. Source scope and local attention choices survive sync/reconnect; provider mutations require an explicit action.
6. Drafting uses permitted selected-account context, with no unapproved cloud prompt/model upload; content cannot authorize its own tools.
7. Recipient/account/thread/payload changes invalidate approval; window/permissions/budget rechecked at send time.
8. Provider-accepted, delivered, read, unknown, and handoff states stay distinct; uncertain sends are not blindly retried.
9. Rate limiting/outages/subscription expiry and missing administrator/partner access produce useful recovery status.
10. Disconnect/revoke/delete, redacted logs, encrypted storage, media cache and account-specific deletion are verified.
11. Target-device background/battery behavior measured; bounded work remains responsive.
12. Each enabled capability carries method/tool, scope, account type, official source, API version, reviewed date and live test evidence.

## 23. Research verification and known gaps

**Directly reviewed official content:** Google Gmail REST/scopes/sync/quota/native OAuth/MCP; Google Chat auth/send/events/MCP; Microsoft Graph mail/delta/send/Teams send and Work IQ docs; Discord Gateway/messages/self-bot policy; Slack MCP/rates/policies; LINE overview/reference/retry and provider MCP repository; Telegram APIs/TDLib/Bot API/content terms; Signal provider source/support; X DM lookup/management/pricing; Android notification/SMS and Play permissions.

**Meta access limitations:** several developers.facebook.com pages returned HTTP 429 or were inaccessible. Meta's own Postman collections supplied readable WhatsApp platform/webhook, Instagram login/messaging, and Messenger send/conversation evidence. Meta pricing, newest WhatsApp service-window exceptions, group/coexistence/history features, AI-provider rules, and exact current app-review requirements remain recheck items. Do not use inaccessible pages as proof that a numeric price/limit was verified.

**Additional implementation references:** some links above are official starting points discovered from provider docs and have not each been tested method-by-method. Current scopes, quotas and API versions must be verified for the chosen endpoint/account before coding it as an enabled feature. Negative findings mean no supported route was established in this research, not proof of universal nonexistence.

**No live external accounts were authorized, no credentials were collected, and no connector was implemented by producing this document.** Existing code and provider documentation are separate evidence classes.

## 24. Copyable task brief for Jcode

Implement MINI connectors using this guide and the current repository's domain, Android and KMP boundaries. Begin with Phase A. Recheck current provider documentation and current source before changing an adapter. For each provider, choose an explicit account type and route; record capabilities, limitations, data flow, grants and source evidence. Keep unsupported personal inbox features disabled with honest fallback coverage. Extend the current GET/snippet contracts before full-body sync or sends. Preserve account-scoped encrypted state, local AI boundaries, user attention choices, exact review and uncertain-send handling. Qualify Android Gmail authorization against Google's supported client flow first. Keep Telegram-derived AI processing disabled until its content-use requirements are resolved. Prefer direct APIs for durable sync; optional MCP tools must enter the same validated approval/outbox path. Do not embed confidential secrets, reuse the publisher as an inbox service, fabricate provider approvals, or equate mocks with live success. Deliver each provider with setup instructions, credential placeholders, contract tests, live qualification checklist, observed coverage and a reproducible connect/read/review/reply/recover workflow. If an external credential, tenant approval, business deployment or partner entitlement is missing, finish the fixture-backed adapter work and report that exact live gate without presenting the connector as released.

