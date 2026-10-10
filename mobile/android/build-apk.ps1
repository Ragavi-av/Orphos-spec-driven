<#
.SYNOPSIS
  Compiles and builds the Orphos Android APKs: the shareable demo app and the
  real (connected) app.

.DESCRIPTION
  - demo   -> app-demo.apk   (mock data, no backend; installs on any phone)
  - actual -> app-debug.apk  (real Keycloak login + live BFF via adb reverse)
  - with -Release, also app-release-unsigned.apk (no signing config yet)

  Finished APKs are copied to .\dist\ with the version in the file name.

.EXAMPLE
  .\build-apk.ps1                 # demo + actual
  .\build-apk.ps1 -Variant demo   # demo only
  .\build-apk.ps1 -Clean -Release # clean build of demo + actual + release
#>
param(
    [ValidateSet('all', 'demo', 'actual')]
    [string]$Variant = 'all',
    [switch]$Release,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# --- JDK 21 ---------------------------------------------------------------
# AGP does not support the system JDK 25. Use JAVA_HOME if it is a real JDK 17+,
# otherwise fall back to the newest JDK 21 bundled with the VS Code Java extension.
function Test-Jdk([string]$path) {
    $path -and (Test-Path (Join-Path $path 'bin\javac.exe'))
}

$jdk = $null
if ((Test-Jdk $env:JAVA_HOME) -and ($env:JAVA_HOME -notmatch 'jdk-?25')) {
    $jdk = $env:JAVA_HOME
} else {
    $jdk = Get-ChildItem "$env:USERPROFILE\.vscode\extensions\redhat.java-*\jre\21*" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Jdk $_.FullName } |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) {
    throw 'No JDK 21 found. Install a JDK 21 or set JAVA_HOME to one.'
}
$env:JAVA_HOME = $jdk
$jdkFwd = $jdk -replace '\\', '/'
Write-Host "Using JDK: $jdk" -ForegroundColor Cyan

# --- Android SDK ----------------------------------------------------------
if (-not (Test-Path (Join-Path $root 'local.properties'))) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    if (-not (Test-Path $sdk)) { throw "Android SDK not found at $sdk. Set ANDROID_HOME." }
    "sdk.dir=$($sdk -replace '\\', '/')" | Set-Content -Encoding ascii (Join-Path $root 'local.properties')
}

# --- Gradle tasks ---------------------------------------------------------
$tasks = @()
if ($Clean) { $tasks += 'clean' }
if ($Variant -in 'all', 'demo')   { $tasks += ':app:assembleDemo' }
if ($Variant -in 'all', 'actual') { $tasks += ':app:assembleDebug' }
if ($Release)                     { $tasks += ':app:assembleRelease' }

Write-Host "Running: gradlew $($tasks -join ' ')" -ForegroundColor Cyan
Push-Location $root
try {
    # -D overrides the hardcoded org.gradle.java.home in gradle.properties.
    # Gradle writes warnings to stderr, which PowerShell 5.1 would turn into a
    # terminating error under 'Stop' — judge success by the exit code instead.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat "-Dorg.gradle.java.home=$jdkFwd" --console=plain @tasks 2>&1 | ForEach-Object { "$_" }
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($gradleExit -ne 0) { throw "Gradle build failed (exit $gradleExit)." }
} finally {
    Pop-Location
}

# --- Collect APKs ---------------------------------------------------------
$version = (Select-String -Path (Join-Path $root 'app\build.gradle') -Pattern 'versionName\s+"([^"]+)"').Matches[0].Groups[1].Value
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null

$outputs = @()
if ($Variant -in 'all', 'demo')   { $outputs += @{ Build = 'demo';    Name = "orphos-demo-$version.apk" } }
if ($Variant -in 'all', 'actual') { $outputs += @{ Build = 'debug';   Name = "orphos-$version.apk" } }
if ($Release)                     { $outputs += @{ Build = 'release'; Name = "orphos-$version-release-unsigned.apk" } }
Write-Host ''
Write-Host 'Built APKs:' -ForegroundColor Green
foreach ($o in $outputs) {
    $apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\$($o.Build)\*.apk") -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { throw "Expected APK for '$($o.Build)' was not produced." }
    Copy-Item $apk.FullName (Join-Path $dist $o.Name) -Force
    '  {0,-45} {1,8:N1} MB' -f $o.Name, ($apk.Length / 1MB) | Write-Host
}
Write-Host "Output folder: $dist"
