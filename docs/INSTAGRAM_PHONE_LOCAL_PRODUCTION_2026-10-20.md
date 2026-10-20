# Instagram phone-local production wiring (20 Oct 2026)

## Scope

This package connects the tested phone-local Instagram login/read/foreground-receive
path into MINI Manage Accounts and Inbox. It reuses the existing
`InstagramLoginActivity` / encrypted session store / bounded `LoadIndex` reader.

It does **not**:

- silently import credentials or observations from `app.mini.hub.rtprobe`
- enable Instagram sending or change qualification gates
- claim background receiving
- install over `app.mini.hub` by itself

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

- Foreground receiving starts when MainActivity resumes with a connected
  Instagram phone-local account and stops on pause. Interval/backoff match the
  tested rtprobe receiver. Overlapping reads are locked.
- Background receiving remains unimplemented and unqualified.
- Gateway rejects phone-local Instagram sends with
  `instagram_phone_local_send_disabled`. Inbox forces native handoff for that
  route. Qualification gates are unchanged.

## Focused checks already expected

- `ConnectRoutePolicyTest` includes Instagram phone-local in debug visibility.
- `InstagramDispatchWindowTest.phoneLocalRouteRejectsSendBeforeProvider`.
- Existing Instagram read/signature device tests remain valid for the runtime.

## Device verification before installing over app.mini.hub

Build only:

```bat
cd native
gradlew.bat :androidApp:assembleDebug :androidApp:testDebugUnitTest :shared:jvmTest --tests app.mini.hub.connector.InstagramDispatchWindowTest --tests app.mini.hub.android.connector.ConnectRoutePolicyTest
```

Do **not** run `adb install` over `app.mini.hub` until the owner accepts the APK.

Owner verification after a deliberate install:

1. Confirm existing Gmail/Outlook/WhatsApp accounts, drafts and observations still open.
2. Manage Accounts shows **Instagram personal (not live-qualified)**.
3. Connect, complete owner sign-in, tap Continue.
4. Inbox shows recent Instagram conversations/message text for that account.
5. Keep MINI foregrounded, send a new Instagram message from another device, wait
   up to ~30s, confirm it appears without tapping Sync.
6. Tap Sync again: no duplicates.
7. Force-stop/reopen MINI: messages remain, no duplicates, send still disabled.
8. Background receiving is not claimed if the app is not visible.
