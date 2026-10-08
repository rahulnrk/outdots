param(
    [string]$OutputDir = "$PSScriptRoot\output"
)

$StatusJson = Join-Path $OutputDir "endurance-status.json"
$MetricsCsv = Join-Path $OutputDir "endurance-metrics.csv"

if (-not (Test-Path $StatusJson)) {
    Write-Host "No endurance test status found at $StatusJson" -ForegroundColor Yellow
    exit 0
}

$status = Get-Content $StatusJson -Raw | ConvertFrom-Json
$procAlive = $false
if ($status.runner_pid) {
    $p = Get-Process -Id $status.runner_pid -ErrorAction SilentlyContinue
    if ($p) { $procAlive = $true }
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "   MINI ANDROID 40-MIN ENDURANCE RUNNER STATUS   " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
$color = if ($status.state -eq "PASSED") { "Green" } elseif ($status.state -eq "RUNNING") { "Yellow" } else { "Red" }
Write-Host "State:           $($status.state)" -ForegroundColor $color
Write-Host "Runner PID:      $($status.runner_pid) (Alive: $procAlive)"
Write-Host "Target Device:   $($status.target_device)"
Write-Host "Start Time:      $($status.start_time)"
Write-Host "Last Heartbeat:  $($status.last_heartbeat)"
Write-Host "Elapsed Time:    $($status.elapsed_sec)s / $($status.total_duration_sec)s ($([math]::Round($status.elapsed_sec / 60.0, 1))m / 40.0m)"
Write-Host "Events Sent:     $($status.total_events_sent) (Success: $($status.total_events_succeeded), Failed: $($status.total_events_failed))"
Write-Host "Latest Notes:    $($status.notes)"

if (Test-Path $MetricsCsv) {
    $rows = Import-Csv $MetricsCsv
    Write-Host "Samples Logged:  $($rows.Count)"
    if ($rows.Count -gt 0) {
        $last = $rows[-1]
        Write-Host "Latest Total PSS: $([math]::Round([int]$last.total_pss_kb / 1024.0, 1)) MB ($($last.total_pss_kb) KB)"
        Write-Host "Latest Heap:     Java: $([math]::Round([int]$last.java_heap_kb / 1024.0, 1)) MB, Native: $([math]::Round([int]$last.native_heap_kb / 1024.0, 1)) MB"
        Write-Host "Latest CPU%:     $($last.cpu_pct)%"
    }
}
Write-Host "==================================================" -ForegroundColor Cyan
