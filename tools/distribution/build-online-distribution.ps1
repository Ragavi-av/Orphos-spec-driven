<#
.SYNOPSIS
  Builds the ONLINE Orphos client package: only the Orphos (eip-*) images are
  bundled; every third-party image and the AI models download on first start.

.DESCRIPTION
  The offline packages carry all ~59 runtime images (~27 GB). Most of that is
  public, third-party software - Ollama, Docling, Postgres, Keycloak and so on -
  which the client can fetch from its registry as easily as from a zip. This
  package ships only what exists nowhere else: the eip-* images built from this
  repo. The compose files pin the third-party images by digest or exact tag, so
  the client still gets the versions this package was built against.

      Orphos-Online\
        images\orphos-images.tar   <- the eip-* images only, docker save'd
        images\orphos-images.size  <- byte count for the integrity check
        docker-compose.*.yml       <- unchanged copies of the repo's four layers
        orphos.ps1 / orphos.sh     <- the Docker-only engine (online variant)
        start/stop/restart/status  <- .bat and .sh wrappers
        config\ ontology\ drivers\ documentation\
        .env.example               <- generated into .env on first start
        .orphos-distribution       <- marker

  Like the other builders, this never builds an image: it refuses to package an
  eip-* image that is missing, or older than the jar in its module's target\,
  because that image would ship stale code. Run `mvn package` and rebuild the
  images first.

.PARAMETER OutDir
  Parent folder for the package. Defaults to D:\Orphos_1\Orphos-Distribution-Online
  (a sibling of the repo, never inside it).

.PARAMETER SkipImages
  Assemble the folder but skip the slow docker save - for testing the layout.

.PARAMETER Zip
  Also write <OutDir>\Orphos-Online.zip.

.PARAMETER AllowStale
  Package images even when they are older than their module's jar.
#>
param(
    [string] $OutDir,
    [switch] $SkipImages,
    [switch] $Zip,
    [switch] $AllowStale
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$engineSrc = Join-Path $PSScriptRoot 'online'
if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $repoRoot) 'Orphos-Distribution-Online' }
$stage = Join-Path $OutDir 'Orphos-Online'

Write-Host '==> Orphos ONLINE distribution builder' -ForegroundColor Cyan
Write-Host "    repo   : $repoRoot"
Write-Host "    output : $stage"
Write-Host ''

# Native commands write progress to stderr, which Stop turns into a terminating
# error. Run them through this so only the exit code decides.
function Invoke-Native([scriptblock] $Block) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $prev }
}

# -- 1. Docker ---------------------------------------------------------------
Invoke-Native { docker version --format '{{.Server.Version}}' *> $null }
if ($LASTEXITCODE -ne 0) { throw 'Docker engine is not reachable. Start Docker Desktop and re-run.' }

# -- 2. The eip-* image set, from the compose files --------------------------
$layerFiles = @('docker-compose.infra.yml', 'docker-compose.data.yml',
                'docker-compose.ai.yml', 'docker-compose.app.yml')

$eipImages = [System.Collections.Generic.SortedSet[string]]::new()
foreach ($f in $layerFiles) {
    $p = Join-Path $repoRoot $f
    if (-not (Test-Path $p)) { throw "Missing compose file: $p" }
    Select-String -Path $p -Pattern '^\s+image:\s*(eip-\S+)' | ForEach-Object {
        [void]$eipImages.Add($_.Matches[0].Groups[1].Value)
    }
}
Write-Host "==> $($eipImages.Count) Orphos images referenced by the compose files"

# image -> build context, so each image can be checked against its jar.
$contexts = @{}
foreach ($f in $layerFiles) {
    $json = Invoke-Native { docker compose -f (Join-Path $repoRoot $f) config --format json 2>$null }
    if ($LASTEXITCODE -ne 0 -or -not $json) { continue }
    $cfg = ($json -join "`n") | ConvertFrom-Json
    foreach ($svc in $cfg.services.PSObject.Properties) {
        $v = $svc.Value
        if ($v.image -like 'eip-*' -and $v.build) { $contexts[$v.image] = $v.build.context }
    }
}

$missing = @()
$stale = @()
foreach ($img in $eipImages) {
    $created = Invoke-Native { docker image inspect --format '{{.Created}}' $img 2>$null }
    if ($LASTEXITCODE -ne 0) { $missing += $img; continue }
    $ctx = $contexts[$img]
    if (-not $ctx) { continue }
    $jar = Get-ChildItem (Join-Path $ctx 'target') -Filter '*.jar' -EA SilentlyContinue |
           Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
    # Docker prints nanoseconds; DateTime accepts at most 7 fractional digits.
    $createdUtc = [datetime]::Parse(("$created".Trim() -replace '(\.\d{7})\d+', '$1')).ToUniversalTime()
    if ($jar -and $jar.LastWriteTimeUtc -gt $createdUtc) {
        $stale += "$img (jar $($jar.LastWriteTime.ToString('yyyy-MM-dd HH:mm')))"
    }
}
if ($missing.Count -gt 0) {
    Write-Host '[ERROR] These Orphos images have not been built on this machine:' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "          $_" }
    throw "$($missing.Count) Orphos image(s) missing. Nothing was written."
}
if ($stale.Count -gt 0) {
    Write-Host '[WARN] These images are older than the jar in their module''s target\:' -ForegroundColor Yellow
    $stale | ForEach-Object { Write-Host "          $_" }
    if (-not $AllowStale) {
        throw "Rebuild them (docker compose -p eip-app -f docker-compose.app.yml build <svc>) or pass -AllowStale."
    }
}
Write-Host '    all present and current'

# -- 3. Stage the folder -----------------------------------------------------
if (Test-Path $stage) {
    Write-Host '==> Clearing previous staging folder'
    Remove-Item -Recurse -Force $stage
}
New-Item -ItemType Directory -Force $stage | Out-Null
$utf8NoBom = New-Object Text.UTF8Encoding $false
$utf8Bom   = New-Object Text.UTF8Encoding $true

# Writes text with explicit line endings and encoding: .bat needs CRLF, .sh and
# .env need LF, and PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
function Write-Text($path, $text, $eol, $encoding) {
    $norm = ($text -replace "`r`n", "`n")
    if ($eol -eq 'crlf') { $norm = $norm -replace "`n", "`r`n" }
    [IO.File]::WriteAllText($path, $norm, $encoding)
}

Write-Host '==> Copying runtime files'
# Byte-for-byte: the third-party references stay as the repo's digests and tags,
# which is exactly what the client pulls.
foreach ($f in $layerFiles) { Copy-Item (Join-Path $repoRoot $f) $stage }

Write-Text (Join-Path $stage '.env.example') ([IO.File]::ReadAllText((Join-Path $repoRoot '.env.example'))) 'lf' $utf8NoBom

Copy-Item (Join-Path $repoRoot 'config') $stage -Recurse
# Build input for eip-fuseki, already inside that image.
Remove-Item (Join-Path $stage 'config\fuseki\apache-jena-fuseki-*.tar.gz') -Force -EA SilentlyContinue
Copy-Item (Join-Path $repoRoot 'ontology') $stage -Recurse

# drivers\ is a classpath bind mount; only the top-level jars matter, and the
# unpacked connector source tree beside them exceeds MAX_PATH.
$drivers = Join-Path $stage 'drivers'
New-Item -ItemType Directory -Force $drivers | Out-Null
Get-ChildItem (Join-Path $repoRoot 'drivers') -File |
    Where-Object { $_.Extension -in '.jar', '.md' } | Copy-Item -Destination $drivers
Write-Host '    compose files, .env.example, config\, ontology\, drivers\'

foreach ($f in Get-ChildItem $engineSrc -File) {
    $text = [IO.File]::ReadAllText($f.FullName)
    $dest = Join-Path $stage $f.Name
    switch ($f.Extension) {
        '.ps1' { Write-Text $dest $text 'lf'   $utf8Bom }
        '.bat' { Write-Text $dest $text 'crlf' $utf8NoBom }
        default { Write-Text $dest $text 'lf'  $utf8NoBom }
    }
}
Write-Host '    engine: orphos.ps1, orphos.sh, start/startmodules/stop/restart/status'

@'
This file marks a DISTRIBUTION install: prebuilt images, no source tree.

This is the ONLINE package. images\orphos-images.tar holds only the Orphos
(eip-*) images; the launchers download the third-party images and the AI models
on the first start, which therefore needs internet.

Deleting this file changes nothing about how this package starts.
'@ | Set-Content (Join-Path $stage '.orphos-distribution') -Encoding ascii

# -- 4. The images -----------------------------------------------------------
$tarGb = 'n/a'
if ($SkipImages) {
    Write-Host '==> Skipping docker save (-SkipImages)' -ForegroundColor Yellow
} else {
    $imagesDir = Join-Path $stage 'images'
    New-Item -ItemType Directory -Force $imagesDir | Out-Null
    $tar = Join-Path $imagesDir 'orphos-images.tar'
    Write-Host "==> docker save -> images\orphos-images.tar ($($eipImages.Count) images, several minutes)"
    Invoke-Native { docker save -o $tar @($eipImages) }
    if ($LASTEXITCODE -ne 0) { throw "docker save failed with exit code $LASTEXITCODE." }
    $tarBytes = (Get-Item $tar).Length
    $tarGb = [math]::Round($tarBytes / 1GB, 1)
    Set-Content -Path (Join-Path $imagesDir 'orphos-images.size') -Value $tarBytes -Encoding ascii
    Write-Host "    wrote $tarGb GB"
}

# -- 5. Documentation --------------------------------------------------------
$docs = Join-Path $stage 'documentation'
New-Item -ItemType Directory -Force $docs | Out-Null
foreach ($f in Get-ChildItem (Join-Path $engineSrc 'documentation') -File) {
    $text = [IO.File]::ReadAllText($f.FullName).Replace('{{TAR_GB}}', "$tarGb")
    Write-Text (Join-Path $docs $f.Name) $text 'lf' $utf8NoBom
}
Copy-Item (Join-Path $docs 'README.md') (Join-Path $stage 'README.md')

# -- 6. Zip ------------------------------------------------------------------
if ($Zip) {
    $zipPath = Join-Path $OutDir 'Orphos-Online.zip'
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Write-Host '==> Zipping (Windows tar.exe; Compress-Archive cannot write >2 GB entries)'
    Invoke-Native { & "$env:SystemRoot\System32\tar.exe" -a -c -f $zipPath -C $OutDir 'Orphos-Online' }
    if ($LASTEXITCODE -ne 0) { throw "zip failed with exit code $LASTEXITCODE." }
    Write-Host ("    wrote {0:N1} GB  {1}" -f ((Get-Item $zipPath).Length / 1GB), $zipPath)
}

# -- 7. Report ---------------------------------------------------------------
$files = Get-ChildItem $stage -Recurse -File
Write-Host ''
Write-Host '==> Online distribution ready' -ForegroundColor Green
Write-Host "    $stage"
Write-Host ("    {0} files, {1:N1} GB" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1GB))
Write-Host '    The client needs Docker Desktop and internet for the first start,'
Write-Host '    then double-clicks start.bat (or runs ./start.sh).'
