#!/usr/bin/env bash
# Orphos EIP - shared launcher helpers.
#
# Sourced by start.sh, stop.sh and startmodules.sh, which are the SINGLE
# implementation of the platform launchers on every OS:
#
#   Linux / macOS : run the .sh directly       (./start.sh app)
#   Windows       : run the .bat wrapper       (start.bat app)
#                   which locates Git Bash (or WSL) and execs the same .sh
#
# There is deliberately no PowerShell port any more - it drifted from the
# shell version. Everything OS-specific lives in this file so the callers
# stay platform-neutral.
#
# Nothing here runs on its own; source it:
#     . "$(dirname "${BASH_SOURCE[0]}")/scripts/lib/eip-platform.sh"

# Guard against double-sourcing (stop.sh -> start.sh chains, future nesting).
if [ -n "${EIP_PLATFORM_LIB_LOADED:-}" ]; then
    return 0
fi
EIP_PLATFORM_LIB_LOADED=1

# Repo root, derived from this file's own location (scripts/lib/ -> ../..), so
# callers work no matter what the working directory was at launch time.
EIP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# ---------------------------------------------------------------
# Host OS detection
# ---------------------------------------------------------------
# EIP_OS is one of: windows | macos | linux
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) EIP_OS="windows" ;;
    Darwin)               EIP_OS="macos" ;;
    *)                    EIP_OS="linux" ;;
esac

# NOTE on Git Bash argument mangling: MSYS rewrites arguments that look like
# POSIX paths before handing them to native .exe programs, so
# `-f /d/repo/docker-compose.app.yml` reaches docker.exe corrupted. The fix
# used throughout is to reference every compose file by a RELATIVE name from
# $EIP_ROOT, which MSYS leaves alone. Do NOT disable the conversion globally:
# Maven's own launcher script depends on it to build a Windows -classpath, and
# turning it off makes `mvn` die with ClassNotFoundException. Where a literal
# forward-slash switch really is needed (taskkill /F), the variables are set on
# that single command instead.

# ---------------------------------------------------------------
# Distribution (client package) detection.
#
# A client package ships prebuilt images in images/orphos-images.tar and NO
# source tree - no services/, no frontend/ - even though every app-layer
# compose service still carries a `build:` key. So any `docker compose build`
# or Maven step is impossible there and every `up` must pass --no-build.
# The .orphos-distribution marker is what says so.
# ---------------------------------------------------------------
EIP_IS_DISTRIBUTION="false"
if [ -f "$EIP_ROOT/.orphos-distribution" ]; then
    EIP_IS_DISTRIBUTION="true"
fi
# Append to every `docker compose up` so a client never tries to compile.
# Empty in a normal checkout. Used unquoted on purpose (word-splits to nothing).
EIP_COMPOSE_NO_BUILD=""
if [ "$EIP_IS_DISTRIBUTION" = "true" ]; then
    EIP_COMPOSE_NO_BUILD="--no-build"
fi

# ---------------------------------------------------------------
# eip_launcher <base-name> - how the user invokes a launcher on this host.
# Used in help text and hints so the suggested command actually works
# (start.bat on Windows, ./start.sh elsewhere).
# ---------------------------------------------------------------
eip_launcher() {
    local base="${1%.sh}"
    if [ "$EIP_OS" = "windows" ]; then
        printf '%s.bat' "$base"
    else
        printf './%s.sh' "$base"
    fi
}

# ---------------------------------------------------------------
# eip_require_docker [--optional]
#
# Makes sure a Linux-containers Docker engine is reachable, launching Docker
# Desktop on Windows/macOS when it is not running (up to 90 s). With
# --optional a missing engine is reported as a warning and returns 1 instead
# of exiting - stop.sh uses that ("nothing to stop").
# ---------------------------------------------------------------
eip_require_docker() {
    local optional="false"
    [ "${1:-}" = "--optional" ] && optional="true"

    # Docker Desktop's WSL2 backend exposes the engine through this context;
    # a stale context selection is the usual cause of "cannot connect".
    if [ "$EIP_OS" = "windows" ]; then
        docker context use desktop-linux >/dev/null 2>&1 || true
    fi

    if ! docker info >/dev/null 2>&1; then
        if [ "$optional" = "true" ]; then
            echo "[WARN] Docker is not running - nothing to do."
            return 1
        fi
        if ! _eip_try_start_docker; then
            return 1
        fi
    fi

    if ! docker compose version >/dev/null 2>&1; then
        echo "[ERROR] The Docker Compose v2 plugin is missing ('docker compose' failed)."
        echo "        Install it: https://docs.docker.com/compose/install/"
        exit 1
    fi

    echo "[OK] Docker engine is running."

    # Windows containers mode cannot run any of these images.
    local osType
    osType="$(docker info --format '{{.OSType}}' 2>/dev/null || true)"
    if [ -n "$osType" ] && [ "$osType" != "linux" ]; then
        echo "[ERROR] Docker is not in Linux containers mode (detected: $osType)."
        if [ "$EIP_OS" = "windows" ]; then
            echo "        Right-click the Docker Desktop tray icon, select"
            echo "        'Switch to Linux containers...' then re-run."
        fi
        exit 1
    fi
    return 0
}

# Attempts to launch the local Docker engine and waits up to 90 s for it.
# Exits (rather than returning) when the engine cannot be brought up, so
# callers do not have to repeat the error handling.
_eip_try_start_docker() {
    local exe=""
    case "$EIP_OS" in
        windows)
            exe="$(_eip_docker_desktop_exe || true)"
            if [ -z "$exe" ]; then
                echo "[ERROR] Docker is not running and Docker Desktop could not be found."
                echo "        Start it manually and re-run."
                exit 1
            fi
            echo "[INFO] Docker Desktop is not running. Attempting to start it..."
            ( "$exe" >/dev/null 2>&1 & ) || true
            ;;
        macos)
            echo "[INFO] Docker Desktop is not running. Attempting to start it..."
            open -a Docker >/dev/null 2>&1 || true
            ;;
        *)
            echo "[ERROR] Docker engine is not running or not reachable."
            echo "        Start it first, e.g.: sudo systemctl start docker"
            echo "        (add yourself to the 'docker' group to run without sudo)"
            exit 1
            ;;
    esac

    local waited=0
    while [ "$waited" -lt 90 ]; do
        sleep 5
        waited=$((waited + 5))
        if [ "$EIP_OS" = "windows" ]; then
            docker context use desktop-linux >/dev/null 2>&1 || true
        fi
        if docker info >/dev/null 2>&1; then
            return 0
        fi
    done

    echo "[ERROR] Docker did not become ready within 90 s. Start it manually and retry."
    exit 1
}

# Locates "Docker Desktop.exe" from the environment rather than hardcoding
# C:\Program Files - Docker can be installed per-user under %LOCALAPPDATA%.
_eip_docker_desktop_exe() {
    local candidates=() c
    if command -v cygpath >/dev/null 2>&1; then
        [ -n "${PROGRAMFILES:-}" ] && candidates+=("$(cygpath -u "$PROGRAMFILES")/Docker/Docker/Docker Desktop.exe")
        [ -n "${LOCALAPPDATA:-}" ] && candidates+=("$(cygpath -u "$LOCALAPPDATA")/Docker/Docker Desktop.exe")
    fi
    candidates+=("/c/Program Files/Docker/Docker/Docker Desktop.exe")
    for c in "${candidates[@]}"; do
        if [ -x "$c" ]; then
            printf '%s' "$c"
            return 0
        fi
    done
    return 1
}

# ---------------------------------------------------------------
# eip_delegate_if_distribution
#
# start.sh's full-platform path runs Maven and `docker compose build`, neither
# of which can work in a client package. The package carries its own launcher
# (orphos-start.ps1) that loads the prebuilt images and brings the layers up
# with --no-build, so hand over to it rather than failing halfway through a
# build. Returns only when this is an ordinary checkout.
# ---------------------------------------------------------------
eip_delegate_if_distribution() {
    [ "$EIP_IS_DISTRIBUTION" = "true" ] || return 0

    if [ ! -f "$EIP_ROOT/orphos-start.ps1" ]; then
        echo "[ERROR] This is a client package (.orphos-distribution is present) but"
        echo "        orphos-start.ps1 is missing, so there is no way to start it:"
        echo "        the app layer has no source tree to build from."
        echo "        Re-extract the package - it is incomplete."
        exit 1
    fi

    if [ "$EIP_OS" != "windows" ]; then
        echo "[ERROR] This is a client package. Its launcher is PowerShell-only."
        echo "        Run it yourself:  pwsh ./orphos-start.ps1"
        exit 1
    fi

    echo "[INFO] Client package detected (.orphos-distribution)."
    echo "       There is no source tree here, so handing over to the package's"
    echo "       own launcher, which uses the prebuilt images:"
    echo "         powershell -File ./orphos-start.ps1"
    echo
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./orphos-start.ps1
    exit $?
}

# ---------------------------------------------------------------
# eip_load_distribution_images <probe-image>
#
# The client-package equivalent of "build the missing images": load them from
# the bundled archive instead. Mirrors orphos-start.ps1's load step, including
# its size check - a truncated copy or half-finished extraction is the usual
# way a package arrives broken, and `docker load` only reports it deep in its
# own output.
# ---------------------------------------------------------------
eip_load_distribution_images() {
    local probe="${1:-eip-platform-core}"
    local tar="$EIP_ROOT/images/orphos-images.tar"
    local sizeFile="$EIP_ROOT/images/orphos-images.size"

    if docker image inspect "$probe" >/dev/null 2>&1; then
        echo "[OK] Images already present - skipping the load step."
        return 0
    fi
    if [ ! -f "$tar" ]; then
        echo "[ERROR] No images loaded and images/orphos-images.tar is missing -"
        echo "        the package is incomplete."
        return 1
    fi
    if [ -f "$sizeFile" ]; then
        local expected actual
        expected="$(tr -d ' \r\n' < "$sizeFile")"
        actual="$(wc -c < "$tar" | tr -d ' ')"
        if [ -n "$expected" ] && [ "$expected" != "$actual" ]; then
            echo "[ERROR] The image archive is incomplete."
            echo "        images/orphos-images.tar is $actual bytes but should be $expected."
            echo "        The copy or extraction was cut short. Copy the package again"
            echo "        and re-extract it, then run this again."
            return 1
        fi
    fi
    echo "[BUILD] First run: loading bundled images. One-time, several minutes,"
    echo "        nothing is downloaded."
    if ! docker load -i "$tar"; then
        echo "[ERROR] docker load failed. Check free disk space - it needs roughly"
        echo "        twice the archive size."
        return 1
    fi
    echo "[OK] Images loaded."
    return 0
}

# ---------------------------------------------------------------
# eip_ensure_env_file - .env must exist before compose reads it.
# ---------------------------------------------------------------
eip_ensure_env_file() {
    if [ -f "$EIP_ROOT/.env" ]; then
        return 0
    fi
    if [ -f "$EIP_ROOT/.env.example" ]; then
        cp "$EIP_ROOT/.env.example" "$EIP_ROOT/.env"
        echo "[WARN] .env was missing - copied from .env.example. Review credentials before production use."
        return 0
    fi
    echo "[ERROR] Neither .env nor .env.example found. Cannot continue."
    exit 1
}

# ---------------------------------------------------------------
# eip_env_val <NAME>          - last value of NAME in .env, unquoted/trimmed
# eip_env_bool <NAME> <deflt> - the same, coerced to "true"/"false"
#
# tr -d '\r' matters on Windows: a .env saved by a Windows editor has CRLF
# line endings and the trailing \r would otherwise become part of the value
# (making "true\r" fall through to the default).
# ---------------------------------------------------------------
eip_env_val() {
    grep -E "^[[:space:]]*${1}[[:space:]]*=" "$EIP_ROOT/.env" 2>/dev/null |
        tail -1 | cut -d'=' -f2- | tr -d '\r' |
        sed -e 's/^["'\'']//' -e 's/["'\'']$//' -e 's/^ *//' -e 's/ *$//'
}

eip_env_bool() {
    local v
    v="$(eip_env_val "$1")"
    case "$v" in
        false|0|False|FALSE) echo "false" ;;
        true|1|True|TRUE)    echo "true" ;;
        *)                   echo "$2" ;;
    esac
}

# ---------------------------------------------------------------
# eip_stop_host_ollama - a natively installed Ollama holds port 11434 and
# would keep the container's published port from binding.
# ---------------------------------------------------------------
eip_stop_host_ollama() {
    if [ "$EIP_OS" = "windows" ]; then
        # MSYS would otherwise turn /F and /IM into filesystem paths.
        MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
            taskkill /F /IM ollama.exe >/dev/null 2>&1 || true
        MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
            taskkill /F /IM "ollama app.exe" >/dev/null 2>&1 || true
    elif command -v pkill >/dev/null 2>&1; then
        pkill -x ollama >/dev/null 2>&1 || true
    fi
}

# ---------------------------------------------------------------
# eip_mvn <args...> - resolve Maven the same way mvnw.cmd does: an explicit
# MAVEN_HOME first, then whatever is on PATH, and ./mvnw only as a last
# resort (that wrapper hardcodes one developer's MAVEN_HOME, so it is not
# portable).
# ---------------------------------------------------------------
eip_mvn() {
    if [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
        "$MAVEN_HOME/bin/mvn" "$@"
    elif command -v mvn >/dev/null 2>&1; then
        mvn "$@"
    elif [ -f "$EIP_ROOT/mvnw" ]; then
        bash "$EIP_ROOT/mvnw" "$@"
    else
        echo "[ERROR] Maven not found. Install it and add it to PATH, or set MAVEN_HOME."
        exit 1
    fi
}

# ---------------------------------------------------------------
# eip_wait_for_seed <container> <timeout-seconds>
# Waits for a one-shot compose job to exit and checks its exit code.
# ---------------------------------------------------------------
eip_wait_for_seed() {
    local container="$1" timeout="$2" waited=0 status="" exitCode=""
    while [ "$status" != "exited" ] && [ "$waited" -lt "$timeout" ]; do
        sleep 2
        waited=$((waited + 2))
        status="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
    done
    if [ "$status" != "exited" ]; then
        echo "[ERROR] $container timed out after ${timeout}s. Check: docker logs $container"
        return 1
    fi
    exitCode="$(docker inspect -f '{{.State.ExitCode}}' "$container" 2>/dev/null || true)"
    if [ "$exitCode" != "0" ]; then
        echo "[ERROR] $container failed (exit $exitCode). Check: docker logs $container"
        return 1
    fi
    return 0
}
