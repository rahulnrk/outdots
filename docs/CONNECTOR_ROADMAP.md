# Connector roadmap

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
| 1 | **Gmail** | A | Implemented, device-verified sync | Live send qualification, reboot-without-launch sync, restricted-scope verification prep | Google OAuth verification; CASA only if a server touches mail |
| 2 | **Outlook.com + Microsoft 365** | A | Fixture-tested, release-offered | Live connect/sync/send on device, 410 resync on device, tenant-consent error UX | Entra app registration, publisher verification |
| 3 | **Android inline reply (RemoteInput)** | B | Notification capture qualified; replies use handoff | Cache reply actions from child notifications per package (`com.whatsapp` vs `.w4b`), bind approval to the action, downgrade to handoff when the action is gone, "submitted, outcome unverified" receipt | None (no external review) |
| 4 | **Google Messages SMS/RCS** | B | Source exists, not default | Enable via notification + inline reply, `smsto:` for new threads; never request READ_SMS | None |
| 4b | **LinkedIn personal** | B | Notification conversations, activity feed and acknowledge actions landed (commits `2a51f79`..`25ee51c`); handoff send | Inline reply through the notification (#3), device run, coverage banner. Official Messaging API is partner-only (SNAP/RSC); no session-scraping vendors | None for Tier B |
| 5 | **Chat export import** | B | Not started | Share-intent receiver for WhatsApp `.zip`/`_chat.txt`, then Telegram JSON; dedupe with captured history; mark as imported coverage | None |
| 6 | **Slack** | A | Fixture-tested, debug only | Live workspace run, rate-limit backoff, 90-day free-tier coverage label | Slack app distribution |
| 7 | **Teams (work/school)** | A | Fixture-tested, debug only | Live tenant run, admin-consent link, automatic fallback to Tier B | Tenant admin consent |
| 8 | **Google Chat** | A | Fixture-tested, debug only | Live Workspace run | Workspace admin allowlist |
| 9 | **Telegram personal (TDLib)** | A | Bot API only | Add TDLib user client (api_id/api_hash, OTP/QR login, encrypted TDLib DB); keep bot route for business | Telegram api_id registration |
| 10 | **Instagram Professional** | A | Adapter exists (24h window) | Live run, App Review, webhook decision (see below) | Meta App Review, Business verification |
| 11 | **Messenger Pages / WhatsApp Business Cloud** | A | Catalog only | Decide webhook route; adapter | Meta review + privacy decision on webhooks |
| 12 | **X DMs** | A | Fixture-tested, debug only | Paid-tier add-on, budget guard | Paid X API credits |
| 13 | **WhatsApp unofficial on-device** | C | whatsmeow packaged (debug, arm64) | Opt-in "advanced" only, ban-risk disclosure, no automated sends, reconnect after process death, 14-day linked-device expiry warning | Product risk decision (see below) |
| 14 | **Instagram/Messenger/LinkedIn unofficial on-device** | C | Not started | Only after #13 runs cleanly for weeks; needs session-cookie handling decision | Product risk decision; AGPL if mautrix-meta/mautrix-linkedin is used |
| 15 | iOS slice, Windows companion | - | Blocked / not scoped | After Android connectors are stable | macOS for iOS; Windows needs a platform decision |

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

## Decisions still open

1. **Meta business webhooks.** Arch s4 rejects a relay because the server sees plaintext briefly. The study's
   sealed-box relay narrows this but does not remove it. Decide before #10/#11 ship sync (API send within
   24h works without webhooks).
2. **Tier C (unofficial clients).** Allowed as opt-in advanced connections on the phone only, or not shipped.
   A remote Matrix homeserver/bridge stays excluded under the phone-local rule.
3. **Telegram:** TDLib replaces or sits alongside the bot connector.
