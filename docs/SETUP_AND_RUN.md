# Setup and run

## Supported components today

| Component | Platform | Status |
|---|---|---|
| TypeScript domain reference + tests | Node 20+ on Windows/macOS/Linux | Runs |
| Web hub harness (synthetic data, simulated provider) | Any modern browser via Vite | Runs |
| Publisher skeleton | Node | Runs locally, not deployed |
| Kotlin Multiplatform core + Android app (`native/`) | JDK 17, Android SDK 34/35 | Being built, see below |
| iOS app | macOS + Xcode | Not buildable in the current Windows environment |

## Web harness

```cmd
cd C:\Users\nande\Downloads\Mini
npm install
npm run typecheck
npm test
npm run build
npm run dev
```

Open the URL Vite prints (normally http://localhost:5173). All accounts and messages are synthetic. Provider
dispatch in the harness is simulated and never contacts Gmail or WhatsApp.

## Native (in progress)

Once `native/` lands:

```cmd
cd C:\Users\nande\Downloads\Mini\native
gradlew.bat :shared:jvmTest
gradlew.bat :androidApp:assembleDebug
```

`native/local.properties` must contain `sdk.dir` pointing at the Android SDK
(for example `C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`). It is not committed.

This file is updated as components become runnable.
