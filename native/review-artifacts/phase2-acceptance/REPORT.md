# Phase 2 acceptance evidence (2026-10-14)

Build: `c03b5c2` debug, installed with `adb install -r` on Pixel `56270DLCH000KZ`.
Nothing was uninstalled, no DB reset, no live sends, no gate changes, no Phase 3 work.
MainActivity keeps FLAG_SECURE. Private Inbox was not screenshotted. Live UI dumps were read and then deleted because they contain private message text.

## 1. Source tests
- `:shared:jvmTest` passed: classification (MessagingStyle/`msg` beats incidental "liked your post", genuine likes/jobs/X "new reply" excluded), `InboxScopeFilterTest` (shared scope for conversations, search, people, drafts, new contacts, new-email drafts ignore category/unseen).
- `:androidApp:testDebugUnitTest` passed. `assembleDebug` succeeded.

## 2. Real database tests
- `ObservationRecordTest.notificationCategorySurvivesJdbcCloseReopenAndSummaryRebuild`: category written, JDBC closed, reopened, summary rebuilt, category intact.
- Device v4 migration (non-destructive `ALTER TABLE`) ran over existing Phase 1 data: Console still lists both Gmail accounts and WhatsApp as Connected, source health Healthy, and Focus history is intact (173-174 items with prior timestamps).

## 3. Device workflows (Pixel, existing data)
| Check | Result |
|---|---|
| Search "hello", tap Done without retyping | Workflow run and dumped, but dump not parsed before deletion. Not verified |
| Focus Done | Needs you 174 -> 173, "Undo last attention change" shown |
| Undo | 173 -> 174, item restored at top |
| Done again, `am force-stop`, relaunch | Still 173, dismissed item not back |
| Identical repost (synthetic id 4242) | Posted, result not parsed. Covered by source dedupe tests only |
| New text on same thread (`phase2new`) | Posted, result not parsed. Covered by source tests only |
| Accounts after v4 | 2 Gmail + 1 WhatsApp Connected |

## 4. Rendered visual checks
12 captures from debug-only `PreviewExportActivity` (real `MiniInboxPane` / `MiniFocusPane` / `MiniConsolePane` with fixture state): inbox, focus, console, activity x light, dark, large font. Inspected: production chrome, Paper & ink type and palette, no substitute layouts.

## Remaining limitations
- Duplicate/resurface checks used a synthetic debug notification, not a real WhatsApp/Gmail message.
- After a cold restart the header showed "Needs setup" and Focus said the notification listener was idle, while Console showed all accounts Connected/Healthy. Unverified: whether this predates Phase 2, and whether the listener reconnects on its own. Under investigation.
- Drafts survival was confirmed by store presence, not by opening a specific saved draft.
- No instrumented tests (`connectedDebugAndroidTest` wipes authorizations).
