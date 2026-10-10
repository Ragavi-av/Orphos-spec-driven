<#
  Builds a self-contained, directly-executable Orphos Setup wizard for Windows.

  Output: an app-image folder containing "Orphos Setup.exe" plus a trimmed,
  bundled Java runtime - the operator double-clicks the .exe; no Java, Maven, or
  PATH setup is required on the target machine.

  Usage (from anywhere):
      pwsh -File tools\setup-wizard\build-exe.ps1            # build jar + exe
      pwsh -File tools\setup-wizard\build-exe.ps1 -SkipMaven # package existing jar

  Requires a JDK 17+ with jpackage + jlink on the build machine (target needs neither).
#>
[CmdletBinding()]
param(
    [switch]$SkipMaven,
    [string]$AppVersion = "1.0.0"
)
$ErrorActionPreference = "Stop"

# JAVA_TOOL_OPTIONS makes every JDK tool print a banner to stderr; under
# ErrorActionPreference=Stop that harmless line aborts the script. Clear it for
# this process so jpackage/jlink run quietly.
$env:JAVA_TOOL_OPTIONS = ""

# Repo root is two levels up from this script (tools\setup-wizard\..\..).
$wizardDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = (Resolve-Path (Join-Path $wizardDir "..\..")).Path
$targetDir = Join-Path $wizardDir "target"
$jarName   = "setup-wizard-$AppVersion-SNAPSHOT.jar"
$distDir   = Join-Path $wizardDir "dist"

# jpackage / jlink live in the JDK; find them via JAVA_HOME or PATH.
function Find-Tool($name) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$name.exe"))) {
        return (Join-Path $env:JAVA_HOME "bin\$name.exe")
    }
    $cmd = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "$name not found. Install a JDK 17+ and set JAVA_HOME, or add its bin to PATH."
}
$jpackage = Find-Tool "jpackage"

if (-not $SkipMaven) {
    Write-Host "==> Building the executable fat jar (mvnw package)..." -ForegroundColor Cyan
    Push-Location $repoRoot
    try { & .\mvnw.cmd -q -pl tools/setup-wizard package -DskipTests }
    finally { Pop-Location }
}

$jarPath = Join-Path $targetDir $jarName
if (-not (Test-Path $jarPath)) { throw "Fat jar not found: $jarPath (run without -SkipMaven first)." }

# Clean any previous app-image so jpackage doesn't refuse to overwrite.
if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
New-Item -ItemType Directory -Force $distDir | Out-Null

# jpackage copies the ENTIRE --input dir into the image, so stage a clean folder
# holding only the fat jar (not target/'s classes, the .original jar, or maven
# metadata) to keep the payload lean.
$inputDir = Join-Path $distDir "_input"
New-Item -ItemType Directory -Force $inputDir | Out-Null
Copy-Item $jarPath $inputDir

Write-Host "==> Packaging self-contained app-image with jpackage..." -ForegroundColor Cyan
# --type app-image => a portable folder with a launcher .exe + bundled runtime
# (no WiX/installer needed). Main class is the Spring Boot loader (read from the
# fat jar's manifest). jlink auto-trims the runtime to the modules we use.
$jpArgs = @(
    "--type", "app-image",
    "--name", "Orphos Setup",
    "--app-version", $AppVersion,
    "--input", $inputDir,
    "--main-jar", $jarName,
    "--dest", $distDir,
    "--vendor", "Orphos",
    "--description", "Orphos EIP setup wizard",
    "--java-options", "-Djava.awt.headless=false"
)
& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE." }

# Drop the staging folder; only the finished app-image should remain in dist.
Remove-Item -Recurse -Force $inputDir -ErrorAction SilentlyContinue

$exe = Join-Path $distDir "Orphos Setup\Orphos Setup.exe"
if (Test-Path $exe) {
    Write-Host "`n==> Done. Directly-executable wizard:" -ForegroundColor Green
    Write-Host "    $exe"
    Write-Host "    Zip the '$distDir\Orphos Setup' folder to hand it over - the target needs no Java."
} else {
    throw "jpackage reported success but the .exe is missing under $distDir."
}
