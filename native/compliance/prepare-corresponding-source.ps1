#Requires -Version 5.1
# Prepares a LOCAL, version-matched Corresponding Source archive for one build of the
# MINI Android APK. It does NOT publish anything, does NOT add a licence to MINI's
# own code, and is NOT evidence of compliance. Distribution remains blocked.
#
# What goes in (only what is needed to regenerate and install the APK):
#   - git-tracked files at HEAD under native/ that are build inputs
#     (Kotlin/Java/Go sources, Gradle files, manifests, resources, assets,
#      go.mod/go.sum, build scripts, compliance texts, NOTICE files);
#   - Go module sources for every module linked into libgojni.so, copied from
#     the module cache at the exact go.sum-pinned versions (vendor/ layout);
#   - BUILD.md with toolchain versions and exact commands; MANIFEST.sha256.
# What is excluded by construction:
#   - anything not tracked by git (local.properties, keystores, .env, build/);
#   - logs, screenshots, device review artifacts, test outputs, tmp/;
#   - model weights (.task/.tflite/.bin) and signing material;
#   - OAuth client IDs are never in source (they come from -P properties).
# A fail-closed scan rejects the archive if it finds key/credential/PII patterns.
param(
  [string]$OutDir = "",
  [switch]$IncludeGoModules = $true,
  # Head: committed HEAD only (git archive). WorkingTree: tracked + untracked-not-ignored
  # files as they are on disk, i.e. exactly what a local build compiles. Use WorkingTree when
  # the binary was built from uncommitted changes; the manifest records which paths differ.
  [ValidateSet("Head", "WorkingTree")][string]$Source = "Head"
)
$ErrorActionPreference = "Stop"
$native = Resolve-Path (Join-Path $PSScriptRoot "..")
$repo = Resolve-Path (Join-Path $native "..")
$env:GOROOT = Join-Path $repo "tmp\go126root\go"
$env:GOPATH = Join-Path $repo "tmp\gopath"
$go = Join-Path $env:GOROOT "bin\go.exe"

$commit = (& git -C $repo rev-parse HEAD).Trim()
$short = $commit.Substring(0, 12)
$dirtyLines = @(& git -C $repo status --porcelain --untracked-files=all -- native | Where-Object { $_ })
$dirty = $dirtyLines.Count -gt 0
$tag = if ($Source -eq "WorkingTree") { "$short-wt" } else { $short }
if (-not $OutDir) { $OutDir = Join-Path $repo "tmp\corresponding-source" }
$stage = Join-Path $OutDir "mini-native-src-$tag"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# ---- 1. Build inputs (never untracked-ignored files, so local.properties/keys/build/ cannot enter) ----
$excludeRe = '(^native/review-artifacts/)|(\.log$)|(\.png$)|(\.jpe?g$)|(\.webp$)|(-test-output\.txt$)|(^native/build/)|(/build/)|(\.task$)|(\.tflite$)|(\.litertlm$)|(\.jks$)|(\.keystore$)|(\.p12$)|(\.pem$)|(local\.properties$)|(\.env)|(^native/[^/]+\.md$)|(^native/\.gradle/)|(^native/\.kotlin/)'
$keepDocsRe = '^native/whatsappRuntime/(NOTICE|README\.md|THIRD_PARTY_LICENSES\.md)$|^native/compliance/'
if ($Source -eq "Head") {
  $tracked = & git -C $repo ls-tree -r --name-only HEAD -- native
} else {
  $tracked = & git -C $repo ls-files --cached --others --exclude-standard -- native | Where-Object { Test-Path (Join-Path $repo $_) }
}
$selected = $tracked | Where-Object { ($_ -notmatch $excludeRe) -or ($_ -match $keepDocsRe) }
$excluded = $tracked | Where-Object { $selected -notcontains $_ }
$listFile = Join-Path $OutDir "selected-$tag.txt"
$selected | Set-Content -Path $listFile -Encoding ascii
if ($Source -eq "Head") {
  $tar = Join-Path $OutDir "tracked-$short.tar"
  Push-Location $repo
  & git archive --format=tar --prefix=src/ -o $tar HEAD -- @selected
  if ($LASTEXITCODE -ne 0) { throw "git archive failed" }
  Pop-Location
  & tar -xf $tar -C $stage
  Remove-Item $tar
} else {
  foreach ($rel in $selected) {
    $dst = Join-Path $stage ("src\" + ($rel -replace '/', '\'))
    New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
    Copy-Item (Join-Path $repo $rel) $dst -Force
  }
}

# ---- 2. Go module sources pinned by go.sum (vendor layout) ----
$vendorNote = "not included"
if ($IncludeGoModules) {
  $vend = Join-Path $stage "src\native\whatsappRuntime\vendor"
  Push-Location (Join-Path $repo "native\whatsappRuntime")
  # go mod vendor writes into the module dir; do it in a temp copy so the working tree is untouched.
  $tmpMod = Join-Path $OutDir "vendor-work-$tag"
  if (Test-Path $tmpMod) { Remove-Item $tmpMod -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $tmpMod | Out-Null
  Copy-Item go.mod, go.sum $tmpMod
  foreach ($d in @("waruntime", "igruntime", "liruntime", "msgruntime")) { Copy-Item $d (Join-Path $tmpMod $d) -Recurse }
  Pop-Location
  Push-Location $tmpMod
  $env:GOFLAGS = "-mod=mod"
  & $go mod vendor
  if ($LASTEXITCODE -ne 0) { throw "go mod vendor failed" }
  Remove-Item Env:\GOFLAGS
  Pop-Location
  Move-Item (Join-Path $tmpMod "vendor") $vend
  Remove-Item $tmpMod -Recurse -Force
  $vendorNote = "src/native/whatsappRuntime/vendor (go mod vendor at go.sum-pinned versions)"
}

# ---- 3. BUILD.md with exact versions ----
$gomob = (Get-ChildItem (Join-Path $env:GOPATH "pkg\mod\golang.org\x") -Directory | Where-Object { $_.Name -like 'mobile@*' } | Sort-Object Name | Select-Object -Last 1).Name
$catalog = Get-Content (Join-Path $repo "native\gradle\libs.versions.toml") -Raw
$wrapper = (Get-Content (Join-Path $repo "native\gradle\wrapper\gradle-wrapper.properties") | Where-Object { $_ -match 'distributionUrl' }) -replace '.*distributionUrl=', ''
$build = @"
# Corresponding Source: MINI Android APK (build $tag)

Prepared locally on $(Get-Date -Format o). NOT published. Distribution of any build
is blocked pending the owner's licensing decision. This archive is preparation
material only; it does not grant, change or assert any licence for MINI's own code,
and its existence is not evidence of compliance.

- Git commit: $commit
- Source mode: $Source $(if ($Source -eq 'WorkingTree') { '(on-disk tracked + untracked-not-ignored files; see "Uncommitted paths" below)' } else { '(committed HEAD only)' })
- Working tree for native/ at prep time: $(if ($dirty) { "DIRTY ($($dirtyLines.Count) paths)" } else { 'clean' })
- Go modules: $vendorNote

## Toolchain (exact)

| Tool | Version |
|---|---|
| Go | $((& $go version) -replace '^go version ','') |
| gomobile / gobind | golang.org/x/$gomob |
| Android NDK | 26.1.10909125 |
| Android Gradle Plugin / Kotlin | see src/native/gradle/libs.versions.toml (agp, kotlin) |
| Gradle | $wrapper |
| JDK | 17 |
| Android compileSdk / minSdk | see src/native/androidApp/build.gradle.kts |

## Rebuild the native runtime (libgojni.so + classes.jar)

````
cd src/native/whatsappRuntime
go env -w GOFLAGS=-mod=vendor          # use the vendored, pinned module sources
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
gomobile init
gomobile bind -target android/arm64 -androidapi 26 -javapkg app.mini.hub.wa \
  -ldflags "-extldflags=-Wl,-z,max-page-size=16384" -o build/whatsapp.aar \
  ./waruntime ./igruntime ./liruntime ./msgruntime
# then extract build/whatsapp.aar to build/aar-extract (classes.jar, jni/)
````
(Windows equivalent: src/native/whatsappRuntime/build-android.ps1.)

## Rebuild the APK

````
cd src/native
./gradlew :androidApp:assembleDebug
# optional, owner-supplied (never in source): -Pmini.googleOAuthClientId=... -Pmini.outlookOAuthClientId=...
````
Release builds fail by design while the copyleft runtime is present and no licensing
decision is recorded (verifyReleaseRuntimeLicensing). That guard is a safety interlock,
not a compliance mechanism.

## Install a rebuilt APK (user's own device)

````
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
````
A rebuilt debug APK is signed with the builder's own debug key, so it installs as an
update only over a build signed with the same key; otherwise uninstall first. MINI's
signing keys are not part of Corresponding Source and are never included.

## Not included, and why

- Signing keys, keystores, local.properties, OAuth client IDs, tokens: secrets / builder-specific.
- On-device model weights (*.task): not part of the APK; see docs/BUILD_AND_MODEL_TERMS_2026-10-20.md.
- Device screenshots, review artifacts, logs, tmp/: may contain personal data; not build inputs.
- Untracked or ignored files of any kind.

## Excluded tracked paths ($($excluded.Count))
$(($excluded | ForEach-Object { "- $_" }) -join "`n")

## Uncommitted paths at prep time ($($dirtyLines.Count))
$(if ($dirtyLines.Count -eq 0) { '- none' } else { ($dirtyLines | ForEach-Object { "- ``$_``" }) -join "`n" })
"@
Set-Content -Path (Join-Path $stage "BUILD.md") -Value $build -Encoding utf8

# ---- 4. Fail-closed secret / PII scan ----
$patterns = [ordered]@{
  "private key block"      = '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  "google api key"         = 'AIza[0-9A-Za-z_\-]{35}'
  "google oauth client id" = '[0-9]{8,}-[0-9a-z]{20,}\.apps\.googleusercontent\.com'
  "oauth client secret"    = 'GOCSPX-[0-9A-Za-z_\-]{20,}'
  "aws access key"         = 'AKIA[0-9A-Z]{16}'
  "slack token"            = 'xox[abpr]-[0-9A-Za-z-]{10,}'
  "github token"           = 'gh[pousr]_[0-9A-Za-z]{30,}'
  "bearer token literal"   = 'Bearer [A-Za-z0-9\-_\.]{30,}'
  "jwt"                    = 'eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}'
  "fb session cookie"      = '\b(xs|c_user|datr)=[0-9A-Za-z%:_\-]{8,}'
  "ig session cookie"      = '\bsessionid=[0-9A-Za-z%:_\-]{15,}'
  "linkedin cookie"        = '\bli_at=[0-9A-Za-z_\-]{20,}'
  "adb device serial"      = '\b2B011FDH300GL0\b'
  "owner email"            = 'nande[A-Za-z0-9._%+\-]*@'
}
$allow = @('placeholder', 'example', '1234567890-abcdef', 'probe-xs', 'probe-datr', '<redacted>', 'REDACTED', 'test-', 'fixture', 'synthetic', 'xoxp-user-token')
# Reviewed, exact exemptions (path + kind). Each was inspected and is not a secret.
$reviewed = @{
  'src\native\whatsappRuntime\vendor\go.mau.fi\whatsmeow\binary\token\token.go|google api key' = 'WhatsApp binary-protocol token dictionary entry that happens to start with AIza; not a Google key.'
}
# Vendored upstream test fixtures (tiny SQLite files used only by modernc.org/sqlite tests).
$reviewedFiles = @{
  'src\native\whatsappRuntime\vendor\modernc.org\sqlite\embed.db'  = 'modernc.org/sqlite test fixture (go:embed in all_test.go), 8 KiB, no user data.'
  'src\native\whatsappRuntime\vendor\modernc.org\sqlite\embed2.db' = 'modernc.org/sqlite test fixture (go:embed in all_test.go), 8 KiB, no user data.'
}
$hits = @()
$scanFiles = Get-ChildItem $stage -Recurse -File | Where-Object { $_.Length -lt 5MB -and $_.FullName -notmatch '\\vendor\\' }
foreach ($f in $scanFiles) {
  $t = [IO.File]::ReadAllText($f.FullName)
  foreach ($k in $patterns.Keys) {
    foreach ($m in [regex]::Matches($t, $patterns[$k])) {
      $ctx = $t.Substring([Math]::Max(0, $m.Index - 40), [Math]::Min(120, $t.Length - [Math]::Max(0, $m.Index - 40)))
      if ($allow | Where-Object { $ctx -match [regex]::Escape($_) }) { continue }
      $hits += [pscustomobject]@{ file = $f.FullName.Substring($stage.Length + 1); kind = $k }
    }
  }
}
# Vendor tree: only key/credential patterns (third-party test fixtures legitimately contain fake keys; report, don't block on them).
$vendorHits = @()
if ($IncludeGoModules) {
  foreach ($f in (Get-ChildItem (Join-Path $stage "src\native\whatsappRuntime\vendor") -Recurse -File | Where-Object { $_.Length -lt 5MB })) {
    $t = [IO.File]::ReadAllText($f.FullName)
    foreach ($k in @("google api key", "oauth client secret", "aws access key", "github token", "adb device serial", "owner email")) {
      if ([regex]::IsMatch($t, $patterns[$k])) { $vendorHits += [pscustomobject]@{ file = $f.FullName.Substring($stage.Length + 1); kind = $k } }
    }
  }
}
$forbiddenFiles = Get-ChildItem $stage -Recurse -File | Where-Object {
  $_.Name -match '\.(jks|keystore|p12|pem|task|tflite|litertlm|apk|aab|db|sqlite|sqlite3)$' -or $_.Name -in @('local.properties', '.env', 'google-services.json')
}
$exempted = @()
$vendorHits = @($vendorHits | Where-Object {
  $key = "$($_.file)|$($_.kind)"
  if ($reviewed.ContainsKey($key)) { $exempted += [pscustomobject]@{ item = $key; reason = $reviewed[$key] }; $false } else { $true }
})
$forbiddenFiles = @($forbiddenFiles | Where-Object {
  $rel = $_.FullName.Substring($stage.Length + 1)
  if ($reviewedFiles.ContainsKey($rel)) { $exempted += [pscustomobject]@{ item = $rel; reason = $reviewedFiles[$rel] }; $false } else { $true }
})

# ---- 5. Manifest + archive ----
$manifest = Get-ChildItem $stage -Recurse -File | Sort-Object FullName | ForEach-Object {
  "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), ($_.FullName.Substring($stage.Length + 1) -replace '\\', '/')
}
Set-Content -Path (Join-Path $stage "MANIFEST.sha256") -Value $manifest -Encoding ascii

$report = [ordered]@{
  commit = $commit; dirty_native = [bool]$dirty
  files = ($manifest | Measure-Object).Count
  excluded_tracked = $excluded.Count
  secret_hits = @($hits); vendor_hits = @($vendorHits)
  forbidden_files = @($forbiddenFiles | ForEach-Object { $_.FullName.Substring($stage.Length + 1) })
  reviewed_exemptions = @($exempted)
}
$report | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir "scan-$tag.json") -Encoding utf8

if ($hits.Count -gt 0 -or $forbiddenFiles.Count -gt 0 -or $vendorHits.Count -gt 0) {
  Write-Host "SCAN FAILED: $($hits.Count) secret/PII hit(s), $($vendorHits.Count) vendor hit(s), $($forbiddenFiles.Count) forbidden file(s). Archive NOT created. See scan-$tag.json"
  $hits + $vendorHits | Group-Object kind | ForEach-Object { Write-Host ("  {0}: {1}" -f $_.Name, $_.Count) }
  exit 3
}
$zip = Join-Path $OutDir "mini-native-src-$tag.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip -CompressionLevel Optimal
$zh = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
Set-Content -Path "$zip.sha256" -Value "$zh  $(Split-Path $zip -Leaf)" -Encoding ascii
Write-Host ("OK: {0} ({1:N1} MB, {2} files, sha256 {3}). Local only; not published." -f $zip, ((Get-Item $zip).Length / 1MB), $report.files, $zh.Substring(0, 16))
