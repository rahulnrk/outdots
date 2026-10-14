# Phase 2 acceptance evidence (2026-10-14)

Device: Pixel `56270DLCH000KZ`. Every build installed with `adb install -r` over existing data.
No uninstall, no DB reset, no live send, no release-gate change, no Phase 3 work.
MainActivity keeps FLAG_SECURE (`MainActivity.kt:40`). The preview exporter is debug-only (`src/debug/AndroidManifest.xml`).
No private message text is retained. Device readback used allow-listed UI labels and synthetic probe strings only, and UI dumps were deleted on the device right after reading.

## 1. Source tests
`:shared:jvmTest` + `:androidApp:testDebugUnitTest`: 56 suites, 312 tests, 0 failures, 0 errors. Includes:
- `MiniPhoneStatusTest`: listener disconnect reads "Reconnecting", not account setup. Permission off and unhealthy accounts are reported separately. Only zero accounts reads "Needs setup". Action history orders uncertain outcomes first.
- `InboxScopeFilterTest`, classification tests (from `c03b5c2`).

## 2. Real database tests
- `ObservationRecordTest.notificationCategorySurvivesJdbcCloseReopenAndSummaryRebuild` (JDBC close/reopen).
- `UnifiedSearchTest.groupedFtsQueryRejectsSnippetButAcceptsAndroidShape` (new, real SQLite FTS5). This proves that the old Android search SQL fails and the fixed shape returns one grouped conversation.
- On-device v4 data: accounts store, Gmail OAuth session, seen watermarks and the observations/attention/actions DBs are all present after the installs. Console showed 2 Gmail + 1 WhatsApp Connected.

## 3. Device workflows (Pixel, existing data)
Evidence label **SYNTHETIC**: repeat/new-message checks used debug probes (`AcceptP2Probe`, id 4343, package `app.mini.hub`) only.

| Check | Result |
|---|---|
| Search "acceptp2", open result, Mark done, back without retyping | PASS. Query kept, active results "No matching conversations.", Done filter lists it |
| Identical repeat (same id/text/post time), no restart (SYNTHETIC) | PASS. Revision stays 1, row stays out of active Inbox |
| Identical repeat after force-stop + relaunch (SYNTHETIC) | PASS. Revision stays 1, still hidden |
| Genuinely new text on same thread (SYNTHETIC) | PASS. Revision 2, row back in active Inbox with "Needs you" |
| Focus Done / Undo / restart (earlier run) | PASS. 174 -> 173, Undo -> 174, Done + force-stop -> 173 |
| Saved new-email draft after force-stop (synthetic recipient `acceptp2@example.com`) | PASS. Listed after restart. Opened with To, Subject, Body and `From: <connected Gmail>` restored. Not sent. Removed afterwards via the app's empty-draft path |
| v4 survival of accounts/drafts/history | PASS. Stores present, accounts Connected, Focus history intact |

### Bugs found and fixed during acceptance
- **On-device search returned nothing for every query.** `SqlCipherObservationStore.search` used `snippet()` inside `GROUP BY`, which SQLite rejects. The exception was swallowed. Fixed, and the failure type is now logged without query text. Regression test added.
- **Debug synthetic probes were invisible in Inbox/search** (self package not a selected source). The debug build alone now passes MINI's own package. Release builds pass nothing.
- **Restored new-email draft did not show which Gmail account it would send from**, though two are connected. Now shows `From:`.

## 4. Cold-start status (listener / permission / accounts)
Recorded separately:
- Notification permission: granted (`enabled_notification_listeners` includes MINI).
- Listener: after force-stop the system logged connection lost at 13:05:52.345 and service connected at 13:05:54.320. MINI logged `listener connected` at 13:05:54.680 (about 2.3 s). The header read "On this phone" in all five samples (2 s apart) and still after another 30 s, with no further disconnects.
- Accounts: Console health "Healthy", 3 Connected.
- Header now derives from these separately. A disconnected listener shows "Reconnecting" (banner: "Connected accounts are unaffected"), never "Needs setup". "Needs setup" appears only with zero accounts. The listener service also requests a rebind on disconnect and pushes state to the UI on connect.
- Earlier observation (before this build): while the phone was locked, the system repeatedly unbound and rebound the listener ("binding died" every ~5 s, then "not rebinding ... previous rebind attempt"). The old header showed "Needs setup" then. Whether that pattern predates Phase 2 is **not verified**.

## 5. Rendered visual checks (synthetic previews, re-exported and inspected)
15 captures in this folder: inbox/focus/console/activity at light/dark/1.3x, plus `activity-xlarge` (2.0x), `activity-dark-large`, `inbox-xlarge` (2.0x).
- Activity now uses the production host (MiniTheme > Scaffold > background), with the header included. Dark mode: correct dark background and light text (`activity-dark.png`, `activity-dark-large.png`). PASS.
- Activity actions wrap with a minimum touch height. "Open in LinkedIn" is fully readable and tappable at 1.3x and 2.0x. PASS.
- Action history is now a single compact strip. The uncertain outcome comes first, with "Check in source app" and "History (n)" to expand. Draft cards are one line plus recipient. At 1.3x the Inbox shows the strip, draft and both conversations above the bottom bar. PASS at 1.0x and 1.3x.

## Remaining limitations
- At 2.0x text the pinned header, strip and draft still push the conversation list mostly below the fold (header labels "Latest conversations / 2 conversations" are visible, rows need a scroll). Acceptable for now, noted for later.
- Repeat/new-message evidence is synthetic, not a real WhatsApp/Gmail message.
- Locked-device listener churn origin (pre-Phase 2 or not) is unverified. On an unlocked device the listener reconnected in about 2.3 s.
- Search results have no inline Done action. Done is taken from the opened conversation's menu.
- No instrumented tests (`connectedDebugAndroidTest` wipes authorizations).
