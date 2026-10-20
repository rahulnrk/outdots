#Requires -Version 5.1
# Builds the third-party notices bundle shipped inside the APK
# (androidApp/src/main/assets/licenses/) from EXACT texts:
#   - Go modules linked into libgojni.so: LICENSE + NOTICE/PATENTS/AUTHORS/CREDITS
#     read from the pinned module cache (go list -deps of all runtime packages);
#   - Go toolchain runtime + golang.org/x/mobile (statically linked by gomobile);
#   - Maven artifacts on the debug runtime classpath: embedded LICENSE/NOTICE files
#     plus POM license metadata (collectThirdPartyNotices must run first);
#   - Native/model/font components with upstream texts fetched by tag into
#     native/compliance/upstream-texts/ (see SOURCES.tsv there).
# Writes index.json (machine-readable) consumed by the in-app licenses screen.
# This bundle is notice material only. It is not a compliance determination,
# it does not grant or claim any licence for MINI's own code, and it is not an
# upstream exception.
param([switch]$SkipGradle)
$ErrorActionPreference = "Stop"
$native = Resolve-Path (Join-Path $PSScriptRoot "..")
$repo = Resolve-Path (Join-Path $native "..")
$assets = Join-Path $native "androidApp\src\main\assets\licenses"
$texts = Join-Path $assets "texts"
$upstream = Join-Path $PSScriptRoot "upstream-texts"
$env:GOROOT = Join-Path $repo "tmp\go126root\go"
$env:GOPATH = Join-Path $repo "tmp\gopath"
$go = Join-Path $env:GOROOT "bin\go.exe"

if (-not $SkipGradle) {
  Push-Location $native
  & .\gradlew.bat :androidApp:collectThirdPartyNotices -q
  if ($LASTEXITCODE -ne 0) { throw "collectThirdPartyNotices failed" }
  Pop-Location
}

if (Test-Path $texts) { Remove-Item $texts -Recurse -Force }
New-Item -ItemType Directory -Force -Path $texts | Out-Null

function Classify([string]$t) {
  if ($t -match 'AFFERO') { return 'AGPL-3.0' }
  if ($t -match 'GNU LESSER') { return 'LGPL' }
  if ($t -match 'GNU GENERAL PUBLIC LICENSE') { return 'GPL-3.0' }
  if ($t -match 'Mozilla Public License') { return 'MPL-2.0' }
  if ($t -match 'Apache License') { return 'Apache-2.0' }
  if ($t -match 'SIL OPEN FONT LICENSE') { return 'OFL-1.1' }
  if ($t -match 'Redistribution and use in source and binary forms') {
    if ($t -match 'Neither the name|names of its') { return 'BSD-3-Clause' } else { return 'BSD-2-Clause' }
  }
  if ($t -match 'Permission is hereby granted') { return 'MIT' }
  if ($t -match 'Permission to use, copy, modify, and(/or)? distribute') { return 'ISC' }
  return 'UNKNOWN'
}
function Safe([string]$s) { ($s -replace '[^A-Za-z0-9._-]', '_') }
function Sha([string]$p) { (Get-FileHash $p -Algorithm SHA256).Hash.ToLower() }

$entries = New-Object System.Collections.Generic.List[object]
function Add-Entry($name, $version, $license, $component, $origin, $files, $notes) {
  $entries.Add([ordered]@{
    name = $name; version = $version; license = $license; component = $component
    origin = $origin; files = @($files); notes = $notes
  }) | Out-Null
}
function Copy-Text($src, $prefix) {
  # Content-addressed: identical texts (e.g. Apache-2.0 in 70 artifacts) are stored once.
  $hash = Sha $src
  $ext = [IO.Path]::GetExtension($src); if (-not $ext -or $ext.Length -gt 5) { $ext = ".txt" }
  $name = $hash.Substring(0, 20) + $ext
  $dest = Join-Path $texts $name
  if (-not (Test-Path $dest)) { Copy-Item $src $dest -Force }
  return [ordered]@{ path = "texts/" + $name; sha256 = $hash; original = (Split-Path $src -Leaf) }
}

# ---- 1. Go modules linked into libgojni.so ----
$pkgs = @("./waruntime", "./igruntime", "./liruntime", "./msgruntime")
$goOverrides = @{
  "github.com/beeper/poly1305" = @{ license = "BSD-3-Clause"; licenseFrom = "golang.org/x/crypto"; note = "Upstream repo has no LICENSE file. Source is a fork of golang.org/x/crypto (per-file Go Authors BSD headers); the x/crypto LICENSE text is reproduced." }
}
Push-Location (Join-Path $native "whatsappRuntime")
$mods = & $go list -deps -f '{{if .Module}}{{.Module.Path}}{{end}}' @pkgs | Sort-Object -Unique
$used = @{}
foreach ($p in $pkgs) { foreach ($m in (& $go list -deps -f '{{if .Module}}{{.Module.Path}}{{end}}' $p | Sort-Object -Unique)) { if (-not $used[$m]) { $used[$m] = @() }; $used[$m] += $p.TrimStart('./') } }
$modInfo = @{}
foreach ($m in $mods) {
  if ($m -eq 'app.mini.hub/whatsappRuntime') { continue }
  $modInfo[$m] = & $go list -m -json $m | ConvertFrom-Json
}
foreach ($m in $modInfo.Keys | Sort-Object) {
  $j = $modInfo[$m]
  $dir = if ($j.Replace) { $j.Replace.Dir } else { $j.Dir }
  $ver = if ($j.Replace) { "$($j.Replace.Path)@$($j.Replace.Version)" } else { $j.Version }
  $files = @()
  $lic = 'UNKNOWN'; $note = $null
  $ov = $goOverrides[$m]
  $licFiles = Get-ChildItem $dir -File | Where-Object { $_.Name -match '^(LICEN[SC]E|COPYING)' }
  if (-not $licFiles -and $ov) {
    $src = $modInfo[$ov.licenseFrom]
    $srcDir = if ($src.Replace) { $src.Replace.Dir } else { $src.Dir }
    $licFiles = Get-ChildItem $srcDir -File | Where-Object { $_.Name -match '^LICEN[SC]E' }
    $note = $ov.note
  }
  foreach ($f in $licFiles) { $files += Copy-Text $f.FullName "go_$m" }
  foreach ($f in (Get-ChildItem $dir -File -Recurse -Depth 2 | Where-Object { $_.Name -match '^(NOTICE|PATENTS|AUTHORS|CREDITS)' })) {
    $files += Copy-Text $f.FullName ("go_" + $m + "_" + ($f.FullName.Substring($dir.Length + 1) -replace '[\\/]', '_'))
  }
  if ($ov) { $lic = $ov.license } elseif ($licFiles) { $lic = Classify ((Get-Content $licFiles[0].FullName -TotalCount 60) -join ' ') }
  $exc = Get-ChildItem $dir -File | Where-Object { $_.Name -eq 'LICENSE.exceptions' }
  foreach ($e in $exc) { $files += Copy-Text $e.FullName "go_$m"; $note = "LICENSE.exceptions names Beeper and Element only. It does not grant MINI any exception." }
  Add-Entry $m $ver $lic "libgojni.so (Go, linked by $($used[$m] -join ', '))" "Go module cache (go.sum verified)" $files $note
}
Pop-Location

# Go toolchain runtime/stdlib and gomobile glue are statically linked into libgojni.so.
$goVer = (& $go version) -replace '^go version ', ''
Add-Entry "Go standard library and runtime" $goVer "BSD-3-Clause" "libgojni.so (Go runtime)" "GOROOT" @(
  (Copy-Text (Join-Path $env:GOROOT "LICENSE") "go_toolchain"),
  (Copy-Text (Join-Path $env:GOROOT "PATENTS") "go_toolchain")
) "Statically linked by the Go compiler into every Go binary."
$mob = Get-ChildItem (Join-Path $env:GOPATH "pkg\mod\golang.org\x") -Directory | Where-Object { $_.Name -like 'mobile@*' } | Sort-Object Name | Select-Object -Last 1
if ($mob) {
  Add-Entry "golang.org/x/mobile (gomobile bind/seq)" ($mob.Name -replace '^mobile@', '') "BSD-3-Clause" "libgojni.so + generated go.Seq Java glue" "Go module cache" @(
    (Copy-Text (Join-Path $mob.FullName "LICENSE") "go_x_mobile"),
    (Copy-Text (Join-Path $mob.FullName "PATENTS") "go_x_mobile")
  ) "gomobile generates JNI/Java glue and links its seq runtime into the bound library."
}

# ---- 2. Maven artifacts (Android app classpath) ----
$noticeRoot = Join-Path $native "androidApp\build\third-party-notices\debugRuntimeClasspath"
$idx = Import-Csv (Join-Path $noticeRoot "INDEX.tsv") -Delimiter "`t" | Where-Object { $_.pom_licenses -ne '(dup)' }
$upIdx = @{}
if (Test-Path (Join-Path $upstream "SOURCES.tsv")) {
  Import-Csv (Join-Path $upstream "SOURCES.tsv") -Delimiter "`t" | ForEach-Object { $upIdx[$_.component] = $_ }
}
foreach ($row in $idx) {
  $c = $row.coordinates
  if ($c -eq 'project :shared' -or $c -eq 'classes.jar') { continue }  # MINI's own code / gomobile jar (covered above)
  $parts = $c.Split(':')
  $ga = "{0}:{1}" -f $parts[0], $parts[1]
  $dir = Join-Path $noticeRoot ($c -replace '[:/\\]', '_')
  $files = @()
  if (Test-Path $dir) {
    foreach ($f in (Get-ChildItem $dir -File | Where-Object { $_.Name -notmatch '\.pom\.xml$|^pom\.xml$' })) { $files += Copy-Text $f.FullName "maven_$c" }
  }
  $lic = $row.pom_licenses
  $note = $null
  if ($upIdx[$ga]) {
    foreach ($u in ($upIdx[$ga].files -split ';')) { if ($u) { $files += Copy-Text (Join-Path $upstream $u) "maven_$ga" } }
    if ($upIdx[$ga].license) { $lic = $upIdx[$ga].license }
    if ($upIdx[$ga].notes) { $note = $upIdx[$ga].notes }
  }
  if ($lic -match 'Apache' -and -not ($files | Where-Object { $_.path -match 'LICEN|COPYING' })) {
    $files += Copy-Text (Join-Path $upstream "apache-2.0.txt") "maven_$ga"
    $note = (@($note, "POM declares Apache-2.0; artifact ships no LICENSE file. Canonical Apache-2.0 text reproduced.") | Where-Object { $_ }) -join " "
  }
  Add-Entry $ga $parts[2] $lic "Android app (Maven)" "POM + artifact contents" $files $note
}

# ---- 3. Native, font and model components outside Maven/Go ----
foreach ($k in $upIdx.Keys | Where-Object { $_ -like 'extra:*' } | Sort-Object) {
  $u = $upIdx[$k]
  $files = @()
  foreach ($f in ($u.files -split ';')) { if ($f) { $files += Copy-Text (Join-Path $upstream $f) ($k -replace '^extra:', 'extra_') } }
  Add-Entry ($k -replace '^extra:', '') $u.version $u.license $u.where $u.source_url $files $u.notes
}
foreach ($font in @("OFL-Inter.txt", "OFL-Manrope.txt")) {
  $p = Join-Path $assets $font
  if (Test-Path $p) { Add-Entry ($font -replace '^OFL-|\.txt$', '') "bundled" "OFL-1.1" "Bundled font" "app assets" @([ordered]@{ path = $font; sha256 = (Sha $p); original = $font }) $null }
}

# ---- 4. Full canonical license texts referenced by short name ----
$canonical = [ordered]@{ "AGPL-3.0" = "agpl-3.0.txt"; "GPL-3.0" = "gpl-3.0.txt"; "MPL-2.0" = "mpl-2.0.txt"; "Apache-2.0" = "apache-2.0.txt" }
$canonFiles = [ordered]@{}
foreach ($k in $canonical.Keys) { $canonFiles[$k] = Copy-Text (Join-Path $upstream $canonical[$k]) "canonical" }

$unknown = @($entries | Where-Object { $_.license -eq 'UNKNOWN' -or $_.license -eq '-' -or -not $_.license })
$noText = @($entries | Where-Object { $_.files.Count -eq 0 })
$out = [ordered]@{
  schema = 1
  generated = (Get-Date).ToString("o")
  git_commit = (& git -C $repo rev-parse HEAD).Trim()
  disclaimer = "Third-party notices for components included in this build. This list does not state or imply a licence for MINI's own code, does not grant any exception, and is not a determination that distribution is compliant."
  canonical_texts = $canonFiles
  components = $entries
  gaps = [ordered]@{ unknown_license = @($unknown | ForEach-Object { $_.name }); no_text = @($noText | ForEach-Object { $_.name }) }
}
$out | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $assets "index.json") -Encoding utf8
Write-Host ("Wrote {0} components, {1} text files. Unknown licence: {2}. Missing text: {3}." -f $entries.Count, (Get-ChildItem $texts).Count, $unknown.Count, $noText.Count)
if ($unknown.Count -gt 0) { $unknown | ForEach-Object { Write-Host "  UNKNOWN: $($_.name)" } }
if ($noText.Count -gt 0) { $noText | ForEach-Object { Write-Host "  NO TEXT: $($_.name)" } }
if ($unknown.Count -gt 0 -or $noText.Count -gt 0) { exit 2 }
