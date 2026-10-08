param(
    [string]$DeviceSerial = "56270DLCH000KZ",
    [int]$ActiveDurationSec = 1800,
    [int]$CooldownDurationSec = 600,
    [int]$SampleIntervalSec = 60,
    [string]$OutputDir = "$PSScriptRoot\output",
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Continue"

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$MetricsCsv = Join-Path $OutputDir "endurance-metrics.csv"
$EventsCsv = Join-Path $OutputDir "endurance-events.csv"
$StatusJson = Join-Path $OutputDir "endurance-status.json"
$ErrorsLog = Join-Path $OutputDir "endurance-errors.log"
$RunnerPid = $PID

# Initialize CSVs
if (-not (Test-Path $MetricsCsv)) {
    "timestamp,elapsed_sec,phase,pid,total_pss_kb,java_heap_kb,native_heap_kb,graphics_kb,cpu_pct,event_count" | Out-File -FilePath $MetricsCsv -Encoding utf8
}
if (-not (Test-Path $EventsCsv)) {
    "timestamp,event_id,event_type,sender,text_length,status,latency_ms" | Out-File -FilePath $EventsCsv -Encoding utf8
}

function Log-Error([string]$msg) {
    $ts = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    "[$ts] $msg" | Out-File -FilePath $ErrorsLog -Append -Encoding utf8
}

function Update-Status([string]$state, [int]$elapsed, [int]$totalSent, [int]$totalSucceeded, [int]$totalFailed, [string]$notes) {
    $obj = [ordered]@{
        state = $state
        runner_pid = $RunnerPid
        target_device = $DeviceSerial
        start_time = $script:StartTime.ToString("yyyy-MM-ddTHH:mm:sszzz")
        last_heartbeat = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
        elapsed_sec = $elapsed
        total_duration_sec = ($ActiveDurationSec + $CooldownDurationSec)
        total_events_sent = $totalSent
        total_events_succeeded = $totalSucceeded
        total_events_failed = $totalFailed
        notes = $notes
    }
    $obj | ConvertTo-Json -Depth 4 | Out-File -FilePath $StatusJson -Encoding utf8
}

function Get-AppMemory([string]$serial) {
    $raw = & $AdbPath -s $serial shell dumpsys meminfo app.mini.hub 2>&1
    $totalPss = 0; $javaHeap = 0; $nativeHeap = 0; $graphics = 0

    foreach ($line in ($raw -split "`n")) {
        if ($line -match "TOTAL\s+PSS:\s+(\d+)") {
            $totalPss = [int]$matches[1]
        } elseif ($line -match "TOTAL\s+(\d+)\s+(\d+)") {
            if ($totalPss -eq 0) { $totalPss = [int]$matches[1] }
        } elseif ($line -match "Java Heap:\s+(\d+)") {
            $javaHeap = [int]$matches[1]
        } elseif ($line -match "Native Heap:\s+(\d+)") {
            $nativeHeap = [int]$matches[1]
        } elseif ($line -match "Graphics:\s+(\d+)") {
            $graphics = [int]$matches[1]
        }
    }
    return @{ TotalPss = $totalPss; JavaHeap = $javaHeap; NativeHeap = $nativeHeap; Graphics = $graphics }
}

function Get-AppCpu([string]$serial) {
    $raw = & $AdbPath -s $serial shell top -b -n 1 2>&1
    foreach ($line in ($raw -split "`n")) {
        if ($line -match "app\.mini\.hub") {
            $parts = $line.Trim() -split "\s+"
            if ($parts.Length -ge 9) {
                # Look for percentage token
                foreach ($part in $parts) {
                    if ($part -match "^\d+\.\d+$") {
                        return [double]$part
                    }
                }
            }
        }
    }
    return 0.0
}

function Get-AppPid([string]$serial) {
    $res = & $AdbPath -s $serial shell pidof app.mini.hub 2>&1
    return $res.Trim()
}

$script:StartTime = Get-Date
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$TotalDurationSec = $ActiveDurationSec + $CooldownDurationSec

Update-Status "RUNNING" 0 0 0 0 "Endurance runner initialized"

$eventsSent = 0
$eventsSucceeded = 0
$eventsFailed = 0
$lastSampleElapsed = -1
$eventIndex = 1

while ($stopwatch.Elapsed.TotalSeconds -lt $TotalDurationSec) {
    $elapsedSec = [int]$stopwatch.Elapsed.TotalSeconds
    $isCooldown = $elapsedSec -ge $ActiveDurationSec
    $currentPhase = if ($isCooldown) { "COOLDOWN" } else { "ACTIVE" }

    # Check device online
    $devicesRaw = (& $AdbPath devices 2>&1) -join "`n"
    if ($devicesRaw -notmatch $DeviceSerial) {
        Log-Error "Device $DeviceSerial disconnected at elapsed ${elapsedSec}s"
        Update-Status "INTERRUPTED" $elapsedSec $eventsSent $eventsSucceeded $eventsFailed "Device disconnected during test"
        break
    }

    # Workload generation in ACTIVE phase (every 30s)
    if (-not $isCooldown -and ($elapsedSec % 30 -eq 0) -and ($elapsedSec -ne $lastWorkloadSec)) {
        $lastWorkloadSec = $elapsedSec
        $eventId = "endurance_evt_$eventIndex"
        $eventIndex++
        $sender = switch ($eventIndex % 4) {
            0 { "Client Team" }
            1 { "Ops Lead" }
            2 { "X Alert" }
            3 { "WHOOP Service" }
        }
        $body = "Synthetic endurance test message $eventId at ${elapsedSec}s"
        
        $evSw = [System.Diagnostics.Stopwatch]::StartNew()
        $res = & $AdbPath -s $DeviceSerial shell am broadcast -a app.mini.hub.POST_SYNTHETIC_NOTIFICATION -p app.mini.hub --es sender "`"$sender`"" --es text "`"$body`"" --ei id ($eventIndex + 8000) 2>&1
        $evSw.Stop()
        $lat = $evSw.ElapsedMilliseconds

        $eventsSent++
        if ($res -match "result=0") {
            $eventsSucceeded++
            "$((Get-Date).ToString('s')),$eventId,POST,$sender,$($body.Length),SUCCESS,$lat" | Out-File -FilePath $EventsCsv -Append -Encoding utf8
        } else {
            $eventsFailed++
            Log-Error "Failed to broadcast ${eventId} - $res"
            "$((Get-Date).ToString('s')),$eventId,POST,$sender,$($body.Length),FAILED,$lat" | Out-File -FilePath $EventsCsv -Append -Encoding utf8
        }

        # Background app periodically
        if ($eventIndex % 6 -eq 0) {
            & $AdbPath -s $DeviceSerial shell input keyevent KEYCODE_HOME 2>&1 | Out-Null
        } elseif ($eventIndex % 6 -eq 3) {
            & $AdbPath -s $DeviceSerial shell am start -n app.mini.hub/app.mini.hub.android.MainActivity 2>&1 | Out-Null
        }
    }

    # Sample metrics every SampleIntervalSec
    if ($elapsedSec -ge ($lastSampleElapsed + $SampleIntervalSec)) {
        $lastSampleElapsed = $elapsedSec
        $appPid = Get-AppPid $DeviceSerial
        $mem = Get-AppMemory $DeviceSerial
        $cpu = Get-AppCpu $DeviceSerial

        $ts = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ss")
        "$ts,$elapsedSec,$currentPhase,$appPid,$($mem.TotalPss),$($mem.JavaHeap),$($mem.NativeHeap),$($mem.Graphics),$cpu,$eventsSent" | Out-File -FilePath $MetricsCsv -Append -Encoding utf8

        Update-Status "RUNNING" $elapsedSec $eventsSent $eventsSucceeded $eventsFailed "Phase: $currentPhase, PSS: $($mem.TotalPss) KB, Events: $eventsSent"
    }

    Start-Sleep -Seconds 2
}

$finalElapsed = [int]$stopwatch.Elapsed.TotalSeconds
if ($finalElapsed -ge $TotalDurationSec) {
    Update-Status "PASSED" $finalElapsed $eventsSent $eventsSucceeded $eventsFailed "40-minute endurance run completed successfully"
} else {
    Update-Status "INTERRUPTED" $finalElapsed $eventsSent $eventsSucceeded $eventsFailed "Run stopped early at ${finalElapsed}s"
}

# Auto-generate summary report
$AnalyzeScript = Join-Path $PSScriptRoot "analyze-endurance.ps1"
if (Test-Path $AnalyzeScript) {
    & $AnalyzeScript -OutputDir $OutputDir
}
