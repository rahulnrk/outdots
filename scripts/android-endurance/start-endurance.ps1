param(
    [string]$DeviceSerial = "56270DLCH000KZ",
    [int]$ActiveDurationSec = 1800,
    [int]$CooldownDurationSec = 600,
    [int]$SampleIntervalSec = 60
)

$OutputDir = "$PSScriptRoot\output"
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$StatusJson = Join-Path $OutputDir "endurance-status.json"
if (Test-Path $StatusJson) {
    try {
        $existing = Get-Content $StatusJson -Raw | ConvertFrom-Json
        if ($existing.state -eq "RUNNING" -or $existing.state -eq "COOLDOWN") {
            $runnerProc = Get-Process -Id $existing.runner_pid -ErrorAction SilentlyContinue
            if ($runnerProc) {
                Write-Host "Endurance test is ALREADY RUNNING (PID: $($existing.runner_pid), Elapsed: $($existing.elapsed_sec)s)" -ForegroundColor Yellow
                exit 0
            }
        }
    } catch {}
}

# Ensure log files are fresh
$RunnerScript = Join-Path $PSScriptRoot "run-endurance.ps1"
$logOut = Join-Path $OutputDir "runner-stdout.log"
$logErr = Join-Path $OutputDir "runner-stderr.log"

$argList = "-NoProfile -ExecutionPolicy Bypass -File `"$RunnerScript`" -DeviceSerial `"$DeviceSerial`" -ActiveDurationSec $ActiveDurationSec -CooldownDurationSec $CooldownDurationSec -SampleIntervalSec $SampleIntervalSec -OutputDir `"$OutputDir`""

$proc = Start-Process powershell -ArgumentList $argList -RedirectStandardOutput $logOut -RedirectStandardError $logErr -PassThru -WindowStyle Hidden

$trackingObj = [ordered]@{
    runner_pid = $proc.Id
    device_serial = $DeviceSerial
    launched_at = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    active_sec = $ActiveDurationSec
    cooldown_sec = $CooldownDurationSec
    status_file = $StatusJson
}
$trackingObj | ConvertTo-Json | Out-File (Join-Path $OutputDir "endurance-runner-process.json") -Encoding utf8

Write-Host "Detached 40-minute endurance runner started successfully." -ForegroundColor Green
Write-Host "Process ID: $($proc.Id)"
Write-Host "Target Device: $DeviceSerial"
Write-Host "Status Path: $StatusJson"
Write-Host "Metrics Path: $(Join-Path $OutputDir 'endurance-metrics.csv')"
