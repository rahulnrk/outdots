# Instagram phone-local production wiring (20 Oct 2026)

## Milestone status: READ CLOSED (foreground)

Phone-local Instagram **owner sign-in, bounded read ingest, Inbox visibility, and
foreground receive** are wired into `app.mini.hub` and device-verified on a Pixel 7 Pro.
This closes the Instagram **read** milestone. It does **not** close sending or
background receiving.

## Scope

This package connects the tested phone-local Instagram login/read/foreground-receive
path into MINI Manage Accounts and Inbox. It reuses the existing
`InstagramLoginActivity` / encrypted session store / bounded `LoadIndex` reader.

It does **not**:

- silently import credentials or observations from `app.mini.hub.rtprobe`
- enable Instagram sending or change qualification gates
- claim background receiving
- require uninstall/clear of `app.mini.hub` data

## Build / commits (read close-out)

Representative production wiring and defect fixes:

| Commit | Role |
|---|---|
| `9bb52dc` | Wire Instagram phone-local into Accounts and Inbox |
| `251c8ae` | Keep phone-local Instagram out of Graph API sync (false Expiry root cause) |
| `432ca82` | Adapt login Activity to foreground sync lambda |
| `3269629` | Serialize phone-local ingest and share session DB |
| `81515d4` | Show phone-local DMs under `provider.instagram` (Inbox without notification-source toggle) |
| OTP sequence `35a7c2a`…`bdce0b6` | OTP UI / field key / stale selection fixes for owner sign-in |

Installed verification used `adb install -r` only over `app.mini.hub` (no clear/uninstall).

## Device evidence (content-free)

Observed on the owner Pixel after the false-expiry fix:

| Check | Result |
|---|---|
| Accounts registry | `instagram=1` alongside preserved `gmail=2`, `whatsapp=1` |
| Console | Instagram Connected / Healthy; Reply off |
| Manual Sync | `MiniUnifiedSync ... skipped=phone_local_instagram` |
| Ingest | `MiniInstagramIngest result=success persisted=5 conversations=2` (repeat syncs stay at 5) |
| Store summaries | `pkg=provider.instagram,cat=DirectMessage,cov=PartialApiSync,evidence=true` |
| Inbox | `MiniInboxRefresh after_filters ... instagram_conversations=2` |
| Send | Reply off / `instagram_phone_local_send_disabled` path unchanged |
| WhatsApp | Still present and Healthy |

## Failures fixed during production wiring

1. **False Expiry on Sync**  
   Official `ConnectorSyncEngine` treated the placeholder `phone-local-session` token as a Graph credential and marked the account Expired. Fix: skip `PhoneLocalBridge` Instagram in the API sync engine; restore Healthy/Expired only from dedicated ingest outcomes (`InstagramPhoneLocalHealth`).

2. **Inbox hidden despite successful ingest**  
   Observations used `com.instagram.android`, so visibility required the Instagram notification-source toggle. Fix: store connected phone-local rows as `provider.instagram` (same connected-account rule as other providers).

3. **Concurrent session-store opens**  
   Foreground receive and manual sync opened separate SQLCipher handles. Fix: process-wide ingest lock + shared session DB handle.

4. **OTP / sign-in blockers** (earlier in the same arc)  
   Missing OTP UI fields, wrong OTP key (`verification_code` → `otp_code`), and stale method selection races.

## Entry points

1. **Manage Accounts → Instagram personal (not live-qualified)**  
   Debug builds offer a phone-local Instagram route. Connect launches the
   isolated `:iglogin` sign-in Activity with `EXTRA_RETURN_RESULT=true`. After
   owner authentication, Continue returns the verified account key to
   `MainActivity`, which installs a `PhoneLocalBridge` connected account only if
   this install already has an encrypted session for that account key.

2. **Inbox**  
   Bounded recent conversations/messages are ingested into the canonical
   observation store (`mini-hub-observations.db`) and shown like other providers.
   Partial history is retained as partial coverage. Manual Sync and foreground
   automatic receive both call the same ingest path.

## Schema / session notes

- No new SQL schema migration is required.
- Production uses the existing canonical observation store and the existing
  Instagram session SQLCipher+Keystore store for `app.mini.hub`.
- Because `app.mini.hub` and `app.mini.hub.rtprobe` are different application IDs,
  rtprobe credentials and message DBs are not visible to production. Owner must
  sign in again inside MINI.
- Disconnect clears the Instagram session row for that account and removes the
  connected-account credential. Stored observations remain in MINI.

## Receiving and send policy

- Foreground receiving starts when MainActivity resumes with a **Healthy**
  Instagram phone-local account and stops on pause. Interval/backoff match the
  tested rtprobe receiver. Overlapping reads are process-locked.
- Background receiving remains unimplemented and unqualified.
- Gateway rejects phone-local Instagram sends with
  `instagram_phone_local_send_disabled`. Inbox forces native handoff for that
  route. Qualification gates are unchanged.

## Focused regression coverage

- `InstagramDispatchWindowTest.phoneLocalRouteRejectsSendBeforeProvider`
- `InstagramPhoneLocalSyncHealthTest`
  - official API sync skips phone-local Instagram
  - transient ingest failures do not expire a Healthy session
  - `session_expired` requires reconnect
  - successful ingest restores Healthy
  - `provider.instagram` stays Inbox-visible without the notification-source toggle
- `ConnectRoutePolicyTest` includes Instagram phone-local in debug visibility

## Remaining gaps (tracked, not closed)

- Instagram **sending** (qualification unchanged; phone-local send stays disabled)
- Instagram **background receiving**
- Optional: reduce content-free diagnostic log verbosity after the LinkedIn arc
- Distribution / AGPL packaging for the combined Go runtime remains a release gate

## Verification commands

```bat
cd native
gradlew.bat :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin :shared:cleanJvmTest :shared:jvmTest --tests app.mini.hub.connector.InstagramPhoneLocalSyncHealthTest --tests app.mini.hub.connector.InstagramDispatchWindowTest
```

Owner device checks after `adb install -r` (no clear):

1. Existing Gmail/WhatsApp accounts and observations still open.
2. Console shows Instagram Connected/Healthy with Reply off.
3. Sync logs `skipped=phone_local_instagram` and Instagram ingest success without Expiry.
4. Inbox includes Instagram conversations (account chip / `instagram_conversations` log).
5. Foreground receive remains the only automatic arrival claim.
