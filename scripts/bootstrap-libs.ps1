# Sync lib JARs and install them into the local Maven repo (.m2).
# Safe to run repeatedly (idempotent):
# - copies PluginAPI.jar from the Steam Rising World SDK
# - downloads the latest OZ Tools release from GitHub into lib/OZTools.jar
# - updates <oz.tools.version> in pom.xml to match that release
# - installs both JARs into .m2 (needed before the first mvn package on a fresh machine)
#
# Usage (from anywhere):
#   .\scripts\bootstrap-libs.ps1

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root "pom.xml"))) {
  $root = $PSScriptRoot
}
Set-Location $root

$libDir = Join-Path $root "lib"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null

$steamApi = "C:\Program Files (x86)\Steam\steamapps\common\RisingWorld\Data\SDK\PluginAPI.jar"
$steamJavadoc = "C:\Program Files (x86)\Steam\steamapps\common\RisingWorld\Data\SDK\javadoc.zip"
$libApi = Join-Path $libDir "PluginAPI.jar"
$libOz = Join-Path $libDir "OZTools.jar"

# --- PluginAPI from Steam SDK ---
if (-not (Test-Path $steamApi)) {
  throw "PluginAPI not found at: $steamApi"
}
Write-Host "Copying PluginAPI from Steam SDK -> lib/PluginAPI.jar"
Copy-Item -Path $steamApi -Destination $libApi -Force
if (Test-Path $steamJavadoc) {
  Write-Host "Copying javadoc.zip from Steam SDK -> lib/javadoc.zip"
  Copy-Item -Path $steamJavadoc -Destination (Join-Path $libDir "javadoc.zip") -Force
}

# --- OZ Tools latest release from GitHub ---
Write-Host "Fetching latest OZ Tools release tag..."
$tag = gh release view -R Devidian/rw-plugin-oz-tools --json tagName --jq ".tagName"
if (-not $tag) {
  throw "Could not read latest OZ Tools release (is gh installed and authenticated?)"
}
$ozVersion = $tag.Trim()
if ($ozVersion.StartsWith("v")) {
  $ozVersion = $ozVersion.Substring(1)
}
Write-Host "Latest OZ Tools: $tag ($ozVersion)"

$tmp = Join-Path $env:TEMP ("oztools-" + $ozVersion)
if (Test-Path $tmp) {
  Remove-Item -Recurse -Force $tmp
}
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Write-Host "Downloading OZTools release asset..."
gh release download $tag -R Devidian/rw-plugin-oz-tools -D $tmp --clobber
$zip = Get-ChildItem -Path $tmp -Filter "OZTools-*.zip" | Select-Object -First 1
if (-not $zip) {
  throw "No OZTools-*.zip asset found for release $tag"
}

$extract = Join-Path $tmp "extracted"
Expand-Archive -Path $zip.FullName -DestinationPath $extract -Force
$jar = Get-ChildItem -Path $extract -Recurse -Filter "OZTools.jar" | Select-Object -First 1
if (-not $jar) {
  throw "OZTools.jar not found inside $($zip.Name)"
}
Write-Host "Copying OZTools.jar -> lib/OZTools.jar"
Copy-Item -Path $jar.FullName -Destination $libOz -Force

# Keep pom version in sync with the downloaded release
$pomPath = Join-Path $root "pom.xml"
$pom = Get-Content -Raw $pomPath
if ($pom -notmatch "<oz\.tools\.version>[^<]+</oz\.tools\.version>") {
  throw "oz.tools.version not found in pom.xml"
}
$pom = [regex]::Replace(
  $pom,
  "<oz\.tools\.version>[^<]+</oz\.tools\.version>",
  "<oz.tools.version>$ozVersion</oz.tools.version>"
)
Set-Content -Path $pomPath -Value $pom -NoNewline
Write-Host "pom.xml oz.tools.version -> $ozVersion"

# Read PluginAPI version from pom (Steam jar has no version in the filename)
if ($pom -notmatch "<rw\.plugin\.api\.version>([^<]+)</rw\.plugin\.api\.version>") {
  throw "rw.plugin.api.version not found in pom.xml"
}
$apiVersion = $Matches[1]

Write-Host "Installing PluginAPI $apiVersion into .m2"
mvn -B install:install-file `
  "-Dfile=lib/PluginAPI.jar" `
  "-DgroupId=net.rising-world" `
  "-DartifactId=plugin-api" `
  "-Dversion=$apiVersion" `
  "-Dpackaging=jar"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Installing OZ Tools $ozVersion into .m2"
mvn -B install:install-file `
  "-Dfile=lib/OZTools.jar" `
  "-DgroupId=com.github.devidian" `
  "-DartifactId=rw-plugin-oz-tools" `
  "-Dversion=$ozVersion" `
  "-Dpackaging=jar"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Done. lib/ and .m2 are synced. You can run: mvn -B package"
Write-Host "Note: bump <rw.plugin.api.version> in pom.xml yourself when Rising World changes the API version."
