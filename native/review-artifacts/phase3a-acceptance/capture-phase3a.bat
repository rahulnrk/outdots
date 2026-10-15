@echo off
setlocal
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set SERIAL=56270DLCH000KZ
set OUT=%~dp0
set COMP=app.mini.hub/app.mini.hub.android.ui.PreviewExportActivity

if not exist "%OUT%" mkdir "%OUT%"

echo Starting Phase 3A fixture export...

call :cap thread false 1.0 thread-light
call :cap thread true 1.0 thread-dark
call :cap thread false 1.3 thread-large
call :cap thread false 2.0 thread-xlarge
call :cap generating false 1.0 thread-generating
call :cap manual false 1.0 thread-manual
call :cap evidence false 1.0 evidence-light
call :cap evidence true 1.0 evidence-dark
call :cap evidence false 1.3 evidence-large
call :cap evidence false 2.0 evidence-xlarge
call :cap unavailable false 1.0 evidence-unavailable

echo ALL_PHASE3A_CAPTURES_DONE
exit /b 0

:cap
echo Capturing %4 (surface=%1 night=%2 font=%3)...
%ADB% -s %SERIAL% shell am start -W -n %COMP% --es surface %1 --ez night %2 --ef font %3 --el ts %RANDOM%
%ADB% -s %SERIAL% shell sleep 2
%ADB% -s %SERIAL% shell screencap -p /data/local/tmp/mini-preview.png
%ADB% -s %SERIAL% pull /data/local/tmp/mini-preview.png "%OUT%\%4.png"
echo Done: %4.png
exit /b 0
