@echo off
rem Usage: live.cmd <testMethod> [accountA localId] [subject]
rem Does not clear logcat, so user-driven events (disconnect taps, callbacks) are never lost.
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set EXTRA=
if not "%2"=="" set EXTRA=-e accountA %2
if not "%~3"=="" set EXTRA=%EXTRA% -e subject "%~3"
"%ADB%" -s 56270DLCH000KZ shell am instrument -w %EXTRA% -e class app.mini.hub.android.GmailReliabilityDeviceTest#%1 app.mini.hub.test/androidx.test.runner.AndroidJUnitRunner
echo ---- privacy-safe log (last 15 lines) ----
"%ADB%" -s 56270DLCH000KZ logcat -d -t 15 -s MiniGmailLive:* MiniGmailSync:* MiniGmailOAuth:*
