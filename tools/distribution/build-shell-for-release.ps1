<#
.SYNOPSIS
  Builds the eip-shell image for a client release and proves no build-machine
  address got baked into the SPA bundle.

.DESCRIPTION
  VITE_* values are Vite BUILD ARGS: Vite substitutes them into the JavaScript
  as string literals during `npm run build`, so whatever this machine's .env
  says at build time is frozen into the image. A client never builds - the
  distribution ships prebuilt images - so a wrong value here cannot be
  corrected by editing anything on the client's machine.

  That is exactly how the 2026-09-07 "directions" package shipped with
  VITE_KEYCLOAK_URL=http://192.168.10.1:8443 baked in: this machine's .env is
  deliberately set for LAN demos, and the release build inherited it. The
  browser was then sent to an unreachable host at sign-in.

  This script keeps both use cases working. It does NOT edit .env - LAN demos
  keep running - it overrides the host for this one build only. A shell
  environment variable takes precedence over .env when docker compose does
  variable substitution, so the override wins without touching the file.

  It then verifies the built image, because an override that silently failed to
  apply is the same bug again.

.PARAMETER PublicHost
  Host baked into the release build. Defaults to localhost (single-machine
  install, which is what the client distribution is). Pass a real hostname only
  for a deliberately multi-machine deployment.

.PARAMETER VerifyOnly
  Skip the build and just audit the eip-shell image already loaded in Docker.
  Use this to check a package you did not build yourself: docker load its
  images\eip-shell.tar first, then run with -VerifyOnly.

.EXAMPLE
  .\tools\distribution\build-shell-for-release.ps1
  Builds with localhost and audits the result.

.EXAMPLE
  docker load -i images\eip-shell.tar
  .\tools\distribution\build-shell-for-release.ps1 -VerifyOnly
  Audits a package built elsewhere.
#>
param(
    [string]$PublicHost = 'localhost',
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Image    = 'eip-shell'

# Native command output inside a function joins the return value, so every
# `& docker ...` whose result is not itself the return value is piped to
# Out-Host. Without this a build failure comes back as a truthy array.
function Invoke-ShellBuild {
    param([string]$TargetHost)

    Write-Host "[BUILD] eip-shell with host '$TargetHost' (this build only; .env untouched)"

    # Shell env beats .env for compose variable substitution.
    $env:EIP_PUBLIC_HOST   = $TargetHost
    $env:VITE_KEYCLOAK_URL = "http://${TargetHost}:8443"

    Push-Location $RepoRoot
    try {
        & docker compose -f docker-compose.app.yml build shell | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "docker compose build shell failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
        Remove-Item Env:\EIP_PUBLIC_HOST   -ErrorAction SilentlyContinue
        Remove-Item Env:\VITE_KEYCLOAK_URL -ErrorAction SilentlyContinue
    }
    Write-Host "[OK] build complete"
}

# Reads every absolute http(s) URL literal out of the served bundle. This is the
# check that would have caught the 2026-09-07 package before it shipped.
function Get-BakedHosts {
    param([string]$ImageRef)

    $script = "grep -ohE 'https?://[a-zA-Z0-9._-]+(:[0-9]+)?' " +
              "/usr/share/nginx/html/assets/*.js /usr/share/nginx/html/index.html 2>/dev/null | sort -u"
    $out = & docker run --rm --entrypoint sh $ImageRef -c $script
    if ($LASTEXITCODE -ne 0) { throw "could not read bundle from image '$ImageRef' (exit $LASTEXITCODE)" }
    return @($out | Where-Object { $_ -and $_.Trim() -ne '' })
}

function Test-BakedHosts {
    param([string[]]$Urls)

    Write-Host "[AUDIT] absolute URL literals in the bundle:"
    foreach ($u in $Urls) { Write-Host "          $u" }

    $bad = @()
    foreach ($u in $Urls) {
        if ($u -notmatch '^https?://([^:/]+)') { continue }
        $h = $Matches[1]

        # An IPv4 literal is always a build-machine leak - the client's address
        # is not knowable at build time. 127.0.0.1 is the one safe literal.
        if ($h -match '^\d{1,3}(\.\d{1,3}){3}$' -and $h -ne '127.0.0.1') {
            $bad += "$u  (IP literal - build machine's address)"
            continue
        }
        # Keycloak is the one the browser is redirected to at sign-in, so it is
        # the failure that actually locks users out.
        if ($u -match ':8443' -and $h -ne 'localhost' -and $h -ne '127.0.0.1') {
            $bad += "$u  (Keycloak host is not localhost)"
        }
    }
    return $bad
}

if (-not $VerifyOnly) { Invoke-ShellBuild -TargetHost $PublicHost }

$urls = Get-BakedHosts -ImageRef $Image
$bad  = Test-BakedHosts -Urls $urls

if ($bad.Count -gt 0) {
    Write-Host ""
    Write-Host "[FAIL] this image must NOT be packaged:"
    foreach ($b in $bad) { Write-Host "         $b" }
    Write-Host ""
    Write-Host "       A client cannot fix this - VITE_* is frozen at build time"
    Write-Host "       and the distribution ships prebuilt images. Rebuild with:"
    Write-Host "         .\tools\distribution\build-shell-for-release.ps1"
    exit 1
}

Write-Host ""
Write-Host "[PASS] no build-machine address baked in - safe to package."
exit 0
