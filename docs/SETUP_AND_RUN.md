# Setup and run

| Component | Requirements | Status |
|---|---|---|
| Android app + KMP core (`native/`) | JDK 17, Android SDK | Builds and runs; see status doc for qualification |
| TypeScript domain reference + tests | Node 20+ | Runs |
| Web demo (`apps/hub`) | Browser via Vite | Runs over synthetic data only |
| Publisher skeleton | Node | Runs locally, not deployed |
| iOS | macOS + Xcode | Not buildable on Windows |

## Verify everything

```cmd
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1
```

Runs `npm test`, `npm run typecheck`, then `gradlew.bat :shared:jvmTest :androidApp:testDebugUnitTest`
in `native/`, and exits non-zero on any failure. There is no hosted CI.

## Native

`native/local.properties` (gitignored) needs `sdk.dir`, e.g. `C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.

```cmd
cd native
gradlew.bat :shared:jvmTest
gradlew.bat :androidApp:testDebugUnitTest
gradlew.bat :androidApp:assembleDebug
```

OAuth client IDs are public IDs supplied at build time with `-Pmini.googleOAuthClientId=...`
(also `mini.outlookOAuthClientId`, `mini.slackClientId`, `mini.xClientId`) or in
`native/local.properties`. Never add a client secret. The Google ID sets the manifest redirect
scheme, so changing it requires a rebuild.

The on-device model `qwen2.5-0.5b-instruct-q8.task` is not in the repo and not downloaded by the
app. Push it to the app's external `files/models/` directory.

### Device tests

`androidApp/src/androidTest` needs a physical device (and connected accounts for Gmail tests):

```cmd
adb install -r <apk>
adb shell am instrument -w -e class <Class>#<method> app.mini.hub.test/androidx.test.runner.AndroidJUnitRunner
```

Do **not** use `connectedDebugAndroidTest`. It uninstalls the app and wipes stored authorizations.

## Web demo

```cmd
npm install
npm run dev
```

Opens at http://127.0.0.1:5173. All accounts and messages are synthetic and dispatch is simulated.
