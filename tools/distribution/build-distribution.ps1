<#
.SYNOPSIS
  Builds the self-contained Orphos distribution handed to a client.

.DESCRIPTION
  A client machine has no source tree, no JDK and no Maven — only Docker Desktop.
  So the distribution carries the container images themselves rather than the
  means to build them:

      Orphos\
        Orphos Setup.exe        <- the wizard; one click starts everything
        app\  runtime\          <- its bundled JRE (no Java needed for the wizard)
        images\orphos-images.tar<- every runtime image, docker save'd
        docker-compose.*.yml    <- unchanged; --no-build is used at bring-up
        start.ps1 startmodules.ps1 start.bat stop.bat
        config\ scripts\ ontology\
        .env.example
        .orphos-distribution    <- marker: load images, never build

  The marker is what makes startmodules.ps1 load images/orphos-images.tar on
  first run instead of trying to compile. A developer checkout has no marker and
  keeps building exactly as before.

  Image set comes from the compose files themselves, so it cannot drift from what
  the platform actually starts.

.PARAMETER OutDir
  Where to write the distribution. Defaults to .\distribution (gitignored).

.PARAMETER SkipImages
  Assemble the folder but skip the slow docker save — for testing the layout.

.PARAMETER Compress
  Also gzip the image archive. Smaller to ship, slower to build and to load.
#>
param(
    [string] $OutDir,
    [switch] $SkipImages,
    [switch] $Compress,
    # Leave the two giant third-party images out of the archive: ollama (8 GB) and
    # docling (7.4 GB), ~15 GB of a ~27 GB package. They are public images, so the
    # client pulls them on first boot — which costs nothing in practice because the
    # AI *models* are downloaded then anyway, so that boot is already online.
    # Everything Orphos-specific still ships in the package.
    [switch] $ExcludeLargePublicImages
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutDir) { $OutDir = Join-Path $repoRoot "distribution" }
$stage = Join-Path $OutDir "Orphos"

Write-Host "==> Orphos distribution builder" -ForegroundColor Cyan
Write-Host "    repo   : $repoRoot"
Write-Host "    output : $stage"
Write-Host ""

# ── 1. Docker must be up: we read the image list from it ────────────────────
docker version --format "{{.Server.Version}}" *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker engine is not reachable. Start Docker Desktop and re-run."
}

# ── 2. The image set, taken from the compose files ──────────────────────────
$composeFiles = @(
    "docker-compose.infra.yml",
    "docker-compose.data.yml",
    "docker-compose.ai.yml",
    "docker-compose.app.yml"
) | ForEach-Object { Join-Path $repoRoot $_ }

$images = [System.Collections.Generic.HashSet[string]]::new()
foreach ($f in $composeFiles) {
    if (-not (Test-Path $f)) { throw "Missing compose file: $f" }
    Select-String -Path $f -Pattern '^\s+image:\s*(\S+)' -AllMatches | ForEach-Object {
        foreach ($m in $_.Matches) { [void]$images.Add($m.Groups[1].Value) }
    }
}
$imageList = @($images) | Sort-Object
Write-Host "==> $($imageList.Count) distinct runtime images referenced by the compose files"

# Every one must already exist locally. Building/pulling is the developer's job,
# not this script's — a silent pull here could ship an unintended version.
$missing = @()
foreach ($img in $imageList) {
    # $ErrorActionPreference is Stop for this script, and a native command writing
    # to stderr counts as a terminating error under it — so a missing image would
    # abort here instead of being collected. Suspend that for the probe: the whole
    # point is to report every missing image at once.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        docker image inspect $img 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { $missing += $img }
    } finally {
        $ErrorActionPreference = $prev
    }
}
if ($missing.Count -gt 0) {
    # Third-party images can be fetched safely — the reference (usually a digest)
    # pins the exact version, so a pull is reproducible. Orphos images cannot: they
    # only exist if this machine built them, and pulling would either fail or fetch
    # something unintended. So split the two and only ever auto-pull the former.
    $pullable = @($missing | Where-Object { $_ -notlike "eip-*" })
    $buildable = @($missing | Where-Object { $_ -like "eip-*" })

    if ($buildable.Count -gt 0) {
        Write-Host ""
        Write-Host "[ERROR] These Orphos images have not been built on this machine:" -ForegroundColor Red
        $buildable | ForEach-Object { Write-Host "          $_" }
        Write-Host ""
        Write-Host "        Run  build.bat  first. Nothing was written."
        throw "$($buildable.Count) Orphos image(s) missing."
    }

    Write-Host ""
    Write-Host "    $($pullable.Count) third-party image(s) missing - pulling them now." -ForegroundColor Yellow
    Write-Host "    (These are digest-pinned, so the version is exactly what the compose files name."
    Write-Host "     They show as <none> in 'docker images' and are removed by any image prune,"
    Write-Host "     which is the usual reason they go absent between builds.)"
    foreach ($img in $pullable) {
        Write-Host "      pulling $(($img -split '@')[0]) ..."
        $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        try {
            docker pull $img 2>&1 | Select-Object -Last 1 | ForEach-Object { Write-Host "        $_" }
            $rc = $LASTEXITCODE
        } finally { $ErrorActionPreference = $prev }
        if ($rc -ne 0) { throw "Failed to pull $img - check internet access and try again." }
    }
    Write-Host "    all pulled"
}
Write-Host "    all present"

# ── 2b. Digest-pinned images get a real tag ─────────────────────────────────
# `docker save` records RepoTags, and for a digest reference RepoTags is null —
# the identity survives only in the OCI index. Whether `docker load` then restores
# a resolvable @sha256 reference depends on the client's image store, which we do
# not control. Rather than gamble, every digest-pinned image is tagged here and
# the distribution's compose copies are rewritten to that tag. Same bytes, same
# pinned version — just an identity that save/load is guaranteed to carry.
$rewrite = @{}
$saveRefs = @()
$excluded = @()
foreach ($img in $imageList) {
    if ($ExcludeLargePublicImages -and ($img -match 'ollama/ollama|docling-serve')) {
        # Referenced by digest in compose, so the client pulls this exact version.
        $excluded += $img
        continue
    }
    if ($img -notmatch '@sha256:') { $saveRefs += $img; continue }
    # A reference can carry BOTH a tag and a digest
    # (infisical/infisical:latest-postgres@sha256:...), and a registry host can
    # carry a port (host:5000/repo). So: drop the digest, then take the last path
    # segment, then drop any tag on that segment — a colon before the last '/' is
    # a port, never a tag.
    $repo = ($img -split '@')[0]
    $leaf = ($repo -split '/')[-1]
    $leaf = ($leaf -split ':')[0]
    $tag  = "orphos-dist/$leaf" + ":pinned"
    docker tag $img $tag
    if ($LASTEXITCODE -ne 0) { throw "Could not tag $img as $tag" }
    $rewrite[$img] = $tag
    $saveRefs += $tag
    Write-Host "    pinned -> $tag"
}
if ($rewrite.Count -gt 0) {
    Write-Host "    $($rewrite.Count) digest-pinned image(s) retagged for save/load safety"
}
if ($excluded.Count -gt 0) {
    Write-Host ""
    Write-Host "    Excluded from the archive (client pulls these on first boot):" -ForegroundColor Yellow
    $excluded | ForEach-Object { Write-Host "      $(($_ -split '@')[0])" }
    Write-Host "    Their compose references are left as digests, so the version is still pinned."
}

# ── 3. Stage the folder ─────────────────────────────────────────────────────
if (Test-Path $stage) {
    Write-Host "==> Clearing previous staging folder"
    Remove-Item -Recurse -Force $stage
}
New-Item -ItemType Directory -Force $stage | Out-Null

Write-Host "==> Copying runtime files"
# Compose files are copied UNCHANGED. Their build: keys are harmless because the
# bring-up uses --no-build and every image is preloaded; leaving them untouched
# means the distribution cannot drift from the dev topology.
Get-ChildItem -Path $repoRoot -Filter "docker-compose.*.yml" | ForEach-Object {
    if ($rewrite.Count -eq 0) {
        Copy-Item $_.FullName $stage
        return
    }
    # Only the digest-pinned references change; everything else is byte-identical.
    $text = Get-Content $_.FullName -Raw
    $hits = 0
    foreach ($k in $rewrite.Keys) {
        if ($text.Contains($k)) { $text = $text.Replace($k, $rewrite[$k]); $hits++ }
    }
    Set-Content -Path (Join-Path $stage $_.Name) -Value $text -NoNewline -Encoding utf8
    if ($hits -gt 0) { Write-Host "    $($_.Name): rewrote $hits pinned reference(s)" }
}
foreach ($f in @("start.sh", "startmodules.sh", "stop.sh",
                 "start.bat", "startmodules.bat", "stop.bat", ".env.example")) {
    $src = Join-Path $repoRoot $f
    if (Test-Path $src) { Copy-Item $src $stage } else { Write-Host "    (skipped missing $f)" }
}
foreach ($d in @("config", "scripts", "ontology")) {
    $src = Join-Path $repoRoot $d
    if (Test-Path $src) {
        Copy-Item $src $stage -Recurse
        Write-Host "    $d\"
    }
}

# ── 3b. The distribution launcher ───────────────────────────────────────────
# The repo's launchers are shell scripts run through scripts\run-sh.bat, which
# needs Git Bash or WSL, and they build images with Maven. Neither is possible on
# a client machine, so the package carries its own PowerShell bring-up that only
# needs Docker. The shell launchers are shipped too (with a bundled bash below)
# for anyone who wants the shared implementation.
$launcher = Join-Path $PSScriptRoot "orphos-start.ps1"
if (-not (Test-Path $launcher)) { throw "Missing $launcher - the distribution launcher is required." }
Copy-Item $launcher $stage -Force
Write-Host "    orphos-start.ps1 (distribution launcher)"

# ── 3c. Bundled toolchain: bash, JDK, Maven ─────────────────────────────────
# Not needed by orphos-start.ps1 — it uses Docker and PowerShell only. They are
# here so the package is genuinely self-sufficient: the .sh launchers can run
# (bash), and anything that wants a JDK or Maven finds one without an install.
$toolsDir = Join-Path $stage "tools"
New-Item -ItemType Directory -Force $toolsDir | Out-Null

function Copy-Tool($label, $from, $to, $probe) {
    if (-not (Test-Path $from)) {
        Write-Host "    (skipped $label - not found at $from)" -ForegroundColor Yellow
        return $false
    }
    Write-Host "    bundling $label ..."
    Copy-Item $from $to -Recurse -Force
    $ok = (-not $probe) -or (Test-Path (Join-Path $to $probe))
    if (-not $ok) { Write-Host "    [WARN] $label copied but $probe is missing" -ForegroundColor Yellow }
    $sz = (Get-ChildItem $to -Recurse -File -EA SilentlyContinue | Measure-Object Length -Sum).Sum
    Write-Host ("      {0:N0} MB" -f ($sz/1MB))
    return $ok
}

# bash: Git for Windows' bin + usr is a working portable bash (msys runtime and
# the coreutils the launchers use). run-sh.bat honours EIP_BASH, which the wizard
# sets to this copy, so no script edit is needed.
$gitRoot = "C:\Program Files\Git"
$bashDir = Join-Path $toolsDir "bash"
if (Test-Path $gitRoot) {
    New-Item -ItemType Directory -Force $bashDir | Out-Null
    Copy-Item (Join-Path $gitRoot "bin") $bashDir -Recurse -Force
    Copy-Item (Join-Path $gitRoot "usr") $bashDir -Recurse -Force
    $bashExe = Join-Path $bashDir "bin\bash.exe"
    if (Test-Path $bashExe) {
        $sz = (Get-ChildItem $bashDir -Recurse -File -EA SilentlyContinue | Measure-Object Length -Sum).Sum
        Write-Host ("    bundled bash ({0:N0} MB)" -f ($sz/1MB))
    } else {
        Write-Host "    [WARN] bash bundled but bin\bash.exe missing" -ForegroundColor Yellow
    }
} else {
    Write-Host "    (skipped bash - Git for Windows not installed here)" -ForegroundColor Yellow
}

# JDK + Maven: resolved from this build machine. JAVA_HOME first, then the
# Adoptium default; Maven from the apache-maven-* folder in Program Files.
$jdkSrc = $env:JAVA_HOME
if (-not $jdkSrc -or -not (Test-Path $jdkSrc)) {
    $jdkSrc = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -EA SilentlyContinue |
               Where-Object { $_.Name -like "jdk-21*" } | Select-Object -First 1).FullName
}
Copy-Tool "JDK" $jdkSrc (Join-Path $toolsDir "jdk") "bin\java.exe" | Out-Null

$mvnSrc = (Get-ChildItem "C:\Program Files" -Directory -EA SilentlyContinue |
           Where-Object { $_.Name -like "apache-maven-*" } | Select-Object -First 1).FullName
Copy-Tool "Maven" $mvnSrc (Join-Path $toolsDir "maven") "bin\mvn.cmd" | Out-Null

# ── 4. The wizard, at the distribution ROOT ─────────────────────────────────
# Placement is load-bearing: SetupPaths walks UP from the launcher looking for
# start.ps1 / docker-compose.infra.yml. At the root it finds them immediately, so
# .env lands beside the compose files and "Start platform" works. Extracted
# anywhere else, the wizard writes config nowhere useful.
$wizardImage = Join-Path $repoRoot "tools\setup-wizard\dist\Orphos Setup"
if (-not (Test-Path $wizardImage)) {
    throw "Wizard app-image not found at '$wizardImage'. Run tools\setup-wizard\build-exe.ps1 first."
}
Write-Host "==> Copying the setup wizard to the distribution root"
Copy-Item (Join-Path $wizardImage "*") $stage -Recurse -Force

# ── 4b. Diagnose.bat ────────────────────────────────────────────────────────
# "Orphos Setup.exe" is a windowed (GUI-subsystem) launcher, so anything that
# fails before the UI appears collapses into a bare "Failed to launch JVM" dialog
# with no cause. This runs the same jar from a console so the operator sees the
# real stack trace. It uses the JDK bundled above rather than the wizard's own
# runtime, because jpackage strips java.exe out of that.
$diagJar = Get-ChildItem (Join-Path $stage "app") -Filter "setup-wizard-*.jar" -EA SilentlyContinue |
           Select-Object -First 1
if ($diagJar) {
    $diagnose = @"
@echo off
REM Runs the wizard exactly as "Orphos Setup.exe" does, but in a console window
REM so startup errors are visible instead of hidden behind a dialog.
setlocal
set HERE=%~dp0
echo Orphos Setup - diagnostic launcher
echo -----------------------------------------------------------------------
echo.
if not exist "%HERE%tools\jdk\bin\java.exe" (
  echo [ERROR] tools\jdk\bin\java.exe is missing - this package is incomplete.
  goto :done
)
"%HERE%tools\jdk\bin\java.exe" -Djava.awt.headless=false -jar "%HERE%app\$($diagJar.Name)"
echo.
echo -----------------------------------------------------------------------
echo Exit code: %ERRORLEVEL%
echo.
echo If the wizard started, open http://localhost:8099 in a browser.
echo If it failed, the cause is above - the last "Caused by:" line is the one
echo that matters.
:done
echo.
pause
"@
    Set-Content -Path (Join-Path $stage "Diagnose.bat") -Value $diagnose -Encoding ascii
    Write-Host "    Diagnose.bat (console launcher, uses the bundled JDK)"
} else {
    Write-Host "    [WARN] no wizard jar found - Diagnose.bat not written" -ForegroundColor Yellow
}

# ── 5. The marker ───────────────────────────────────────────────────────────
@"
This file marks a DISTRIBUTION install (prebuilt images, no source tree).

startmodules.ps1 checks for it: when images are missing it loads
images\orphos-images.tar instead of trying to compile from source, and fails with
a clear message if an image is still absent afterwards.

Delete this file only if you have the full Orphos source tree, a JDK and Maven
here and genuinely want builds attempted.
"@ | Set-Content (Join-Path $stage ".orphos-distribution") -Encoding ascii

# ── 6. The images ───────────────────────────────────────────────────────────
if ($SkipImages) {
    Write-Host "==> Skipping docker save (-SkipImages)" -ForegroundColor Yellow
} else {
    $imagesDir = Join-Path $stage "images"
    New-Item -ItemType Directory -Force $imagesDir | Out-Null
    $tar = Join-Path $imagesDir "orphos-images.tar"
    Write-Host "==> docker save -> images\orphos-images.tar"
    Write-Host "    $($imageList.Count) images. Shared layers are stored once, so this is"
    Write-Host "    smaller than the sum of 'docker images' sizes. Several minutes."
    docker save -o $tar @saveRefs
    if ($LASTEXITCODE -ne 0) { throw "docker save failed with exit code $LASTEXITCODE." }
    $tarBytes = (Get-Item $tar).Length
    $tarGb = [math]::Round($tarBytes / 1GB, 2)
    Write-Host "    wrote $tarGb GB"

    # Record the exact byte count beside the archive. The bring-up compares against
    # this before loading, so a truncated copy or a half-finished extraction is
    # caught up front with a plain-English message instead of failing midway
    # through `docker load` with something cryptic. The client never has to check
    # a file size by hand.
    Set-Content -Path (Join-Path $imagesDir "orphos-images.size") -Value $tarBytes -Encoding ascii
    Write-Host "    recorded expected size for the integrity check"

    if ($Compress) {
        Write-Host "==> Compressing the archive (gzip)"
        $gz = "$tar.gz"
        $in = [IO.File]::OpenRead($tar)
        $out = [IO.File]::Create($gz)
        $gzip = New-Object IO.Compression.GZipStream($out, [IO.Compression.CompressionLevel]::Optimal)
        try { $in.CopyTo($gzip) } finally { $gzip.Dispose(); $out.Dispose(); $in.Dispose() }
        Remove-Item $tar -Force
        Write-Host "    wrote $([math]::Round((Get-Item $gz).Length / 1GB, 2)) GB (gz)"
    }
}

# ── 7. Client-facing README ─────────────────────────────────────────────────
@"
Orphos - installation
=====================

Three steps. Nothing to configure, nothing to build.

  1. Install Docker Desktop, choosing "Linux containers" and the WSL2 backend.
     Start it and wait for the whale icon in the tray to stop animating.

  2. Extract this folder anywhere. If the zip's Properties shows an "Unblock"
     checkbox, tick it before extracting.

  3. Double-click "Orphos Setup.exe".
     Your browser opens the setup wizard. Answer the six screens, press Install,
     then press "Start platform".

The first start takes 20-40 minutes: it unpacks the bundled container images
(nothing is downloaded) and then fetches the AI models. Later starts take a
couple of minutes. When the log settles, open http://localhost:3001

That is the whole installation. Everything below is reference only.


What this machine needs
-----------------------
  Docker Desktop     Linux containers mode, WSL2 backend
  Windows 10 or 11   64-bit, virtualization enabled
  16 GB RAM          32 GB for the full stack
  ~35 GB free disk
  Internet           only on first start, for the AI models

NOT needed: Java, Maven, Node, or any source code. The wizard carries its own
Java runtime and every service ships as a prebuilt container image.


If something goes wrong
-----------------------
"Orphos Setup.exe" shows an error dialog
    Run "Diagnose.bat" here. It starts the same wizard in a console window so the
    real error is visible. The last line beginning "Caused by:" is the cause.

The start log says the image archive is incomplete
    The copy or extraction was truncated. The installer checks this itself and
    stops rather than failing halfway. Copy the package again and re-extract; if
    Windows struggles with the large file inside, use 7-Zip (https://7-zip.org).

Anything mentioning Docker
    Make sure Docker Desktop is running and set to Linux containers. The tray
    whale icon should be steady, not animating.


Day to day
----------
  start.bat     start the platform
  stop.bat      stop it

Keep .env safe. It holds this installation's passwords, and they are written into
the databases on first start - changing them later breaks sign-in unless the data
volumes are reset too.
"@ | Set-Content (Join-Path $stage "README.txt") -Encoding utf8

# When images were excluded, the first start is NOT offline — correct the README
# rather than shipping a claim the client will find untrue on step 3.
if ($excluded.Count -gt 0) {
    $rp = Join-Path $stage "README.txt"
    $rt = Get-Content $rp -Raw
    $rt = $rt -replace '(?s)The first start takes 20-40 minutes.*?fetches the AI models\.',
        'The first start takes 20-40 minutes and NEEDS AN INTERNET CONNECTION: it unpacks the bundled container images, downloads two more (the AI engine and the document reader), and then fetches the AI models.'
    $rt = $rt -replace 'Internet           only on first start, for the AI models',
        'Internet           required on first start (AI models + 2 images)'
    Set-Content $rp -Value $rt -Encoding utf8 -NoNewline
    Write-Host "    README adjusted: first start requires internet in this build"
}

# ── 8. Report ───────────────────────────────────────────────────────────────
$files = (Get-ChildItem $stage -Recurse -File)
$total = [math]::Round(($files | Measure-Object Length -Sum).Sum / 1GB, 2)
Write-Host ""
Write-Host "==> Distribution ready" -ForegroundColor Green
Write-Host "    $stage"
Write-Host "    $($files.Count) files, $total GB"
Write-Host ""
Write-Host "    Hand over the 'Orphos' folder itself, or zip it. The client needs"
Write-Host "    only Docker Desktop, then double-clicks Orphos Setup.exe."

