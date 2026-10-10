<#
  Orphos - client distribution engine (Docker only).

  This is the single implementation behind start.bat / stop.bat / restart.bat /
  status.bat in a client package. It deliberately uses NOTHING but Docker and
  Windows PowerShell 5.1, which ships with Windows: a client machine has no
  source tree, no JDK, no Maven, no Node and no bash.

  It mirrors the repo's start.sh bring-up sequence exactly - same four compose
  layers, same compose profiles, same bootstrap steps (Infisical seed wait,
  ArcadeDB schema, connections-seed) - but every `up` passes --no-build, because
  the Orphos images arrive prebuilt in images\orphos-images.tar and there is
  nothing here to compile.

  ONLINE package: only the Orphos (eip-*) images are bundled. Third-party images
  (Postgres, Keycloak, Ollama, ...) and the AI models are downloaded on first
  start, so that start needs internet. Later starts do not.

  Usage (from the package root):
      .\orphos.ps1 start
      .\orphos.ps1 stop
      .\orphos.ps1 restart
      .\orphos.ps1 status
      .\orphos.ps1 modules admin,core      # only the listed functionalities
#>
[CmdletBinding()]
param(
    # Explicit Position: once any parameter declares one, the others stop being
    # positional, and `orphos.ps1 modules x` would silently run 'start'.
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart', 'status', 'modules')]
    [string] $Command = 'start',

    # For 'modules': the functionalities to start, comma- and/or space-separated
    # (startmodules.bat admin,core  or  startmodules.bat admin core).
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]] $Modules = @(),

    # Skip opening the browser at the end of a successful start.
    [switch] $NoBrowser,

    # Per-layer health budget. The application layer is the slow one on a cold
    # machine: 36 JVMs starting at once against a first-boot database.
    [int] $HealthTimeoutSeconds = 900
)

# Continue rather than Stop: a native command writing to stderr counts as a
# terminating error under Stop, and docker writes progress there routinely.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot

# Compose resolves a relative `.env` and relative bind-mount sources against the
# working directory, so every docker call in this script runs from the package
# root and names its compose file relatively.
Set-Location $root

$ORPHOS_URL   = 'http://localhost:3001'
$SIGNIN_URL   = 'http://localhost:8443'
$PLATFORM_API = 'http://localhost:8090'

# Bring-up order is a dependency order, not a preference: app services read
# secrets seeded by infra and query schema created against data.
$Layers = @(
    [pscustomobject]@{ Project = 'eip-infra'; File = 'docker-compose.infra.yml'; Label = 'Infrastructure' }
    [pscustomobject]@{ Project = 'eip-data' ; File = 'docker-compose.data.yml' ; Label = 'Data layer'     }
    [pscustomobject]@{ Project = 'eip-ai'   ; File = 'docker-compose.ai.yml'   ; Label = 'AI layer'       }
    [pscustomobject]@{ Project = 'eip-app'  ; File = 'docker-compose.app.yml'  ; Label = 'Application'    }
)

function Write-Step($msg) { Write-Host "[orphos] $msg" }
function Write-Ok  ($msg) { Write-Host "[  OK  ] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "[ WARN ] $msg" -ForegroundColor Yellow }
function Write-Fail($msg) { Write-Host "[ERROR ] $msg" -ForegroundColor Red }

function Write-Banner($title) {
    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host '============================================================' -ForegroundColor Cyan
    Write-Host ''
}

# -----------------------------------------------------------------------------
# Docker preflight
#
# Checked explicitly instead of letting compose fail: "docker is not recognized",
# "engine not running" and "Windows containers mode" are the three ways this goes
# wrong on a client machine, and each deserves a plain answer rather than a
# compose stack trace.
# -----------------------------------------------------------------------------
function Test-DockerReady {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Fail 'Docker is not installed on this machine.'
        Write-Host ''
        Write-Host '         Orphos runs entirely in Docker containers, so Docker Desktop is'
        Write-Host '         the one prerequisite. Install it from:'
        Write-Host ''
        Write-Host '             https://www.docker.com/products/docker-desktop/'
        Write-Host ''
        Write-Host '         Choose LINUX CONTAINERS and the WSL2 backend during setup, start'
        Write-Host '         Docker Desktop, then run this again.'
        return $false
    }

    & docker version --format '{{.Server.Version}}' *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail 'Docker is installed but the engine is not running.'
        Write-Host ''
        Write-Host '         Start Docker Desktop and wait for the whale icon in the system'
        Write-Host '         tray to stop animating, then run this again.'
        return $false
    }

    $osType = (& docker info --format '{{.OSType}}' 2>$null)
    if ($osType -and $osType.Trim() -ne 'linux') {
        Write-Fail "Docker is in Windows containers mode (detected: $($osType.Trim()))."
        Write-Host ''
        Write-Host '         Every Orphos image is a Linux image. Right-click the Docker Desktop'
        Write-Host "         tray icon, choose 'Switch to Linux containers...', then run this again."
        return $false
    }

    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "The Docker Compose v2 plugin is missing ('docker compose' failed)."
        Write-Host '         Update Docker Desktop to a current version and run this again.'
        return $false
    }

    $ver = (& docker version --format '{{.Server.Version}}' 2>$null)
    Write-Ok "Docker engine $ver is running (Linux containers)."
    return $true
}

# -----------------------------------------------------------------------------
# .env
#
# In the development repo the setup wizard writes this file. A client package has
# no wizard, so the first start generates it from .env.example and fills every
# blank secret with a fresh random value - this installation's own passwords,
# not shared ones baked into the package.
#
# Generated ONCE. The passwords are written into Keycloak, Postgres, ArcadeDB and
# Infisical on first boot, so regenerating them later locks the platform out of
# its own data. Every later start reuses the file untouched.
# -----------------------------------------------------------------------------
function New-Secret {
    param([int] $Length = 28)
    # Deliberately alphanumeric and free of look-alike glyphs: the value travels
    # through .env (where a '$' would be interpolated by compose), JDBC URLs and
    # Basic-auth headers, and is occasionally read aloud off a screen.
    $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function New-HexSecret {
    param([int] $Bytes = 16)
    $b = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($b) } finally { $rng.Dispose() }
    ($b | ForEach-Object { $_.ToString('x2') }) -join ''
}

function New-Base64Secret {
    param([int] $Bytes = 32)
    $b = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($b) } finally { $rng.Dispose() }
    [Convert]::ToBase64String($b)
}

function Initialize-EnvFile {
    $envPath = Join-Path $root '.env'
    if (Test-Path $envPath) {
        Write-Ok 'Using the existing .env (this installation is already configured).'
        return $true
    }

    $examplePath = Join-Path $root '.env.example'
    if (-not (Test-Path $examplePath)) {
        Write-Fail '.env.example is missing - this package is incomplete.'
        return $false
    }

    Write-Step 'First run: generating .env with this installation''s own passwords.'

    # Key -> generator. Formats follow the notes in .env.example: Infisical wants
    # a 16-byte hex encryption key and a 32-byte base64 auth secret, and the
    # internal service token must be 32+ chars or platform-core fails closed.
    $generated = @{
        'KEYCLOAK_ADMIN_PASSWORD'    = (New-Secret 28)
        'ARCADEDB_PASSWORD'          = (New-Secret 28)
        'POSTGRES_PASSWORD'          = (New-Secret 28)
        'FUSEKI_ADMIN_PASSWORD'      = (New-Secret 28)
        'INFISICAL_DB_PASSWORD'      = (New-Secret 28)
        'INFISICAL_ADMIN_PASSWORD'   = (New-Secret 28)
        'INFISICAL_ENCRYPTION_KEY'   = (New-HexSecret 16)
        'INFISICAL_AUTH_SECRET'      = (New-Base64Secret 32)
        'EIP_INTERNAL_SERVICE_TOKEN' = (New-HexSecret 24)
    }

    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in (Get-Content $examplePath)) {
        $matched = $false
        foreach ($key in $generated.Keys) {
            # Only ever fill a key that is present AND blank. A value already
            # written into .env.example is a deliberate default and is left alone.
            if ($line -match "^\s*$([regex]::Escape($key))\s*=\s*$") {
                $lines.Add("$key=$($generated[$key])")
                $matched = $true
                break
            }
        }
        if (-not $matched) { $lines.Add($line) }
    }

    # Infisical's DB URI has to agree with the password generated just above.
    $infisicalUri = "postgres://infisical:$($generated['INFISICAL_DB_PASSWORD'])@eip-infisical-db:5432/infisical"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*INFISICAL_DB_CONNECTION_URI\s*=\s*$') {
            $lines[$i] = "INFISICAL_DB_CONNECTION_URI=$infisicalUri"
        }
    }

    # LF, and no BOM: the same file is read by compose, by this script and by the
    # Linux engine, and LF is the one form all three handle identically.
    [IO.File]::WriteAllText($envPath, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false))

    # A blank secret here means a substitution silently missed - which surfaces
    # much later as an unexplained authentication failure. Catch it now.
    $check = Get-EnvMap
    $blank = @()
    foreach ($k in $generated.Keys + @('INFISICAL_DB_CONNECTION_URI')) {
        if ([string]::IsNullOrWhiteSpace($check[$k])) { $blank += $k }
    }
    if ($blank.Count -gt 0) {
        Write-Fail "Generated .env is missing values for: $($blank -join ', ')"
        Write-Host '         The .env.example template in this package is not what the'
        Write-Host '         launcher expects. Do not start with this .env - remove it and'
        Write-Host '         report the problem.'
        return $false
    }

    Write-Ok '.env created. Keep it safe - it holds this installation''s passwords.'
    Write-Host '         They are written into the databases on first start, so changing'
    Write-Host '         them later breaks sign-in unless the data volumes are reset too.'
    return $true
}

function Get-EnvMap {
    $map = @{}
    $envPath = Join-Path $root '.env'
    if (-not (Test-Path $envPath)) { return $map }
    foreach ($line in (Get-Content $envPath)) {
        if ($line -match '^\s*#') { continue }
        $i = $line.IndexOf('=')
        if ($i -lt 1) { continue }
        $map[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim()
    }
    return $map
}

# -----------------------------------------------------------------------------
# Compose profiles - the same derivation start.sh performs, so a client starts
# exactly the set of containers the development machine would.
# -----------------------------------------------------------------------------
function Set-ComposeProfiles {
    param([hashtable] $EnvMap)

    function EnvBool($key, $default) {
        if (-not $EnvMap.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($EnvMap[$key])) { return $default }
        $v = $EnvMap[$key].ToLower()
        return -not ($v -eq 'false' -or $v -eq '0')
    }

    $demoMode  = EnvBool 'DEMO_MODE' $true
    $bundledDb = EnvBool 'EIP_BUNDLED_DB' $true

    $profiles = @()
    # DEMO_MODE=true is the light stack; 'full' adds Kafka, Fluent Bit, Jaeger,
    # VictoriaLogs, Prometheus and Perses.
    if (-not $demoMode) { $profiles += 'full' }
    if (EnvBool 'EIP_BUNDLED_ARCADEDB'     $bundledDb) { $profiles += 'bundled-arcade' }
    if (EnvBool 'EIP_BUNDLED_POSTGRES'     $bundledDb) { $profiles += 'bundled-postgres' }
    if (EnvBool 'EIP_BUNDLED_INFISICAL_DB' $bundledDb) { $profiles += 'bundled-infisical-db' }
    if (EnvBool 'EIP_BUNDLED_DOCLING'      $true)      { $profiles += 'bundled-docling' }

    $env:COMPOSE_PROFILES = ($profiles -join ',')

    # The AI layer is its own compose file rather than a profile, and the bundled
    # ArcadeDB gates the schema step, so both are kept for the caller.
    $script:BundledAi     = EnvBool 'EIP_BUNDLED_AI' $true
    $script:BundledArcade = EnvBool 'EIP_BUNDLED_ARCADEDB' $bundledDb

    Write-Step "Profiles: $($env:COMPOSE_PROFILES)"
}

# -----------------------------------------------------------------------------
# Images
#
# The expected set is parsed from the compose files that ship in this package,
# so it cannot drift from what the bring-up actually starts.
# -----------------------------------------------------------------------------
function Get-ExpectedImages {
    $images = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($layer in $Layers) {
        $path = Join-Path $root $layer.File
        if (-not (Test-Path $path)) { continue }
        Select-String -Path $path -Pattern '^\s+image:\s*(\S+)' -AllMatches | ForEach-Object {
            foreach ($m in $_.Matches) { [void]$images.Add($m.Groups[1].Value) }
        }
    }
    return @($images)
}

# This is the ONLINE package: images\orphos-images.tar carries only the Orphos
# (eip-*) images, which exist nowhere else. Every third-party image is pulled
# from its public registry on first start instead - the compose files pin most
# of them by digest or exact tag, so the client gets the same bytes this
# package was tested with.
function Import-BundledImages {
    $expected = @(Get-ExpectedImages | Where-Object { $_ -like 'eip-*' })
    $missing = @()
    foreach ($img in $expected) {
        & docker image inspect $img *> $null
        if ($LASTEXITCODE -ne 0) { $missing += $img }
    }

    if ($missing.Count -eq 0) {
        Write-Ok "All $($expected.Count) Orphos images are already loaded - skipping the load step."
        return $true
    }

    $tar = Join-Path $root 'images\orphos-images.tar'
    if (-not (Test-Path $tar)) {
        Write-Fail "$($missing.Count) Orphos image(s) are missing and images\orphos-images.tar is not here."
        Write-Host '         This package is incomplete. Copy it again and re-extract.'
        return $false
    }

    # Integrity gate. A truncated copy or a half-finished extraction is the
    # likeliest way a large package arrives broken, and `docker load` only reports
    # it minutes in, cryptically. Catch it here, in plain words, up front.
    $sizeFile = Join-Path $root 'images\orphos-images.size'
    $actual = (Get-Item $tar).Length
    if (Test-Path $sizeFile) {
        $expectedBytes = [int64](Get-Content $sizeFile -Raw).Trim()
        if ($actual -ne $expectedBytes) {
            Write-Fail 'The image archive is incomplete.'
            Write-Host ("         images\orphos-images.tar is {0:N2} GB but should be {1:N2} GB." -f ($actual / 1GB), ($expectedBytes / 1GB))
            Write-Host '         The copy or extraction was cut short. Copy the package again and'
            Write-Host '         re-extract it (use 7-Zip if Windows struggles with the large file),'
            Write-Host '         then run this again.'
            return $false
        }
    }

    Write-Step ("First run: loading the bundled Orphos images ({0:N1} GB)." -f ($actual / 1GB))
    Write-Host '         One-time step, several minutes.'
    # Out-Host keeps docker's progress visible without it becoming this
    # function's return value (see the note in Start-Layer).
    & docker load -i $tar | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Fail 'docker load failed.'
        Write-Host '         The usual cause is disk space - it needs roughly twice the archive'
        Write-Host '         size free. Check Docker Desktop > Settings > Resources.'
        return $false
    }
    Write-Ok 'Images loaded.'
    return $true
}

# Third-party images. Only those the ACTIVE profiles start are pulled - asking
# compose for the resolved image list (rather than grepping every image: line)
# keeps a demo install from downloading Kafka, Jaeger and the rest of the
# 'full' stack it will never run. Must run after Set-ComposeProfiles.
#
# For a modules start, -AppServices narrows the app layer to the selected
# services (compose adds their dependencies) and -NeedAi gates the AI layer.
function Get-ActiveThirdPartyImages {
    param([string[]] $AppServices = @(), [bool] $NeedAi = $true)
    $images = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($layer in $Layers) {
        if ($layer.Project -eq 'eip-ai' -and -not ($script:BundledAi -and $NeedAi)) { continue }
        if (-not (Test-Path (Join-Path $root $layer.File))) { continue }
        $only = @()
        if ($layer.Project -eq 'eip-app') { $only = $AppServices }
        foreach ($img in (& docker compose -f $layer.File config --images @only 2>$null)) {
            $img = "$img".Trim()
            if ($img -and $img -notlike 'eip-*') { [void]$images.Add($img) }
        }
    }
    # connections-seed is started by name outside the profile set, so its image
    # is always needed.
    [void]$images.Add('busybox:1.36')
    return @($images)
}

function Install-ThirdPartyImages {
    param([string[]] $AppServices = @(), [bool] $NeedAi = $true)
    $missing = @()
    foreach ($img in (Get-ActiveThirdPartyImages -AppServices $AppServices -NeedAi $NeedAi)) {
        & docker image inspect $img *> $null
        if ($LASTEXITCODE -ne 0) { $missing += $img }
    }
    if ($missing.Count -eq 0) {
        Write-Ok 'All third-party images are present.'
        return $true
    }

    Write-Step "Downloading $($missing.Count) third-party image(s) from their public registries."
    Write-Host '         One-time step; needs internet. The AI engine and document reader'
    Write-Host '         are several GB each, so this can take a while on a slow line.'
    $failed = @()
    foreach ($img in $missing) {
        Write-Host "         pulling $(($img -split '@')[0]) ..."
        # Out-Host: keeps docker's output out of this function's return value.
        & docker pull $img | Out-Host
        if ($LASTEXITCODE -ne 0) { $failed += $img }
    }
    if ($failed.Count -gt 0) {
        Write-Fail "Could not download $($failed.Count) image(s):"
        $failed | ForEach-Object { Write-Host "           $_" }
        Write-Host '         Check the internet connection (and any proxy configured in'
        Write-Host '         Docker Desktop > Settings > Resources > Proxies), then run this again.'
        Write-Host '         Images already downloaded are kept.'
        return $false
    }
    Write-Ok 'Third-party images downloaded.'
    return $true
}

# -----------------------------------------------------------------------------
# Ollama models
#
# The AI models are NOT part of any image. This online package does not bundle
# them either: they are pulled into the Ollama volumes once the AI layer is up,
# exactly as the repo's start.sh does, and kept there for every later start.
# -----------------------------------------------------------------------------
$OllamaModels = @(
    [pscustomobject]@{ Container = 'eip-ollama'   ; Model = 'granite3.1-dense:2b' }
    [pscustomobject]@{ Container = 'eip-ollama'   ; Model = 'phi3.5'              }
    [pscustomobject]@{ Container = 'eip-ollama'   ; Model = 'qwen2.5:1.5b'        }
    [pscustomobject]@{ Container = 'eip-ollama-hi'; Model = 'qwen2.5:1.5b'        }
    [pscustomobject]@{ Container = 'eip-ollama-hi'; Model = 'bge-m3'              }
)

function Install-OllamaModels {
    foreach ($m in $OllamaModels) {
        $listing = (& docker exec $m.Container ollama list 2>$null) -join "`n"
        if ($listing -match [regex]::Escape($m.Model)) { continue }
        Write-Step "Downloading AI model $($m.Model) into $($m.Container) (one-time, needs internet)..."
        & docker exec $m.Container ollama pull $m.Model | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "Could not download $($m.Model). AI answers will fail until it is present;"
            Write-Host '         run start.bat again once the internet connection is back.'
        }
    }
    Write-Ok 'AI models ready.'
}

# -----------------------------------------------------------------------------
# Network. Declared `external: true` in every compose file, so it has to exist
# before the first `up` rather than being created by compose.
# -----------------------------------------------------------------------------
function Initialize-Network {
    & docker network inspect eip-net *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Ok 'Docker network eip-net is present.'
        return $true
    }
    Write-Step 'Creating the eip-net Docker network...'
    & docker network create eip-net *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail 'Could not create the eip-net network.'
        return $false
    }
    Write-Ok 'Network eip-net created.'
    return $true
}

# -----------------------------------------------------------------------------
# Layer bring-up
#
# --no-build is the load-bearing flag. 36 of these services still carry a
# `build:` key (the compose files are copied from the repo unchanged, so the
# distribution cannot drift from the dev topology) and without it compose would
# try to compile them against a source tree that is not here.
# -----------------------------------------------------------------------------
function Start-Layer {
    param([string] $Project, [string] $File, [string] $Label)

    if (-not (Test-Path (Join-Path $root $File))) {
        Write-Fail "Missing $File - this package is incomplete."
        return $false
    }
    Write-Step "Starting $Label ..."
    # Out-Host, not a bare call: a native command's stdout joins the FUNCTION's
    # output stream, so without this the return value becomes @(compose output,
    # $false) - an array, which is truthy, and a failed layer would sail through
    # the caller's `if (-not (Start-Layer ...))` check unnoticed.
    & docker compose -p $Project -f $File up -d --no-build --remove-orphans | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "$Label failed to start. Inspect it with:"
        Write-Host "             docker compose -p $Project -f $File logs"
        return $false
    }
    return $true
}

# Waits until nothing in the project is still 'starting'. Containers without a
# healthcheck report no health at all and are treated as ready.
function Wait-Layer {
    param([string] $Project, [string] $Label)

    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    $lastReport = ''
    while ((Get-Date) -lt $deadline) {
        $ids = @(& docker compose -p $Project ps -q 2>$null | Where-Object { $_ })
        if ($ids.Count -eq 0) { return }

        $starting = 0; $unhealthy = 0
        foreach ($id in $ids) {
            $h = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $id 2>$null)
            if     ($h -eq 'starting')  { $starting++ }
            elseif ($h -eq 'unhealthy') { $unhealthy++ }
        }

        if ($starting -eq 0) {
            if ($unhealthy -gt 0) { Write-Warn "$Label is up, but $unhealthy container(s) report unhealthy." }
            else                  { Write-Ok   "$Label healthy." }
            return
        }

        $report = "$Label - $starting of $($ids.Count) container(s) still starting..."
        if ($report -ne $lastReport) { Write-Host "         $report"; $lastReport = $report }
        Start-Sleep -Seconds 5
    }
    Write-Warn "$Label is still settling after $HealthTimeoutSeconds s - continuing anyway."
}

# The Infisical seed is a one-shot job that writes the machine identity every app
# service mounts. Starting the app layer before it finishes gives 36 services a
# missing-secret failure, so the wait is not optional.
function Wait-SeedJob {
    param([string] $Container, [int] $TimeoutSeconds = 240)

    Write-Step "Waiting for $Container to finish seeding..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $state = (& docker inspect --format '{{.State.Status}}' $Container 2>$null)
        if (-not $state) { Start-Sleep -Seconds 3; continue }
        if ($state.Trim() -eq 'exited') {
            $code = (& docker inspect --format '{{.State.ExitCode}}' $Container 2>$null)
            if ($code -and $code.Trim() -eq '0') { Write-Ok "$Container completed."; return $true }
            Write-Warn "$Container exited with code $($code.Trim()) - continuing, but sign-in may fail."
            return $false
        }
        Start-Sleep -Seconds 3
    }
    Write-Warn "$Container did not finish within $TimeoutSeconds s - continuing anyway."
    return $false
}

# -----------------------------------------------------------------------------
# ArcadeDB schema. A PowerShell port of scripts/init-arcadedb-schema.sh, which
# needs bash and curl - neither is on a client machine. Every statement is
# IF NOT EXISTS, so running it on every start is a no-op after the first.
# -----------------------------------------------------------------------------
function Initialize-ArcadeSchema {
    param([hashtable] $EnvMap)

    $password = $EnvMap['ARCADEDB_PASSWORD']
    if ([string]::IsNullOrWhiteSpace($password)) {
        Write-Warn 'ARCADEDB_PASSWORD is not set in .env - skipping schema initialisation.'
        return
    }

    $base = 'http://localhost:2480'
    $auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("root:$password"))
    $headers = @{ Authorization = $auth }
    $db = 'orphos'

    Write-Step 'Initialising the ArcadeDB schema...'

    # ArcadeDB reports healthy before its HTTP API accepts commands, so poll.
    $ready = $false
    for ($i = 0; $i -lt 40; $i++) {
        try {
            Invoke-RestMethod -Uri "$base/api/v1/ready" -Headers $headers -TimeoutSec 5 -ErrorAction Stop | Out-Null
            $ready = $true
            break
        } catch { Start-Sleep -Seconds 3 }
    }
    if (-not $ready) {
        Write-Warn 'ArcadeDB did not become reachable - skipping schema initialisation.'
        Write-Host '         The services create what they need on first use; this is usually harmless.'
        return
    }

    function Invoke-Arcade($uri, $bodyObject) {
        try {
            Invoke-RestMethod -Uri $uri -Method Post -Headers $headers -ContentType 'application/json' -Body ($bodyObject | ConvertTo-Json -Compress) -TimeoutSec 30 -ErrorAction Stop | Out-Null
        } catch {
            # "already exists" is the expected answer on every start after the
            # first, and IF NOT EXISTS covers the rest. Nothing here is fatal.
        }
    }

    Invoke-Arcade "$base/api/v1/server" @{ command = "create database $db" }

    foreach ($v in @('Incident','ITSystem','CostCentre','SLAMetric','BudgetItem','Alert','Decision','Project')) {
        Invoke-Arcade "$base/api/v1/command/$db" @{ language = 'sql'; command = "CREATE VERTEX TYPE $v IF NOT EXISTS" }
    }
    foreach ($e in @('IMPACTS','FUNDS','HAS_INCIDENT','HAS_SLA_METRIC','DEPENDS_ON','TRIGGERS')) {
        Invoke-Arcade "$base/api/v1/command/$db" @{ language = 'sql'; command = "CREATE EDGE TYPE $e IF NOT EXISTS" }
    }
    foreach ($t in @('Incident','Alert')) {
        Invoke-Arcade "$base/api/v1/command/$db" @{ language = 'sql'; command = "CREATE PROPERTY $t.id IF NOT EXISTS STRING" }
        Invoke-Arcade "$base/api/v1/command/$db" @{ language = 'sql'; command = "CREATE INDEX IF NOT EXISTS ON $t(id) UNIQUE" }
    }

    Write-Ok 'ArcadeDB schema ready.'
}

# -----------------------------------------------------------------------------
# Status
# -----------------------------------------------------------------------------
function Get-ServiceRows {
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($layer in $Layers) {
        $ids = @(& docker compose -p $layer.Project ps -a -q 2>$null | Where-Object { $_ })
        foreach ($id in $ids) {
            $line = (& docker inspect --format '{{.Name}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' $id 2>$null)
            if (-not $line) { continue }
            $parts = $line.Split('|')
            $rows.Add([pscustomobject]@{
                Layer  = $layer.Label
                Name   = $parts[0].TrimStart('/')
                State  = $parts[1]
                Health = $parts[2]
            })
        }
    }
    return $rows
}

function Show-Status {
    Write-Banner 'Orphos Services'

    $rows = Get-ServiceRows
    if ($rows.Count -eq 0) {
        Write-Host '  No Orphos containers exist yet.'
        Write-Host ''
        Write-Host '  Orphos Status: NOT INSTALLED'
        Write-Host ''
        Write-Host '  Run  start.bat  to bring the platform up.'
        Write-Host ''
        return
    }

    $running = 0; $stopped = 0; $unhealthy = 0
    $width = ($rows | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
    if ($width -lt 24) { $width = 24 }

    $currentLayer = ''
    foreach ($r in ($rows | Sort-Object Layer, Name)) {
        if ($r.Layer -ne $currentLayer) {
            Write-Host ''
            Write-Host "  $($r.Layer)" -ForegroundColor Cyan
            Write-Host "  $('-' * ($width + 22))"
            $currentLayer = $r.Layer
        }

        $status = $r.State
        $colour = 'Gray'
        if ($r.State -eq 'running') {
            $running++
            $colour = 'Green'
            if     ($r.Health -eq 'healthy')   { $status = 'Running (healthy)' }
            elseif ($r.Health -eq 'starting')  { $status = 'Starting';  $colour = 'Yellow' }
            elseif ($r.Health -eq 'unhealthy') { $status = 'Running (UNHEALTHY)'; $colour = 'Red'; $unhealthy++ }
            else                               { $status = 'Running' }
        } else {
            # A completed seed job is 'exited 0' and is not a fault.
            $stopped++
            $status = (Get-Culture).TextInfo.ToTitleCase($r.State)
            $colour = 'DarkYellow'
        }

        Write-Host ("  {0,-$width}  " -f $r.Name) -NoNewline
        Write-Host $status -ForegroundColor $colour
    }

    Write-Host ''
    Write-Host "  $('=' * ($width + 22))"
    if ($running -gt 0 -and $unhealthy -eq 0) {
        Write-Host '  Orphos Status: RUNNING' -ForegroundColor Green
    } elseif ($running -gt 0 -and $unhealthy -gt 0) {
        Write-Host "  Orphos Status: DEGRADED ($unhealthy unhealthy)" -ForegroundColor Yellow
    } else {
        Write-Host '  Orphos Status: STOPPED' -ForegroundColor DarkYellow
    }
    Write-Host "  $running running, $stopped not running, $($rows.Count) total"
    Write-Host ''
    if ($running -gt 0) {
        Write-Host "  Orphos        $ORPHOS_URL"
        Write-Host "  Sign-in       $SIGNIN_URL"
        Write-Host "  Platform API  $PLATFORM_API"
        Write-Host ''
    }
}

# Polls the UI itself rather than trusting container health: the shell container
# is healthy long before the services behind it can answer a sign-in.
function Wait-ForUi {
    param([int] $TimeoutSeconds = 300)

    Write-Step 'Waiting for the Orphos web interface to answer...'
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $ORPHOS_URL -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) {
                Write-Ok "Orphos is answering at $ORPHOS_URL"
                return $true
            }
        } catch { Start-Sleep -Seconds 5 }
    }
    Write-Warn "The web interface did not answer within $TimeoutSeconds s."
    Write-Host '         The containers are up; the services may still be seeding. Try'
    Write-Host "         $ORPHOS_URL in a browser in a few minutes, or run status.bat."
    return $false
}

# -----------------------------------------------------------------------------
# Commands
# -----------------------------------------------------------------------------
function Invoke-Start {
    Write-Banner 'Orphos - starting'

    if (-not (Test-DockerReady))    { return 1 }
    if (-not (Initialize-EnvFile))  { return 1 }

    $envMap = Get-EnvMap
    Set-ComposeProfiles -EnvMap $envMap

    if (-not (Import-BundledImages))    { return 1 }
    if (-not (Install-ThirdPartyImages)) { return 1 }
    if (-not (Initialize-Network))      { return 1 }

    Write-Host ''

    if (-not (Start-Layer 'eip-infra' 'docker-compose.infra.yml' 'infrastructure (Traefik, Postgres, Keycloak, Infisical)')) { return 1 }
    Wait-SeedJob 'eip-infisical-seed' 300 | Out-Null
    Wait-Layer 'eip-infra' 'Infrastructure'

    if (-not (Start-Layer 'eip-data' 'docker-compose.data.yml' 'data layer (ArcadeDB, Fuseki, Qdrant)')) { return 1 }
    Wait-Layer 'eip-data' 'Data layer'
    if ($script:BundledArcade) { Initialize-ArcadeSchema -EnvMap $envMap }

    if ($script:BundledAi) {
        if (-not (Start-Layer 'eip-ai' 'docker-compose.ai.yml' 'AI layer (Ollama)')) { return 1 }
        Wait-Layer 'eip-ai' 'AI layer'
        Install-OllamaModels
    } else {
        Write-Step 'EIP_BUNDLED_AI=false - skipping the local AI layer.'
    }

    # Idempotent, and required every start: the app layer mounts the connections
    # registry as an external volume, which this job creates.
    Write-Step 'Ensuring the DB connections registry is seeded...'
    & docker compose -p eip-infra -f docker-compose.infra.yml up -d connections-seed *> $null

    if (-not (Start-Layer 'eip-app' 'docker-compose.app.yml' 'application layer (services + UI)')) { return 1 }
    Wait-Layer 'eip-app' 'Application layer'

    Show-Status
    Wait-ForUi 300 | Out-Null

    Write-Host ''
    Write-Host '  Orphos is running successfully.' -ForegroundColor Green
    Write-Host ''

    if (-not $NoBrowser) {
        Write-Step "Opening $ORPHOS_URL ..."
        Start-Process $ORPHOS_URL
    }
    return 0
}

# -----------------------------------------------------------------------------
# Functionality-scoped start (startmodules.bat / startmodules.sh)
#
# The same catalogue as the repo's startmodules.sh: every functionality brings
# the control-plane base (platform-core, platform-registry, admin-console,
# widgets + the shell) plus exactly the services and plugin remotes it needs.
# Everything else stays down. Keep this table in sync with startmodules.sh.
# -----------------------------------------------------------------------------
$BaseBackend = @('platform-core', 'platform-registry', 'admin-console', 'widgets')
$BaseUi      = @('shell', 'plugin-widgets')

$ModuleCatalog = [ordered]@{
    'admin'         = @{ Ai = $false; Plugins = @();
                         Services = @();
                         Desc = 'Platform admin console (tenant / branding / config / RBAC / onboarding)' }
    'core'          = @{ Ai = $true;  Plugins = @('plugin-decision-log');
                         Services = @('graph-proxy', 'okg-ingest', 'okg-reasoning', 'priority-engine', 'briefing-service', 'decision-log');
                         Desc = 'Core knowledge-graph & insight services (graph, OKG, priority, briefing, decision log)' }
    'dataingestion' = @{ Ai = $false; Plugins = @();
                         Services = @('data-ingestion', 'okg-ingest');
                         Desc = 'Enterprise data ingestion (sources, mappings, batch jobs -> graph)' }
    'docingest'     = @{ Ai = $true;  Plugins = @('plugin-doc-ingest');
                         Services = @('doc-ingest', 'docling');
                         Desc = 'Document ingestion (doc-ingest + Docling parser)' }
    'aicopilot'     = @{ Ai = $true;  Plugins = @('plugin-ai-copilot', 'plugin-rag-search');
                         Services = @('nl-query', 'doc-ingest', 'graph-proxy');
                         Desc = 'AI Co-pilot (NL query, RAG document search, SPARQL chat)' }
    'communication' = @{ Ai = $true;  Plugins = @('plugin-email-scanner');
                         Services = @('mail-insight', 'notification-service', 'nl-query', 'mobile-bff');
                         Desc = 'Email + channel communication (mail insight, Slack/Teams, notifications)' }
    'mobile'        = @{ Ai = $false; Plugins = @();
                         Services = @('mobile-bff', 'mail-insight', 'notification-service', 'decision-log', 'priority-engine', 'briefing-service');
                         Desc = 'Native mobile app backend (mobile BFF + its services); API proxy on port 3002' }
    'voice'         = @{ Ai = $false; Plugins = @();
                         Services = @('voice', 'whisper');
                         Desc = 'Voice / speech-to-text (Whisper); runs while a tenant has voice on in Admin -> Voice' }
}
$ModuleAliases = @{
    'email' = @('communication')
    'all'   = @('admin', 'core', 'dataingestion', 'docingest', 'aicopilot', 'communication', 'mobile', 'voice')
}

$ServicePorts = [ordered]@{
    'graph-proxy' = 8080; 'okg-ingest' = 8081; 'nl-query' = 8082; 'priority-engine' = 8083
    'briefing-service' = 8084; 'decision-log' = 8086; 'notification-service' = 8087
    'okg-reasoning' = 8088; 'doc-ingest' = 8091; 'mail-insight' = 8092
    'data-ingestion' = 8096; 'mobile-bff' = 8097; 'voice' = 8099; 'docling' = 5001
}

function Show-ModulesUsage {
    Write-Host 'Usage: startmodules.bat <module>[,<module>...]     (Linux/macOS: ./startmodules.sh)'
    Write-Host ''
    Write-Host 'Modules:'
    foreach ($k in $ModuleCatalog.Keys) { Write-Host ('  {0,-15} {1}' -f $k, $ModuleCatalog[$k].Desc) }
    Write-Host ''
    Write-Host 'Aliases:'
    Write-Host '  email           communication'
    Write-Host '  all             every module above'
    Write-Host ''
    Write-Host 'Every module also starts the admin console, the dashboard and the web UI'
    Write-Host '(except "mobile" on its own, which needs no web UI).'
    Write-Host ''
    Write-Host 'Examples:'
    Write-Host '  startmodules.bat admin'
    Write-Host '  startmodules.bat core,aicopilot,docingest'
    Write-Host '  startmodules.bat all'
}

function Invoke-Modules {
    param([string[]] $Requested)

    $tokens = @($Requested | ForEach-Object { "$_" -split '[,\s]+' } |
                ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
    if ($tokens.Count -eq 0 -or ($tokens | Where-Object { $_ -in 'help', '-h', '--help', '/?' })) {
        Show-ModulesUsage
        return 0
    }

    $selected = [System.Collections.Generic.List[string]]::new()
    foreach ($t in $tokens) {
        $members = if ($ModuleAliases.Contains($t)) { $ModuleAliases[$t] } else { @($t) }
        foreach ($m in $members) {
            if (-not $ModuleCatalog.Contains($m)) {
                Write-Fail "Unknown module '$m'."
                Write-Host ''
                Show-ModulesUsage
                return 2
            }
            if (-not $selected.Contains($m)) { $selected.Add($m) }
        }
    }

    Write-Banner 'Orphos - starting modules'
    foreach ($m in $selected) { Write-Host ('  - {0,-14} {1}' -f $m, $ModuleCatalog[$m].Desc) }
    Write-Host ''

    if (-not (Test-DockerReady))   { return 1 }
    if (-not (Initialize-EnvFile)) { return 1 }
    $envMap = Get-EnvMap
    Set-ComposeProfiles -EnvMap $envMap

    # Union of what the selected modules need.
    $backend = [System.Collections.Generic.List[string]]::new()
    $ui      = [System.Collections.Generic.List[string]]::new()
    $BaseBackend | ForEach-Object { $backend.Add($_) }
    $BaseUi      | ForEach-Object { $ui.Add($_) }
    $needAi = $false
    foreach ($m in $selected) {
        foreach ($s in $ModuleCatalog[$m].Services) { if (-not $backend.Contains($s)) { $backend.Add($s) } }
        foreach ($p in $ModuleCatalog[$m].Plugins)  { if (-not $ui.Contains($p))      { $ui.Add($p) } }
        if ($ModuleCatalog[$m].Ai) { $needAi = $true }
    }

    # mobile on its own never loads the web SPA: the light mobile-proxy replaces
    # the shell. In a union both run - mobile-proxy has its own port (3002).
    $profiles = @($env:COMPOSE_PROFILES -split ',' | Where-Object { $_ })
    if ($selected.Contains('mobile')) {
        if ($selected.Count -eq 1) { $ui.Clear() }
        $ui.Add('mobile-proxy')
        $profiles += 'mobile'
    }

    # Voice containers are CREATED stopped; platform-core's reconciler starts
    # them for tenants that switch voice on. Its profile must be active or
    # compose does not see the services at all.
    $voice = @($backend | Where-Object { $_ -in 'voice', 'whisper' })
    foreach ($v in $voice) { [void]$backend.Remove($v) }
    if ($voice.Count -gt 0) { $profiles += 'voice' }

    # docling sits behind bundled-docling; with EIP_BUNDLED_DOCLING=false it
    # runs elsewhere and is not a service here.
    if ($profiles -notcontains 'bundled-docling') { [void]$backend.Remove('docling') }

    $env:COMPOSE_PROFILES = ($profiles -join ',')
    if ($needAi -and -not $script:BundledAi) {
        Write-Step 'EIP_BUNDLED_AI=false - using the remote Ollama named in .env.'
        $needAi = $false
    }

    $appServices = @($backend) + @($ui) + $voice
    if (-not (Import-BundledImages)) { return 1 }
    if (-not (Install-ThirdPartyImages -AppServices $appServices -NeedAi $needAi)) { return 1 }
    if (-not (Initialize-Network))   { return 1 }
    Write-Host ''

    if (-not (Start-Layer 'eip-infra' 'docker-compose.infra.yml' 'infrastructure (Traefik, Postgres, Keycloak, Infisical)')) { return 1 }
    Wait-SeedJob 'eip-infisical-seed' 300 | Out-Null
    Wait-Layer 'eip-infra' 'Infrastructure'

    if (-not (Start-Layer 'eip-data' 'docker-compose.data.yml' 'data layer (ArcadeDB, Fuseki, Qdrant)')) { return 1 }
    Wait-Layer 'eip-data' 'Data layer'
    if ($script:BundledArcade) { Initialize-ArcadeSchema -EnvMap $envMap }

    if ($needAi) {
        if (-not (Start-Layer 'eip-ai' 'docker-compose.ai.yml' 'AI layer (Ollama)')) { return 1 }
        Wait-Layer 'eip-ai' 'AI layer'
        Install-OllamaModels
    } else {
        Write-Step 'AI layer skipped - the selected modules need no local LLM.'
    }

    Write-Step 'Ensuring the DB connections registry is seeded...'
    & docker compose -p eip-infra -f docker-compose.infra.yml up -d connections-seed *> $null

    # No --remove-orphans: containers of modules started earlier must survive.
    Write-Step "Starting services: $($backend -join ', ')"
    & docker compose -p eip-app -f docker-compose.app.yml up -d --no-build @backend | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Fail 'Services failed to start. Inspect them with:'
        Write-Host '             docker compose -p eip-app -f docker-compose.app.yml logs'
        return 1
    }
    # --no-deps keeps the shell from dragging in every plugin remote.
    Write-Step "Starting UI: $($ui -join ', ')"
    & docker compose -p eip-app -f docker-compose.app.yml up -d --no-build --no-deps @ui | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Fail 'The UI containers failed to start.'
        return 1
    }
    if ($voice.Count -gt 0) {
        Write-Step 'Preparing voice containers (created, not started)...'
        & docker compose -p eip-app -f docker-compose.app.yml up --no-start --no-build @voice | Out-Host
        if ($LASTEXITCODE -eq 0) {
            Write-Ok 'Voice is available. It starts by itself for tenants with voice on in Admin -> Voice.'
        } else {
            Write-Warn 'Could not prepare the voice containers; Admin -> Voice will show it as unavailable.'
        }
    }
    Wait-Layer 'eip-app' 'Application layer'

    Write-Host ''
    Write-Host "  Modules '$($selected -join ',')' are up." -ForegroundColor Green
    Write-Host ''
    if ($ui.Contains('shell'))        { Write-Host "  Orphos (web UI)      $ORPHOS_URL" }
    if ($ui.Contains('mobile-proxy')) { Write-Host '  Mobile API           http://localhost:3002' }
    Write-Host "  Sign-in (Keycloak)   $SIGNIN_URL"
    Write-Host "  Platform API         $PLATFORM_API"
    foreach ($s in $ServicePorts.Keys) {
        if ($backend.Contains($s)) { Write-Host ('  {0,-20} http://localhost:{1}' -f $s, $ServicePorts[$s]) }
    }
    Write-Host ''
    Write-Host '  Only these modules run: menus for the others will not appear.'
    Write-Host '  Add more with e.g.  startmodules.bat communication   - full platform: start.bat'
    Write-Host ''

    if ($ui.Contains('shell')) {
        Wait-ForUi 300 | Out-Null
        if (-not $NoBrowser) { Start-Process $ORPHOS_URL }
    }
    return 0
}

function Invoke-Stop {
    Write-Banner 'Orphos - stopping'

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Warn 'Docker is not installed - nothing to stop.'
        return 0
    }
    & docker version --format '{{.Server.Version}}' *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn 'The Docker engine is not running - nothing to stop.'
        return 0
    }

    # `stop`, never `down`. `down` removes containers and, with -v, the volumes -
    # which is this installation's data. Stopped containers restart in seconds and
    # keep everything. Reverse dependency order.
    # Every profile, so containers a modules start added (mobile-proxy, voice,
    # whisper, docling) are stopped too - compose ignores inactive profiles.
    $env:COMPOSE_PROFILES = 'full,bundled-arcade,bundled-postgres,bundled-infisical-db,bundled-docling,mobile,voice'
    for ($i = $Layers.Count - 1; $i -ge 0; $i--) {
        $layer = $Layers[$i]
        if (-not (Test-Path (Join-Path $root $layer.File))) { continue }
        Write-Step "Stopping $($layer.Label) ..."
        & docker compose -p $layer.Project -f $layer.File stop *> $null
    }

    Write-Host ''
    Write-Ok 'Orphos is stopped. All data has been kept.'
    Write-Host '         Run  start.bat  to bring it back up.'
    Write-Host ''
    return 0
}

function Invoke-Status {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Write-Banner 'Orphos Services'
        Write-Host '  Docker is not installed, so Orphos cannot be running.'
        Write-Host ''
        Write-Host '  Orphos Status: STOPPED (Docker is not installed)'
        Write-Host ''
        return 1
    }
    & docker version --format '{{.Server.Version}}' *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Banner 'Orphos Services'
        Write-Host '  The Docker engine is not running, so Orphos is not running either.'
        Write-Host ''
        Write-Host '  Orphos Status: STOPPED (Docker is not running)'
        Write-Host ''
        return 1
    }
    Show-Status
    return 0
}

switch ($Command) {
    'start'   { exit (Invoke-Start) }
    'stop'    { exit (Invoke-Stop) }
    'status'  { exit (Invoke-Status) }
    'modules' { exit (Invoke-Modules -Requested $Modules) }
    'restart' {
        Invoke-Stop | Out-Null
        exit (Invoke-Start)
    }
}
