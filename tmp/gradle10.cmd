@echo off
rem Runs one Gradle command from native\ with a 10-minute wall-clock limit.
rem Gradle is started via ShellExecute (no handle inheritance) so the warm daemon
rem cannot hold the caller's output pipe open after the build ends.
rem Usage: tmp\gradle10.cmd <gradle args...>
setlocal
set "MINI_NATIVE=%~dp0..\native"
set "MINI_ARGS=%*"
powershell -NoProfile -Command "$sw=[Diagnostics.Stopwatch]::StartNew(); $log=\"$env:TEMP\mini_gradle.log\"; Remove-Item $log -ErrorAction SilentlyContinue; $p=Start-Process -FilePath cmd.exe -ArgumentList ('/c gradlew.bat ' + $env:MINI_ARGS + ' > \"' + $log + '\" 2>&1') -WorkingDirectory $env:MINI_NATIVE -WindowStyle Hidden -PassThru; if(-not $p.WaitForExit(600000)){ & taskkill /F /T /PID $p.Id | Out-Null; Get-Content $log -Tail 30; Write-Output ('GRADLE_TIMEOUT after {0:n0}s' -f $sw.Elapsed.TotalSeconds); exit 124 }; Get-Content $log | Select-String -Pattern 'FAILED|tests completed|BUILD|^e: |AssertionError|Exception' | ForEach-Object { $_.Line } | Select-Object -First 40; Write-Output ('GRADLE_EXIT={0} wall={1:n0}s' -f $p.ExitCode, $sw.Elapsed.TotalSeconds); exit $p.ExitCode"
