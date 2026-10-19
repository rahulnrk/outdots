param([Parameter(Mandatory = $true)][string]$Jar, [Parameter(Mandatory = $true)][string]$Out)
# Dumps the gomobile-generated public Java API of the given classes.jar so the
# coordinated runtime can prove WhatsApp's binding is unchanged across builds.
$javap = "C:\Program Files\Java\jdk-17\bin\javap.exe"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Jar))
$classes = $zip.Entries | Where-Object { $_.FullName -like "app/mini/hub/*.class" } | ForEach-Object { $_.FullName -replace '\.class$', '' -replace '/', '.' } | Sort-Object
$zip.Dispose()
$text = foreach ($c in $classes) { & $javap -public -cp $Jar $c }
$text | Set-Content -Path $Out -Encoding utf8
Write-Host "$($classes.Count) classes -> $Out"
