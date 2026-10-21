# Connector roadmap

> **Latest 19 October decision:** WhatsApp, Facebook Messenger, Instagram and LinkedIn use phone-local Android protocol clients, following existing WhatsApp packaging/session architecture. This supersedes the earlier same-day Matrix-route target for those providers. Keep remote plaintext blocked; extend the shared native runtime with per-provider qualification. Slack route feasibility remains separate. See the [selected implementation sequence](BRIDGE_AND_AI_OPTIONS_2026-10-19.md).

> **19 October 2026 direction:** [Unified demo roadmap](UNIFIED_DEMO_ROADMAP_2026-10-19.md) records the user's new selection: official Gmail/Outlook/Teams/Android SMS routes; Matrix bridges for WhatsApp/Messenger/Instagram/LinkedIn/Slack/iMessage. This supersedes conflicting route direction below. Bridge hosting/privacy and SMS versus RCS remain explicit decisions. Existing adapters and historical qualification are unchanged.

> **Product scope update, 15 October 2026:** [Current requirements](PRODUCT_REQUIREMENTS.md) supersede conflicting work order and coverage claims here. Further Instagram, Facebook/Messenger and LinkedIn work is deferred. An official API is not a guarantee of full history, personal-inbox access, new-conversation support, all media types or live-qualified sending. WhatsApp personal currently uses an unofficial debug linked-device route. The register requires capability-by-capability evidence and treats the reported notification clutter as unresolved. No connector consolidation, protocol work, install, send or gate change is authorized by this documentation update.

**Date:** 13 October 2026. Inputs: arch v1.2, [implementation status](IMPLEMENTATION_STATUS.md),
[connectors](CONNECTORS.md), [phone-local bridge feasibility](PHONE_LOCAL_BRIDGE_FEASIBILITY.md) and the external
"Cross-Platform Communication Connectors Study" (Oct 2026).

Connectors are built **one at a time**. A connector is not started until the previous one meets its
definition of done, unless it is blocked on an external party (review, credentials), in which case the next
one starts and the blocked one is tracked.

## Definition of done (every connector)

1. Adapter + fixture tests (`:shared:jvmTest`) for auth, sync cursor, gap recovery, send, error kinds.
2. Capability snapshot in `ConnectorCatalog` is truthful (route, history, send route, coverage).
3. UI shows the right tier: **Full Sync**, **Smart Companion** (notification-derived) or **Assisted Handoff**.
4. Live device run on the Pixel recorded in a validation doc (connect, sync, process death, reconnect).
5. Send path goes through exact-review approval; `SendQualification` cleared only by owner evidence.
6. `IMPLEMENTATION_STATUS.md` updated, keeping "fixture-tested" vs "qualified on device/live" separate.

## Product decision (13 Oct 2026)

Personal chat networks use **on-device connections** (Beeper On-Device model): the protocol client runs inside
MINI on the phone, plaintext never leaves the device, session keys are stored in SQLCipher + Keystore, no Matrix
homeserver and no MINI server in the path. Everything else uses **official APIs**. The user always taps Send.

| Network | On-device engine (candidate) | License | Login on phone | Status |
|---|---|---|---|---|
| WhatsApp (+ Business app) | whatsmeow | MPL-2.0 | Linked device, pairing code | Packaged; owner-verified on Pixel (debug) |
| Google Messages SMS/RCS | libgm (mautrix-gmessages) | **AGPL-3.0** | Pair as Messages for Web (QR or Google account) | Not started |
| Instagram DMs | mautrix-meta (instagram mode) | **AGPL-3.0** | In-app login WebView, cookies kept on device | Not started |
| Messenger | mautrix-meta (messenger mode) | **AGPL-3.0** | In-app login WebView, cookies kept on device | **In progress** (21 Oct 2026): E2EE device state is a sealed in-memory store + AES-GCM snapshot (not plaintext SQLite). Cookie SQLCipher unchanged. Accounts still unavailable. See [Messenger feasibility](MESSENGER_PHONE_LOCAL_FEASIBILITY_2026-10-20.md) |
| LinkedIn messages | mautrix-linkedin | **AGPL-3.0** | In-app login WebView, cookies kept on device | **BLOCKED** (20 Oct 2026): embedded Android WebView Cloudflare-blocked before session; manager/bot cookie paste needs a bridge host, not phone-local Accounts. See [LinkedIn feasibility](LINKEDIN_PHONE_LOCAL_FEASIBILITY_2026-10-20.md) |
| iMessage | none viable | - | - | **Not possible** on Android or iOS; see below |

Engineering constraints:

- **One Go runtime.** gomobile produces one `libgojni.so`; two gomobile AARs cannot coexist. All engines go into a
  single `native/bridgeRuntime` Go module (rename of `whatsappRuntime`) with one Kotlin facade per network.
- **AGPL.** Linking libgm / mautrix-meta / mautrix-linkedin into the distributed app makes the combined runtime
  AGPL. Options: (a) publish MINI's bridge runtime (and likely the app) under AGPL, (b) obtain a commercial
  license from the authors, (c) write our own clients from the protocol. Prototype work can proceed in debug;
  release requires this decision. **20 Oct 2026 review:** upstream `LICENSE.exceptions` cover only Beeper and
  Element. whatsmeow pulls in **GPL-3.0 `go.mau.fi/libsignal`**, so even a WhatsApp-only release is copyleft.
  Recommended path: request a MINI exception upstream while preparing full AGPL compliance. A Gradle guard now
  blocks release builds that contain the runtime without `-Pmini.licenseDecision`. See
  [Licensing compliance review](LICENSING_COMPLIANCE_2026-10-20.md).
- **iMessage.** Apple's protocol needs Apple-signed device validation. Beeper Mini was shut down by Apple; Beeper
  now supports iMessage only on a Mac. Fallback: Android notification capture is impossible (no iMessage on
  Android); on iOS, handoff only; optional future "Mac relay" owned by the user (plaintext on the user's Mac).
- **Risk.** Reverse-engineered clients can break on protocol changes and can trigger account checks. Human-only
  sending lowers but does not remove this. Each connection is opt-in with a plain disclosure.

## Tiers

| Tier | Meaning | Routes |
|---|---|---|
| A. Official API | Full history, sync, API send | Gmail, Outlook/M365, Slack, Teams work, Google Chat, Telegram, Meta business, X |
| B. Android companion | Notification capture + inline reply + export import | WhatsApp (both), Messenger, Instagram personal, SMS/RCS, Teams personal, LinkedIn, Signal, Discord, LINE |
| C. Unofficial on-device client | Reverse-engineered protocol running on the phone (Beeper On-Device style) | WhatsApp (whatsmeow, debug today), later Instagram/Messenger |
| D. Handoff only | Draft locally, open source app | Everything not covered above; all of iOS consumer apps |

## Order

| # | Connector | Tier | Current state | Work to finish | External blockers |
|---|---|---|---|---|---|
| 1 | **Bridge runtime consolidation** | C | `whatsappRuntime` single-network | Rename to `bridgeRuntime`, one Go module, per-network Kotlin facades, shared session store | None |
| 2 | **WhatsApp on-device** | C | Owner-verified on Pixel (debug) | Release gating, 14-day primary-phone warning, coverage label, multi-account | None |
| 3 | **Google Messages on-device (libgm)** | C | Not started | Pair, sync history, receive, reviewed send, reconnect after process death | AGPL decision for release |
| 4 | **Instagram on-device** | C | Official Pro adapter exists | mautrix-meta engine, in-app login, checkpoint handling | AGPL decision |
| 5 | **Messenger on-device** | C | Notification-only; sealed E2EE device store on rtprobe | Same engine as #4 (messenger mode); owner WebView on rtprobe | Live owner login + encrypted inbound qualification; Accounts still unavailable |
| 6 | **LinkedIn on-device** | C | Notification conversations (`2a51f79`..`25ee51c`); runtime/session scaffolding | mautrix-linkedin engine + verified phone-local login | **BLOCKED:** Cloudflare on embedded WebView; manager/bridge cookie paste not phone-local |
| 7 | **Gmail** | A | Device-verified sync | Live send qualification, OAuth verification prep | Google verification |
| 8 | **Outlook / M365** | A | Fixture-tested | Live run on device | Entra registration |
| 9 | **Slack** | A | Fixture-tested, debug | Live workspace run | Slack app distribution |
| 10 | **Teams (work/school)** | A | Fixture-tested, debug | Live tenant run, admin-consent link | Tenant consent |
| 11 | **Google Chat** | A | Fixture-tested, debug | Live Workspace run | Workspace allowlist |
| 12 | **Telegram (TDLib)** | A | Bot API only | TDLib user client | api_id registration |
| 13 | **X DMs** | A | Fixture-tested, debug | Paid add-on | X API credits |
| 14 | **Business APIs** (IG Pro, Pages, WA Cloud) | A | IG Pro adapter | Only if business users need them | Meta App Review |
| - | Notification capture (all Android sources) | B | Qualified on device | Stays as a fallback when an on-device connection is disconnected | None |
| - | Chat export import | B | Not started | WhatsApp `.zip`; mainly for iOS | None |
| - | iOS | - | Blocked (no macOS) | Same engines via gomobile xcframework inside a notification extension; needs a content-free push wake-up | macOS, Apple dev account |
| - | iMessage | - | Not possible | Handoff only; optional user-owned Mac relay later | Apple |

Cross-cutting, scheduled alongside #2: **content-free wake-up relay** for Gmail Pub/Sub and Graph
subscriptions (notification carries only an ID; device fetches content). Off by default; requires disclosure.

## Sending rule (all tiers)

MINI never sends on its own. The local model writes a draft; the user reviews the exact text and taps Send.
This holds for API, inline-reply and unofficial-client routes alike.

## iOS mapping

| Route | iOS |
|---|---|
| Tier A official APIs | Same shared connector code (KMP); background refresh needs a push wake-up instead of WorkManager |
| Tier B notification capture / inline reply | Not possible: iOS does not expose other apps' notifications |
| Chat export import | Works via a Share Extension |
| Tier C unofficial on-device | Possible (Beeper does it inside the Notification Service Extension) but needs a wake-up push service and fits a 50-150 MB extension memory limit; Go runtime via gomobile xcframework, unmeasured |
| Handoff | Works via URL schemes (`whatsapp://`, `linkedin://`, `sms:`) |

## Progress (13 Oct 2026)

Completeness pass done for Gmail, Outlook, Slack, Teams, Google Chat, X and WhatsApp (see
IMPLEMENTATION_STATUS). Telegram is on hold by owner decision.

Next, owner actions:
1. Microsoft app registration (one client ID for Outlook + Teams), redirects `app.mini.hub://oauth2redirect/outlook` and `/teams`.
2. Slack app (PKCE, user scopes), redirect `app.mini.hub://oauth2redirect/slack`.
3. Enable the Google Chat API in the existing Google Cloud project.
4. X only if a paid DM plan is wanted.
Then: live connect/sync/send run per provider on the Pixel and record gate evidence.

## Rejected routes (13 Oct 2026)

- **Cloud aggregators (Unipile and similar):** hold users' passwords/cookies and read messages on their servers;
  API key requires a MINI relay server. Conflicts with phone-only processing.
- **Hosted Matrix bridges (mautrix on a server):** plaintext on the bridge host.
- **Beeper content provider:** competitor dependency.
- **Cloud LLMs:** messages would leave the phone.

Adopted from the review: one account-status model for every account (OK, Connecting, Login needed, Permission
needed, Stopped, Error with a plain fix), an on-device IMAP/SMTP connector (iCloud, Yahoo, Zoho, Exchange servers,
custom domains), and a TDLib user client for Telegram.

## Decisions still open

1. **Meta business webhooks.** Arch s4 rejects a relay because the server sees plaintext briefly. The study's
   sealed-box relay narrows this but does not remove it. Decide before #10/#11 ship sync (API send within
   24h works without webhooks).
2. **AGPL** for libgm / mautrix-meta / mautrix-linkedin (see Product decision).
   A remote Matrix homeserver/bridge stays excluded under the phone-local rule.
3. **Telegram:** TDLib replaces or sits alongside the bot connector.
