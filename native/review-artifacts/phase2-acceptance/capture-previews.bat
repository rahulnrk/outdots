@echo off
setlocal
set ADB=C:\Users\nande\AppData\Local\Android\Sdk\platform-tools\adb.exe
set SERIAL=56270DLCH000KZ
set OUT=C:\Users\nande\Downloads\Mini\native\review-artifacts\phase2-acceptance
set COMP=app.mini.hub/app.mini.hub.android.ui.PreviewExportActivity
if not exist "%OUT%" mkdir "%OUT%"

call :cap inbox 0 1.0 inbox-light
call :cap inbox 1 1.0 inbox-dark
call :cap inbox 0 1.3 inbox-large
call :cap focus 0 1.0 focus-light
call :cap focus 1 1.0 focus-dark
call :cap focus 0 1.3 focus-large
call :cap console 0 1.0 console-light
call :cap console 1 1.0 console-dark
call :cap console 0 1.3 console-large
call :cap activity 0 1.0 activity-light
call :cap activity 1 1.0 activity-dark
call :cap activity 0 1.3 activity-large
echo ALL_CAPTURES_DONE
exit /b 0

:cap
%ADB% -s %SERIAL% shell am force-stop app.mini.hub
%ADB% -s %SERIAL% shell am start -W -n %COMP% --es surface %1 --ez night %2 --ef font %3
%ADB% -s %SERIAL% shell sleep 2
%ADB% -s %SERIAL% shell screencap -p /data/local/tmp/mini-preview.png
%ADB% -s %SERIAL% pull /data/local/tmp/mini-preview.png "%OUT%\%4.png"
echo captured %4
exit /b 0
