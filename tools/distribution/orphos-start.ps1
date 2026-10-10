<#
  Orphos - distribution bring-up.

  This is the launcher that ships INSIDE a client package, and it exists because
  the repo's own launchers cannot run there:

    * start.sh / startmodules.sh are shell scripts. On Windows the .bat wrappers
      shell out through scripts\run-sh.bat, which needs Git Bash or WSL.
    * They also run Maven and build images from source - impossible on a machine
      with no source tree.

  A client has Docker Desktop and nothing else, so this script uses only Docker
  and PowerShell (which ships with Windows):

    1. loads images\orphos-images.tar the first time, after checking it arrived
       intact;
    2. brings the four layers up in order - infra, data, ai, app - with
       --no-build, so nothing is ever compiled;
    3. waits for each layer's containers to report healthy before the next.

  The package also carries a bundled bash, JDK and Maven under tools\, so
  start.bat / startmodules.bat still work there if you want the repo's shared
  implementation instead. This script is the path that needs none of them.

  Usage (from the package root):
      .\orphos-start.ps1              # bring the platform up
      .\orphos-start.ps1 -SkipLoad    # assume images are already loaded
#>
param(
    [switch] $SkipLoad,
    [int]    $HealthTimeoutSeconds = 300
)

$ErrorActionPreference = "Continue"
$root = $PSScriptRoot

function Write-Step($msg) { Write-Host "[orphos] $msg" }
function Write-Fail($msg) { Write-Host "[ERROR] $msg" -ForegroundColor Red }

# ── Docker must be running ──────────────────────────────────────────────────
# Checked explicitly rather than letting compose fail: "docker not recognized"
# and "engine not running" are the two most common client problems and they
# deserve a straight answer instead of a compose stack trace.
& docker version --format "{{.Server.Version}}" *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Fail "Docker is not available."
    Write-Host "        Install Docker Desktop, set it to LINUX CONTAINERS, start it, and"
    Write-Host "        wait for the whale icon to stop animating. Then run this again."
    exit 1
}
$osType = (& docker info --format "{{.OSType}}" 2>$null)
if ($osType -and $osType.Trim() -ne "linux") {
    Write-Fail "Docker is in Windows containers mode (detected: $($osType.Trim()))."
    Write-Host "        Right-click the Docker Desktop tray icon and choose"
    Write-Host "        'Switch to Linux containers...', then run this again."
    exit 1
}
Write-Step "Docker engine OK (Linux containers)."

# ── .env is what the wizard wrote ───────────────────────────────────────────
$envPath = Join-Path $root ".env"
if (-not (Test-Path $envPath)) {
    Write-Fail "No .env in $root."
    Write-Host "        Run 'Orphos Setup.exe' first - it writes .env and orphos.config.json."
    exit 1
}
$envMap = @{}
foreach ($line in Get-Content $envPath) {
    if ($line -match '^\s*#') { continue }
    $i = $line.IndexOf('=')
    if ($i -lt 1) { continue }
    $envMap[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim()
}
function EnvBool($key, $default) {
    if (-not $envMap.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($envMap[$key])) { return $default }
    $v = $envMap[$key].ToLower()
    return -not ($v -eq 'false' -or $v -eq '0')
}

# Same profile derivation as start.sh, so the distribution starts the same set
# of containers the repo would.
$demoMode = EnvBool 'DEMO_MODE' $true
$bundledDb = EnvBool 'EIP_BUNDLED_DB' $true
$profiles = @()
if (-not $demoMode) { $profiles += 'full' }
if (EnvBool 'EIP_BUNDLED_ARCADEDB'     $bundledDb) { $profiles += 'bundled-arcade' }
if (EnvBool 'EIP_BUNDLED_POSTGRES'     $bundledDb) { $profiles += 'bundled-postgres' }
if (EnvBool 'EIP_BUNDLED_INFISICAL_DB' $bundledDb) { $profiles += 'bundled-infisical-db' }
if (EnvBool 'EIP_BUNDLED_DOCLING'      $true)      { $profiles += 'bundled-docling' }
$env:COMPOSE_PROFILES = ($profiles -join ',')
Write-Step ("Profile: DEMO_MODE={0}; compose profiles: {1}" -f $demoMode, $env:COMPOSE_PROFILES)

# ── Load the bundled images (once) ──────────────────────────────────────────
if (-not $SkipLoad) {
    & docker image inspect eip-platform-core *> $null
    if ($LASTEXITCODE -ne 0) {
        $tar = Join-Path $root "images\orphos-images.tar"
        if (-not (Test-Path $tar)) {
            Write-Fail "No images loaded and images\orphos-images.tar is missing - the package is incomplete."
            exit 1
        }
        # Integrity gate. A truncated copy or a half-finished extraction is the
        # likeliest way this arrives broken, and docker load reports it deep in
        # its own output. Catch it here, in plain words, before spending minutes.
        $expectedFile = Join-Path $root "images\orphos-images.size"
        $actual = (Get-Item $tar).Length
        if (Test-Path $expectedFile) {
            $expected = [int64](Get-Content $expectedFile -Raw).Trim()
            if ($actual -ne $expected) {
                Write-Fail "The image archive is incomplete."
                Write-Host ("        images\orphos-images.tar is {0:N2} GB but should be {1:N2} GB." -f ($actual/1GB), ($expected/1GB))
                Write-Host "        The copy or extraction was cut short. Copy the package again and"
                Write-Host "        re-extract it, then run this again."
                exit 1
            }
        }
        Write-Step ("First run: loading bundled images ({0:N1} GB). One-time, several minutes, nothing downloaded." -f ($actual/1GB))
        & docker load -i $tar
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "docker load failed. Check free disk space - it needs roughly twice the archive size."
            exit 1
        }
        Write-Step "Images loaded."
    } else {
        Write-Step "Images already present - skipping the load step."
    }
}

# ── Layer bring-up ──────────────────────────────────────────────────────────
# --no-build is the load-bearing flag: every image is preloaded, and without it
# compose would try to build the 36 services that carry a build: key.
function Start-Layer($project, $file, $label) {
    $path = Join-Path $root $file
    if (-not (Test-Path $path)) { Write-Fail "Missing $file - the package is incomplete."; exit 1 }
    Write-Step "Starting $label ..."
    & docker compose -p $project -f $path up -d --no-build --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "$label failed to start. Inspect it with:"
        Write-Host "        docker compose -p $project -f $file logs"
        exit 1
    }
}

# Waits until no container in the project is still 'starting'. Containers with
# no healthcheck report no health at all, so they are treated as ready.
function Wait-Layer($project, $label) {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $ids = & docker compose -p $project ps -q 2>$null
        if (-not $ids) { return }
        $starting = 0; $unhealthy = 0
        foreach ($id in $ids) {
            if (-not $id) { continue }
            $h = (& docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" $id 2>$null)
            if ($h -eq 'starting') { $starting++ }
            elseif ($h -eq 'unhealthy') { $unhealthy++ }
        }
        if ($starting -eq 0) {
            if ($unhealthy -gt 0) { Write-Step "$label up, but $unhealthy container(s) report unhealthy - continuing." }
            else { Write-Step "$label healthy." }
            return
        }
        Start-Sleep -Seconds 5
    }
    Write-Step "$label still settling after $HealthTimeoutSeconds s - continuing anyway."
}

Start-Layer 'eip-infra' 'docker-compose.infra.yml' 'infrastructure (Traefik, Postgres, Keycloak, Infisical)'
Wait-Layer  'eip-infra' 'Infrastructure'

Start-Layer 'eip-data' 'docker-compose.data.yml' 'data layer (ArcadeDB, Fuseki, Qdrant)'
Wait-Layer  'eip-data' 'Data layer'

# The AI layer is its own compose file rather than a profile, so it is started
# only when the install actually asked for local models.
if (Test-Path (Join-Path $root 'docker-compose.ai.yml')) {
    Start-Layer 'eip-ai' 'docker-compose.ai.yml' 'AI layer (Ollama)'
    Write-Step "Ollama will pull its models on first boot - this needs internet and takes a few minutes."
}

# Seeds the DB-connections volume, exactly as the repo launchers do.
& docker compose -p eip-infra -f (Join-Path $root 'docker-compose.infra.yml') up -d connections-seed *> $null

Start-Layer 'eip-app' 'docker-compose.app.yml' 'application layer'
Wait-Layer  'eip-app' 'Application layer'

Write-Host ""
Write-Step "Platform is up."
Write-Host "         Orphos       http://localhost:3001"
Write-Host "         Sign-in      http://localhost:8443"
Write-Host "         Platform API http://localhost:8090"
Write-Host ""
Write-Host "         First sign-in can take another minute while the services finish seeding."
Write-Host "         Stop everything with  stop.bat"
