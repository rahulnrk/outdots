# MINI connection plan: official accounts first, bridges qualified separately

**Date:** 2026-10-09
**Scope:** Android phone-local product, English MVP. Romanized Hindi/Telugu tests remain deferred exploratory coverage.
**Evidence rule:** A provider API, a Beeper connection, a third-party bridge, and an Android notification are different capabilities. MINI must show the route and coverage honestly.

## What Beeper proves, and what it does not

Beeper's current bridge documentation says Beeper is built on Matrix and that a bridge translates Matrix messages to another network. Its bridges run either on the device for On-Device Connections or in Beeper Cloud. The on-device help page says device-local connections keep chat data off Beeper servers, allow multiple accounts per network, require setup on each device, and need unrestricted battery/data access. It also lists different network sets for on-device and cloud connections.

This is a useful product pattern, not a license to copy a provider protocol into MINI. A Beeper connection may use a provider API, a Matrix bridge, a reverse-engineered or web session, or a device-link flow. Beeper's public bridge/source index and individual bridge repositories must be reviewed at the exact revision before reuse. A bridge's open-source license does not grant provider approval, API permission, or permission to ship a consumer login flow.

MINI therefore uses this sequence:

1. Official OAuth/API routes for Gmail and Outlook first.
2. Teams qualified as a separate Microsoft work/tenant capability, not assumed to equal Outlook.
3. Personal social/messaging networks remain notification-only or native handoff unless an approved provider route is proven.
4. Third-party bridges are a separate server/process product decision. They are not silently embedded in the phone app.
5. Every account owns its account ID, token reference, scopes, cursor, health, conversations, attention state and deletion boundary.

## Primary connection plan

| Network | Account types that are genuinely supportable | Preferred route | Phone-local feasibility | Limitation and release gate |
|---|---|---|---|---|
| **Gmail** | Google consumer Gmail and Google Workspace user mailboxes, subject to consent and restricted-scope review | **Official Gmail REST API + OAuth**. `users/me/profile`, message/thread reads, history cursor and later drafts/send | **High for bounded foreground/periodic reads.** Use Android TLS transport, Keystore token storage and bounded work. Gmail push requires server-side Google Cloud Pub/Sub, so it is not a phone-local-only dependency | Restricted/sensitive scopes, consent-screen verification, refresh/revocation, history-gap recovery, pagination and duplicate handling. The repository now has a public-client PKCE/browser callback path and read-only adapter, but no configured client ID or live account proof |
| **Outlook** | Microsoft consumer accounts and Microsoft 365 work/school accounts, subject to Entra app configuration and tenant consent | **Official Microsoft Graph delegated OAuth + PKCE**. Mail folders/messages, delta, later send | **High for bounded reads.** Graph change notifications require a reachable webhook, so local polling/delta recovery is the initial path | Personal/work tenants differ. Admin consent, throttling, subscription renewal and immutable message IDs must be handled. Implement after Gmail contract tests |
| **Teams** | Primarily Microsoft 365 work/school tenants and organizational users. Do not infer that a consumer Teams identity has the same Graph coverage | **Official Microsoft Graph only where the tenant/app permissions qualify**. Chat and channel messages are separately permissioned | **Conditional.** Foreground/periodic reads can be local, but broad chat/channel notifications and protected permissions may require tenant/server setup | Qualify separately from Outlook. Admin consent, protected/special permissions, tenant policy, incomplete message visibility, chat/channel scope and send policy are release blockers. Notification-only fallback remains truthful |
| **WhatsApp personal** | Personal WhatsApp account linked to a phone; not a WhatsApp Business Platform account | No broad official personal-inbox API verified. Current MINI route is **Android notification observation** and native handoff. Beeper's on-device route uses a third-party bridge/device-link approach | **Notification/local observation: feasible.** A full bridge is not yet a safe Android-library dependency. It would need an isolated process, device-link lifecycle and battery work | Do not substitute Cloud API: it is for business onboarding, webhooks and business messaging, not a user's complete consumer inbox. `mautrix/whatsapp` is AGPL-3.0 at the checked `main` LICENSE, but provider terms, reverse-engineered protocol risk, battery and Android packaging remain unqualified |
| **Instagram** | Personal accounts are not equivalent to professional/business accounts. Official messaging route is for eligible professional accounts with approved permissions | Official **Instagram messaging API** only for qualified professional/business accounts. Personal DMs: notification observation/handoff or third-party bridge qualification | Official business route can require webhooks and server components. Personal on-device bridge is technically possible only as a separately qualified process | `mautrix/meta` contains Messenger and Instagram bridges and its checked LICENSE is AGPL-3.0. This does not grant Meta approval or personal DM API access. Account mode, permissions, review and policy windows must be explicit |
| **Facebook Messenger** | Personal consumer Messenger differs from Facebook Page/business messaging | Official **Messenger Platform** is a Page/business route. Personal account remains notification observation/handoff unless a separately qualified third-party bridge is accepted | Page/business API may need webhook relay. Personal on-device bridge is not yet a MINI shipping route | `mautrix/meta` checked as AGPL-3.0. Page API is not proof of private consumer inbox access. Do not present Page OAuth as a personal Messenger connector |
| **LinkedIn** | LinkedIn member inbox, organization/Page messaging and partner products are distinct | Official LinkedIn APIs do not establish broad personal member inbox access. Use notification observation/handoff; Pages only through approved partner route. Beeper on-device/third-party route is separate | Notification observation is local. A bridge requires a separate isolated runtime and policy/legal review | `mautrix/linkedin` checked LICENSE is AGPL-3.0. This is third-party bridge code, not LinkedIn endorsement. Member auth, session behavior, terms, rate limits and message completeness are unproven |

### Gmail first real connector implemented in this increment

The shared connector package and Android host now provide:

- `ConnectedAccount` metadata with provider account ID, account type, route, state, scopes, health and user-action status.
- `ConnectedAccountStore` and `AccessTokenStore` boundaries, with in-memory implementations for deterministic tests.
- `SyncCheckpoint`, bounded `ProviderMessage`, transport abstraction and explicit HTTP failure states.
- `GmailApiConnector`, which uses only the official REST API, discovers the authorized mailbox identity, lists bounded messages, fetches metadata, returns a page cursor and maps 401/403/429/5xx without pretending success.
- `ConnectorObservationFactory`, which preserves the connected account ID and marks data as `OfficialProviderApi`; it is not mislabeled as an Android notification.
- Android public-client OAuth with PKCE, browser launch, state and callback validation, and a configurable Google client ID. The client secret is intentionally not modeled or accepted by the Android build.
- Android metadata persistence and an Android Keystore AES-GCM access-token store. Access and refresh tokens are account-scoped and no token is put in the account metadata record or logs.
- Android `HttpURLConnection` token exchange and an account-scoped bounded Gmail sync runner that atomically persists observations and a Gmail cursor through the existing observation store.
- Coverage summaries that distinguish `FullProviderSync`, `PartialApiSync`, imported snapshots and notification-only observations.

The authorization path and account lifecycle are independently testable, but there is no configured Google client ID, approved test-account result, or live device result in this repository. Live authorization and two-account sync are therefore still blocked by provider and device setup.

## Later networks from the Beeper screenshot

The supplied screenshot shows: WhatsApp, Instagram, Facebook Messenger, Discord, Google Chat, Google Messages, Google Voice, LINE, LinkedIn, Signal, Slack, Telegram, Tumblr, Viber and X. The following eleven are explicitly deferred after the primary sources.

| Network | Intended account | Official route | Beeper/third-party route and license evidence | Phone-local assessment and limitation |
|---|---|---|---|---|
| **Telegram** | Personal Telegram user account; bot accounts are a different product | Bot API covers bot chats, not the user's complete inbox. MTProto/TDLib can represent a user client but requires Telegram app credentials and a high-trust user authorization review | `mautrix/telegram` checked LICENSE is AGPL-3.0. A bridge/user client is not the same as Bot API | Possible in a dedicated local process, but account login, abuse risk, battery, encryption/session storage and provider terms make it later, not MVP |
| **Signal** | Personal Signal account | No sanctioned generic consumer inbox API for MINI is verified. Notification observation and native handoff are the safe routes | `mautrix/signal` checked LICENSE is AGPL-3.0. Bridge operation and Signal compatibility do not create an official API entitlement | Notification-only is phone-local. Full bridge requires a separate process, identity/session security and explicit legal/provider review |
| **Discord** | User accounts and server members; bot accounts differ | Official Discord bot/OAuth/Gateway route for servers/channels the bot can access. It is not arbitrary personal-DM access; message-content intent and permissions apply | `mautrix/discord` checked LICENSE is AGPL-3.0. Do not use self-bot or user-token automation | Bot/workspace route can be server-based. A personal inbox bridge is not an approved MVP path; use notification/handoff |
| **Slack** | Workspace member/account, possibly multiple workspaces | Official Slack OAuth + Web API/Events API or Socket Mode. Access is workspace install/scopes; DM coverage and retention are limited by permissions | A third-party Matrix bridge would need separate license/revision review. No bridge is reused in MINI now | Official route is conditional but feasible for workspaces. Webhook/event mode may need a relay; local polling/event client must obey scopes and battery limits |
| **Google Chat** | Google Workspace user/app in authorized spaces, not blanket Gmail personal chat | Official Google Chat API and app/user event subscriptions, subject to Workspace/admin/auth model | `mautrix/googlechat` checked LICENSE is AGPL-3.0; third-party bridge remains unqualified | Workspace-only qualification. Do not claim it reads personal Gmail Chat or all spaces without exact scopes and app installation |
| **Google Messages** | Android SMS/RCS user, with device/SMS role boundaries | Android default-SMS role and system APIs are the legitimate local path where MINI qualifies. Google Messages web pairing is not a generic third-party API | `mautrix/gmessages` checked LICENSE is AGPL-3.0 and is a bridge, not a Google API | Full local access would require default-SMS role and Play-policy review. Otherwise notification/import/handoff only. Do not request SMS access solely for AI convenience |
| **Google Voice** | Google Voice user account, with account/region/product eligibility | No general official third-party private inbox API is qualified for MINI | Beeper help lists Google Voice as on-device and cloud supported. Any bridge/repository license and provider terms need revision-specific review before reuse | Treat Beeper as evidence of a product route, not a portable Android SDK. Notification/handoff first; full bridge later |
| **LINE** | Personal LINE users differ from LINE Official Account | Official LINE Messaging API is for Official Accounts/channels, not a personal inbox | Beeper help lists LINE in cloud connections, not on-device list. A third-party bridge needs revision-specific license/terms review | Official route is business/bot only. Personal support remains unqualified; no password/cookie scraping |
| **Tumblr** | Tumblr user and Tumblr DM capability | No general official private-DM API route is qualified for MINI | Beeper help lists Tumblr DMs as on-device and cloud. Bridge license and current auth behavior require direct revision review | On-device product evidence does not make a reusable Android dependency. Notification/handoff first |
| **Viber** | Personal Viber user versus bot/business account | Viber Bot REST API is for bots/commercial conversations, not a complete personal inbox | Beeper screenshot lists Viber; a bridge and current license need direct revision verification | Bot route is not personal account support. Notification observation/handoff only until a qualified provider route exists |
| **X** | X user account, with API tier/DM eligibility | X API availability, pricing and DM access are mutable and plan-dependent. No release claim without a current developer contract | Beeper help lists X on-device and cloud. Any bridge is third-party and must be audited at a pinned revision | Local notification observation is feasible. Official API route is conditional and may require server/webhook economics; bridge is not MVP |

### License and reuse policy

The checked mautrix repositories for WhatsApp, Meta, LinkedIn, Google Chat, Google Messages, Telegram, Signal and Discord expose an **AGPL-3.0** LICENSE at the retrieved default branch. This is source-level evidence only, not a complete dependency/SBOM audit. The bridge repositories also depend on other projects whose licenses must be inventoried before distribution. Tumblr, Viber, X, LINE and Google Voice bridge licensing is intentionally **unverified** here rather than guessed.

AGPL compliance would require source/legal-notice and network-source obligations for a distributed or hosted modified bridge. More importantly, reverse-engineered sessions can violate provider terms and may break without notice. MINI will not embed bridge code, harvest cookies, collect passwords, use accessibility scraping, or ship self-bot behavior as a shortcut.

## Account isolation, filtering and local AI protections

- Every official account has a separate stable `AccountId`, provider account ID, Keystore token reference, scopes, cursor, health and deletion operation.
- Notification observations keep account identity unknown (`local_user`) unless the provider observation establishes it. Selecting a source package is never treated as account authorization.
- Provider sync data is marked `OfficialProviderApi` and displayed with partial coverage while the current adapter is page-bounded. Notification data remains notification-only. Existing source allow-list behavior, attention state and hidden-data preservation remain unchanged.
- AI receives only the selected account/conversation compartment and evidence revisions already allowed by the existing local runtime. No connector sends message content to a publisher, bridge, diagnostics endpoint or remote model. English remains MVP; romanized Hindi/Telugu tests remain deferred.
- Outbound send is not enabled by the first Gmail connector. Exact action approval, recipient/account binding, current permission recheck and ambiguous-outcome handling remain mandatory before adding send.

## Exact setup still missing

### Gmail

1. Google Cloud project with Gmail API enabled.
2. Android public OAuth client configured for this application identity and signing certificate, with the accepted redirect/deep-link configuration.
3. Build-time public client ID supplied as `-Pmini.googleOAuthClientId=<id>`. No client secret is needed or accepted by Android.
4. OAuth consent/branding, PKCE flow and least-privilege scopes.
5. Decision and review for Gmail sensitive/restricted scopes, including any required verification/security assessment.
6. Two real test accounts or a consumer plus Workspace account, with approved consent.
7. Live validation of refresh/expiry/revocation, provider-account identity readback, bounded sync, history/cursor gap recovery, duplicate messages, process death and account deletion on a test device.

### Outlook and Teams

1. Entra app registration with supported account types.
2. Android redirect URI and PKCE flow.
3. Delegated Graph permissions for Mail.Read first, then separate Mail.Send review. Teams chat/channel permissions are a separate qualification.
4. Work/school tenant admin consent where required, plus personal-account validation.
5. Real account fixtures and device testing for delta, throttling, expired tokens, subscription renewal and tenant policy errors.

### Bridge candidates

No provider credentials, Beeper account, Matrix homeserver, bridge deployment, device-link session, provider-approved partner access or legal/licensing sign-off is present. Those are not silently substituted with fake Connect buttons. A bridge pilot would need a separate threat model, pinned source revisions, full license/SBOM review, provider terms review, isolated process boundary, battery policy, crash/reconnect tests and a decision whether message content may leave the phone.

## Source references

- Gmail API guide: https://developers.google.com/workspace/gmail/api/guides
- Gmail OAuth scopes: https://developers.google.com/workspace/gmail/api/auth/scopes
- Gmail history: https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.history
- Gmail push: https://developers.google.com/workspace/gmail/api/guides/push
- Microsoft Graph overview: https://learn.microsoft.com/en-us/graph/overview
- Graph mail resources: https://learn.microsoft.com/en-us/graph/api/resources/mail-api-overview
- Graph Teams concepts: https://learn.microsoft.com/en-us/graph/teams-concept-overview
- Beeper bridge architecture: https://developers.beeper.com/bridges/
- Beeper connection types: https://help.beeper.com/chat-networks/using-on-device-chat-network-connections-in-beeper
- Beeper open source index: https://developers.beeper.com/open-source/
- Android notification listener: https://developer.android.com/reference/android/service/notification/NotificationListenerService
- Meta WhatsApp Cloud API: https://developers.facebook.com/docs/whatsapp/cloud-api/
- Messenger Platform: https://developers.facebook.com/docs/messenger-platform
- Instagram messaging: https://developers.facebook.com/documentation/instagram-platform/instagram-api-with-instagram-login/messaging-api
- LinkedIn developer authentication: https://learn.microsoft.com/en-us/linkedin/shared/authentication/authentication
- Mautrix WhatsApp: https://github.com/mautrix/whatsapp and https://raw.githubusercontent.com/mautrix/whatsapp/main/LICENSE
- Mautrix Meta: https://github.com/mautrix/meta and https://raw.githubusercontent.com/mautrix/meta/main/LICENSE
- Mautrix LinkedIn: https://github.com/mautrix/linkedin and https://raw.githubusercontent.com/mautrix/linkedin/main/LICENSE
- Mautrix Google Chat: https://github.com/mautrix/googlechat and https://raw.githubusercontent.com/mautrix/googlechat/master/LICENSE
- Mautrix Google Messages: https://github.com/mautrix/gmessages and https://raw.githubusercontent.com/mautrix/gmessages/main/LICENSE
- Mautrix Telegram: https://github.com/mautrix/telegram and https://raw.githubusercontent.com/mautrix/telegram/master/LICENSE
- Mautrix Signal: https://github.com/mautrix/signal and https://raw.githubusercontent.com/mautrix/signal/main/LICENSE
- Mautrix Discord: https://github.com/mautrix/discord and https://raw.githubusercontent.com/mautrix/discord/main/LICENSE
