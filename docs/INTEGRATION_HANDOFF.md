# Integrated application handoff

Updated 2026-10-13. This is the master-prompt integration of:

- **Project A:** `native/` Mini Hub (KMP core + Android Compose app). This is the production backend and the only live runtime.
- **Project B:** `C:\Users\nande\Downloads\design test` Paper & ink design study (HTML/JS prototype, 28 screens). Visual and flow source of truth. No live providers.

The TypeScript `apps/hub` demo remains a synthetic reference. It is not the Android runtime.

## Architecture (actual)

```
MainActivity (FLAG_SECURE)
  MiniHubRuntime (SQLCipher + Android Keystore)
    connectors, observation store, attention store, style engine, local draft experiment
  InboxViewModel (real state: conversations, drafts, exact review payloads, send)
  HubApp (Paper & ink navigation)
    Welcome / Sources / Permission / Ready
    Inbox / Filters / Search
    Focus / Later
    Thread (ChatUi.ThreadView + compose fields)
    Context / Review / Handoff
    Accepted / Opened / Unknown / Unavailable
    Console / Health / Model / Style / Privacy / Accessibility
  Connect dialogs (OAuth, tokens, WhatsApp pairing) remain Project A surfaces
```

Personal content and inference stay on-device. Exact review still binds account, recipients, body, grants, and source revisions before `approveAndDispatch`. Notification routes remain handoff-only.

## Feature reconciliation

| Feature | Backend | Basic UI | Polished UI | Gap | Action | Test |
|---|---|---|---|---|---|---|
| Encrypted store | Fully implemented | Blocking failure screen | Storage screen | Polished storage screen not a live route | Keep Project A fail-closed screen | RuntimeStorageTest |
| Unified inbox | Fully implemented | List | Inbox rows | Visual | HubApp InboxDestination filters live summaries | InboxViewModelTest |
| Search | Fully implemented | Inbox field | Search screen | Polished search now calls ViewModel | HubApp SearchDestination | InboxViewModelSearchTest |
| Filters | Partial labels | Filter chips | Source+account | Unread/Work/Personal now filter live list | accountFilter | compile |
| Attention | Fully implemented | Thread menu | Focus + Later | Wired to AttentionAction | FocusDestination | Attention rules tests |
| Local draft | Partial (model file optional) | Composer | Draft card | Same ViewModel | ThreadView + requestLocalDraft | InboxViewModelTest |
| Exact review | Fully implemented | Dialog | Full-screen | Dialog overlay disabled; HubApp Review/Handoff | ReviewScreen approveAndDispatch | AuthorizationBindingTest, SendDispatchTest |
| API send | Gated | Dialog send | Accepted | Outcome routed from outbox message | HubApp LaunchedEffect | SendDispatchTest |
| Handoff | Fully implemented | Dialog | Handoff/Opened | Route != Api opens Handoff | HubApp | device-qualified previously |
| Gmail/Outlook connect | Fully implemented | Accounts dialog | Sources/Ready | Ready tap still launches Project A OAuth | onConnect | device |
| WhatsApp notify | Fully implemented | Listener | Health/paused | Health uses isListenerConnected | HealthScreen | HeadlessNotificationLifecycleTest |
| WhatsApp link | Debug packaged, not live-qualified | Pairing dialog | Console | Dialog kept; not claimed live | Accounts dialog | packaging docs |
| Style learning | Opt-in, default off | Learning dialog | Style screen | StyleScreen calls onStyleLearningChanged | StyleScreen | AdaptiveStyleEngineTest |
| On-device model | Partial, file not in repo | Hidden | Model screen | Honest "not installed"; no fake download | ModelScreen | status doc |
| iOS | Blocked | None | None | No macOS | Recorded | n/a |

## Remaining limitations

- Paper & ink onboarding is a first-run UX over existing source preferences. Provider sign-in is still the Android/Google/Microsoft screens.
- Unqualified connectors stay debug-only (`ConnectRoutePolicy`).
- Live Gmail send remains behind owner-recorded qualification. The Accepted screen is shown only when the outbox reports `AcceptedByProvider`.
- No Playwright/UI Automator coverage of HubApp screens yet.
- iOS is not buildable here.

## Run

```cmd
cd native
gradlew.bat :shared:jvmTest
gradlew.bat :androidApp:testDebugUnitTest
gradlew.bat :androidApp:installDebug
adb shell am start -n app.mini.hub/app.mini.hub.android.MainActivity
```

Do not run `connectedDebugAndroidTest` (it uninstalls the app and wipes authorizations).
