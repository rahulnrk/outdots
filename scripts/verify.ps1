# Local verification for MINI. No hosted CI exists; run this before handing off changes.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\verify.ps1
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$results = @()

function Invoke-Step {
    param([string]$Name, [string]$Dir, [scriptblock]$Cmd)
    Write-Host "==> $Name" -ForegroundColor Cyan
    Push-Location $Dir
    try {
        & $Cmd
        $code = $LASTEXITCODE
    } catch {
        Write-Host $_ -ForegroundColor Red
        $code = 1
    } finally {
        Pop-Location
    }
    if ($null -eq $code) { $code = 0 }
    $script:results += [pscustomobject]@{ Step = $Name; ExitCode = $code; Passed = ($code -eq 0) }
}

Invoke-Step 'npm test' $root { npm test }
Invoke-Step 'npm run typecheck' $root { npm run typecheck }
Invoke-Step 'gradle shared:jvmTest + androidApp:testDebugUnitTest' (Join-Path $root 'native') {
    .\gradlew.bat :shared:jvmTest :androidApp:testDebugUnitTest
}

Write-Host ''
Write-Host 'Summary' -ForegroundColor Cyan
foreach ($r in $results) {
    $label = if ($r.Passed) { 'PASS' } else { "FAIL (exit $($r.ExitCode))" }
    $color = if ($r.Passed) { 'Green' } else { 'Red' }
    Write-Host ("  {0,-55} {1}" -f $r.Step, $label) -ForegroundColor $color
}

if ($results | Where-Object { -not $_.Passed }) { exit 1 }
exit 0
