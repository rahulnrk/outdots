#Requires -Version 5.1
# Builds the single coordinated gomobile runtime (one libgojni.so).
# Packages: waruntime (WhatsApp, whatsmeow MPL-2.0) and igruntime
# (Instagram, mautrix-meta AGPL-3.0, linked but login unavailable).
param([string]$OutDir = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $root "..\..")
$goRoot = Join-Path $repo "tmp\go126root\go"
$goBin = Join-Path $goRoot "bin\go.exe"
$gopath = Join-Path $repo "tmp\gopath"
$ndk = "C:\Users\nande\AppData\Local\Android\Sdk\ndk\26.1.10909125"
$androidHome = "C:\Users\nande\AppData\Local\Android\Sdk"
$outDir = if ($OutDir) { $OutDir } else { Join-Path $root "build" }
$aar = Join-Path $outDir "whatsapp.aar"
$report = Join-Path $outDir "PACKAGING.md"

if (-not (Test-Path $goBin)) { throw "Go toolchain missing at $goBin" }
if (-not (Test-Path $ndk)) { throw "NDK missing at $ndk" }

New-Item -ItemType Directory -Force -Path $outDir, $gopath | Out-Null
$env:GOROOT = $goRoot
$env:GOPATH = $gopath
$env:GOBIN = Join-Path $gopath "bin"
$env:PATH = "$goRoot\bin;$($env:GOBIN);$($env:PATH)"
$env:ANDROID_HOME = $androidHome
$env:ANDROID_NDK_HOME = $ndk
$env:CGO_ENABLED = "1"

Push-Location $root
try {
    & $goBin version
    & $goBin install golang.org/x/mobile/cmd/gomobile@latest
    & $goBin install golang.org/x/mobile/cmd/gobind@latest
    & (Join-Path $env:GOBIN "gomobile.exe") init
    if (Test-Path $aar) { Remove-Item $aar -Force }
    & (Join-Path $env:GOBIN "gomobile.exe") bind `
        -target android/arm64 `
        -androidapi 26 `
        -javapkg app.mini.hub.wa `
        -ldflags "-extldflags=-Wl,-z,max-page-size=16384" `
        -o $aar `
        ./waruntime `
        ./igruntime
} finally {
    Pop-Location
}

$lines = @()
$lines += "# WhatsApp runtime packaging"
$lines += ""
$lines += "- Date: $(Get-Date -Format o)"
$lines += "- whatsmeow: c386243a72bacad080ba2938b37e1e4e0f1ca3b2 (go.mod replace)"
$lines += "- mautrix-meta: v0.2610.0 = 3c16226686c71af64df294df0803791685064e0b (AGPL-3.0, instameow only)"
$lines += "- packages: ./waruntime ./igruntime"
$lines += "- Go: $(& $goBin version)"
$lines += "- NDK: $ndk"
if (Test-Path $aar) {
    $size = (Get-Item $aar).Length
    $lines += "- AAR: $aar ($size bytes)"
    $extract = Join-Path $outDir "aar-extract"
    if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    Copy-Item $aar (Join-Path $extract "whatsapp.zip")
    Expand-Archive -Force -Path (Join-Path $extract "whatsapp.zip") -DestinationPath $extract
    $so = Get-ChildItem -Recurse $extract -Filter "*.so" | Select-Object -First 1
    if ($so) {
        $readelf = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
        $align = & $readelf -l $so.FullName | Select-String "LOAD"
        $lines += "- native: $($so.FullName) ($($so.Length) bytes)"
        $lines += "- LOAD headers:"
        $align | ForEach-Object { $lines += "  - $($_.Line.Trim())" }
    } else {
        $lines += "- native: no .so found in AAR"
    }
} else {
    $lines += "- AAR: missing (gomobile bind failed)"
}
$lines -join "`n" | Set-Content -Path $report -Encoding utf8
Write-Host "Wrote $report"
if (-not (Test-Path $aar)) { exit 1 }
