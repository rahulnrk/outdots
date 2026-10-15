@echo off
rem Debug-only Phase 3A interactive check: clear the editor through the real IME while a fake generation is blocked.
setlocal
set A=%~dp0..\..\..\tmp\adb.cmd
set C=app.mini.hub/.android.ui.Phase3AWorkflowHarnessActivity
set OUT=%~dp0
call %A% shell input keyevent 224
call %A% shell wm dismiss-keyguard
call %A% logcat -c
call %A% shell am start -W -n %C% --es step interactive-arm >nul
ping -n 4 127.0.0.1 >nul
rem Focus the composer field, then delete every character with the keyboard.
call %A% shell input tap 615 2060
ping -n 3 127.0.0.1 >nul
call %A% shell input keyevent KEYCODE_MOVE_END
call %A% shell input keyevent KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL
ping -n 2 127.0.0.1 >nul
call %A% shell screencap -p /data/local/tmp/h.png
call %A% pull /data/local/tmp/h.png "%OUT%device-keyboard-cleared-in-flight.png" >nul
call %A% shell am start -W -n %C% --es step interactive-release >nul
ping -n 4 127.0.0.1 >nul
call %A% shell screencap -p /data/local/tmp/h.png
call %A% pull /data/local/tmp/h.png "%OUT%device-cleared-after-result.png" >nul
call %A% logcat -d -s MiniPhase3AHarness
