#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Orphos - client distribution engine (Docker only), Linux/macOS.
#
# The exact counterpart of orphos.ps1: same four compose layers, same profile
# derivation, same bootstrap sequence, same --no-build guarantee. It is a
# separate implementation rather than a shared one because a Windows client has
# no bash and a Linux client has no PowerShell, and each has to work alone.
#
# Depends only on: docker, and the coreutils any Linux/macOS install already has.
# No JDK, no Maven, no Node, no source tree.
#
#   ./orphos.sh start | stop | restart | status
# -----------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 1

ORPHOS_URL="http://localhost:3001"
SIGNIN_URL="http://localhost:8443"
PLATFORM_API="http://localhost:8090"

HEALTH_TIMEOUT="${ORPHOS_HEALTH_TIMEOUT:-900}"
NO_BROWSER="${ORPHOS_NO_BROWSER:-false}"

# Bring-up order is a dependency order: app services read secrets seeded by
# infra and query schema created against data.
LAYER_PROJECTS=(eip-infra                  eip-data                 eip-ai                 eip-app)
LAYER_FILES=(docker-compose.infra.yml docker-compose.data.yml docker-compose.ai.yml docker-compose.app.yml)
LAYER_LABELS=("Infrastructure"          "Data layer"             "AI layer"             "Application")

step() { echo "[orphos] $*"; }
ok()   { echo "[  OK  ] $*"; }
warn() { echo "[ WARN ] $*"; }
fail() { echo "[ERROR ] $*" >&2; }

banner() {
    echo
    echo "============================================================"
    echo "  $*"
    echo "============================================================"
    echo
}

# -----------------------------------------------------------------------------
# Docker preflight. Checked explicitly so the three common client failures get a
# plain answer instead of a compose stack trace.
# -----------------------------------------------------------------------------
docker_ready() {
    if ! command -v docker >/dev/null 2>&1; then
        fail "Docker is not installed on this machine."
        echo
        echo "         Orphos runs entirely in Docker containers, so Docker is the one"
        echo "         prerequisite. Install Docker Engine (Linux) or Docker Desktop"
        echo "         (macOS) from https://docs.docker.com/get-docker/ and run this again."
        return 1
    fi

    if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
        fail "Docker is installed but the engine is not running."
        echo
        echo "         Linux : sudo systemctl start docker"
        echo "         macOS : start Docker Desktop and wait for the whale icon to settle."
        echo
        echo "         If you get a permission error instead, add yourself to the docker"
        echo "         group:  sudo usermod -aG docker \$USER   (then log out and back in)."
        return 1
    fi

    if ! docker compose version >/dev/null 2>&1; then
        fail "The Docker Compose v2 plugin is missing ('docker compose' failed)."
        echo "         Install it: https://docs.docker.com/compose/install/"
        return 1
    fi

    ok "Docker engine $(docker version --format '{{.Server.Version}}' 2>/dev/null) is running."
    return 0
}

# -----------------------------------------------------------------------------
# .env
#
# Generated once, on the first start, with this installation's own random
# passwords. They are written into Keycloak, Postgres, ArcadeDB and Infisical on
# first boot, so regenerating them later locks the platform out of its own data.
# -----------------------------------------------------------------------------
# Alphanumeric on purpose: the value travels through .env (where a '$' would be
# interpolated by compose), JDBC URLs and Basic-auth headers.
new_secret()  { LC_ALL=C tr -dc 'a-km-zA-HJ-NP-Z2-9' < /dev/urandom | head -c "${1:-28}"; }
new_hex()     { LC_ALL=C tr -dc 'a-f0-9'             < /dev/urandom | head -c "${1:-32}"; }
new_base64()  { head -c "${1:-32}" /dev/urandom | base64 | tr -d '\n'; }

init_env_file() {
    if [ -f "$ROOT/.env" ]; then
        ok "Using the existing .env (this installation is already configured)."
        return 0
    fi
    if [ ! -f "$ROOT/.env.example" ]; then
        fail ".env.example is missing - this package is incomplete."
        return 1
    fi

    step "First run: generating .env with this installation's own passwords."

    # Formats follow the notes in .env.example: Infisical wants a 16-byte hex
    # encryption key and a 32-byte base64 auth secret, and the internal service
    # token must be 32+ chars or platform-core fails closed.
    local kc arcade pg fuseki infdb infadmin infkey infauth svctoken
    kc="$(new_secret 28)";        arcade="$(new_secret 28)"
    pg="$(new_secret 28)";        fuseki="$(new_secret 28)"
    infdb="$(new_secret 28)";     infadmin="$(new_secret 28)"
    infkey="$(new_hex 32)";       infauth="$(new_base64 32)"
    svctoken="$(new_hex 48)"

    # Only ever fill a key that is present AND blank; a value already in
    # .env.example is a deliberate default and is left alone.
    #
    # tr -d '\r' first: the anchors below end in `=$`, which cannot match a line
    # that ends in \r. The packaged template is already LF, but a template edited
    # on Windows would silently produce a .env with every password EMPTY, so the
    # guard stays.
    tr -d '\r' < "$ROOT/.env.example" | \
    sed -e "s|^KEYCLOAK_ADMIN_PASSWORD=$|KEYCLOAK_ADMIN_PASSWORD=$kc|" \
        -e "s|^ARCADEDB_PASSWORD=$|ARCADEDB_PASSWORD=$arcade|" \
        -e "s|^POSTGRES_PASSWORD=$|POSTGRES_PASSWORD=$pg|" \
        -e "s|^FUSEKI_ADMIN_PASSWORD=$|FUSEKI_ADMIN_PASSWORD=$fuseki|" \
        -e "s|^INFISICAL_DB_PASSWORD=$|INFISICAL_DB_PASSWORD=$infdb|" \
        -e "s|^INFISICAL_ADMIN_PASSWORD=$|INFISICAL_ADMIN_PASSWORD=$infadmin|" \
        -e "s|^INFISICAL_ENCRYPTION_KEY=$|INFISICAL_ENCRYPTION_KEY=$infkey|" \
        -e "s|^INFISICAL_AUTH_SECRET=$|INFISICAL_AUTH_SECRET=$infauth|" \
        -e "s|^EIP_INTERNAL_SERVICE_TOKEN=$|EIP_INTERNAL_SERVICE_TOKEN=$svctoken|" \
        -e "s|^INFISICAL_DB_CONNECTION_URI=$|INFISICAL_DB_CONNECTION_URI=postgres://infisical:$infdb@eip-infisical-db:5432/infisical|" \
        > "$ROOT/.env"

    # A blank secret here means the substitution silently missed, which surfaces
    # much later as an unexplained authentication failure. Catch it now.
    local k missing=""
    for k in KEYCLOAK_ADMIN_PASSWORD ARCADEDB_PASSWORD POSTGRES_PASSWORD \
             FUSEKI_ADMIN_PASSWORD INFISICAL_ENCRYPTION_KEY INFISICAL_AUTH_SECRET \
             EIP_INTERNAL_SERVICE_TOKEN INFISICAL_DB_CONNECTION_URI; do
        [ -z "$(env_value "$k")" ] && missing="$missing $k"
    done
    if [ -n "$missing" ]; then
        fail "Generated .env is missing values for:$missing"
        echo "         The .env.example template in this package is not what the"
        echo "         launcher expects. Do not start with this .env - remove it and"
        echo "         report the problem."
        return 1
    fi

    chmod 600 "$ROOT/.env" 2>/dev/null || true
    ok ".env created. Keep it safe - it holds this installation's passwords."
    echo "         They are written into the databases on first start, so changing"
    echo "         them later breaks sign-in unless the data volumes are reset too."
    return 0
}

env_value() {
    [ -f "$ROOT/.env" ] || return 0
    grep -E "^\s*$1\s*=" "$ROOT/.env" | tail -1 | cut -d'=' -f2- | tr -d '\r'
}

env_bool() {
    local v
    v="$(env_value "$1")"
    if [ -z "$v" ]; then echo "$2"; return; fi
    v="$(echo "$v" | tr '[:upper:]' '[:lower:]')"
    if [ "$v" = "false" ] || [ "$v" = "0" ]; then echo "false"; else echo "true"; fi
}

# The same profile derivation start.sh performs, so a client starts exactly the
# set of containers a development machine would.
set_compose_profiles() {
    local demo bundled_db profiles=()
    demo="$(env_bool DEMO_MODE true)"
    bundled_db="$(env_bool EIP_BUNDLED_DB true)"

    [ "$demo" = "false" ] && profiles+=("full")
    [ "$(env_bool EIP_BUNDLED_ARCADEDB     "$bundled_db")" = "true" ] && profiles+=("bundled-arcade")
    [ "$(env_bool EIP_BUNDLED_POSTGRES     "$bundled_db")" = "true" ] && profiles+=("bundled-postgres")
    [ "$(env_bool EIP_BUNDLED_INFISICAL_DB "$bundled_db")" = "true" ] && profiles+=("bundled-infisical-db")
    [ "$(env_bool EIP_BUNDLED_DOCLING      true)"          = "true" ] && profiles+=("bundled-docling")

    if [ "${#profiles[@]}" -gt 0 ]; then
        COMPOSE_PROFILES="$(IFS=,; echo "${profiles[*]}")"
    else
        COMPOSE_PROFILES=""
    fi
    export COMPOSE_PROFILES

    # The AI layer is its own compose file rather than a profile, and the bundled
    # ArcadeDB gates the schema step.
    BUNDLED_AI="$(env_bool EIP_BUNDLED_AI true)"
    BUNDLED_ARCADE="$(env_bool EIP_BUNDLED_ARCADEDB "$bundled_db")"

    step "Profiles: ${COMPOSE_PROFILES:-<none>}"
}

# -----------------------------------------------------------------------------
# Images. The expected set is parsed from the compose files that ship here, so it
# cannot drift from what the bring-up actually starts.
# -----------------------------------------------------------------------------
expected_images() {
    local f
    for f in "${LAYER_FILES[@]}"; do
        [ -f "$ROOT/$f" ] || continue
        sed -nE 's|^[[:space:]]+image:[[:space:]]*([^[:space:]]+).*|\1|p' "$ROOT/$f"
    done | sort -u
}

# ONLINE package: images/orphos-images.tar carries only the Orphos (eip-*)
# images. Third-party images are pulled on first start - see pull_images.
load_images() {
    local img missing=0 total=0
    while IFS= read -r img; do
        [ -n "$img" ] || continue
        total=$((total + 1))
        docker image inspect "$img" >/dev/null 2>&1 || missing=$((missing + 1))
    done < <(expected_images | grep '^eip-')

    if [ "$missing" -eq 0 ]; then
        ok "All $total Orphos images are already loaded - skipping the load step."
        return 0
    fi

    local tar="$ROOT/images/orphos-images.tar"
    if [ ! -f "$tar" ]; then
        fail "$missing Orphos image(s) are missing and images/orphos-images.tar is not here."
        echo "         This package is incomplete. Copy it again and re-extract."
        return 1
    fi

    # Integrity gate. A truncated copy is the likeliest way a large package
    # arrives broken, and docker load only reports it minutes in, cryptically.
    local actual expected
    actual="$(wc -c < "$tar" | tr -d ' ')"
    if [ -f "$ROOT/images/orphos-images.size" ]; then
        expected="$(tr -d ' \r\n' < "$ROOT/images/orphos-images.size")"
        if [ "$actual" != "$expected" ]; then
            fail "The image archive is incomplete."
            echo "         images/orphos-images.tar is $actual bytes but should be $expected."
            echo "         The copy or extraction was cut short. Copy the package again and"
            echo "         re-extract it, then run this again."
            return 1
        fi
    fi

    step "First run: loading the bundled Orphos images ($((actual / 1024 / 1024 / 1024)) GB)."
    echo "         One-time step, several minutes."
    if ! docker load -i "$tar"; then
        fail "docker load failed."
        echo "         The usual cause is disk space - it needs roughly twice the archive size free."
        return 1
    fi
    ok "Images loaded."
    return 0
}

# Third-party images: only those the ACTIVE profiles start, as resolved by
# compose, so a demo install never downloads the 'full' stack. Most are pinned
# by digest or exact tag, so the client gets the bytes this package was tested
# with. Must run after set_compose_profiles.
#
# A modules start sets APP_SERVICES (the app layer is narrowed to those, plus
# their dependencies) and NEED_AI (gates the AI layer).
APP_SERVICES=""
NEED_AI="true"
active_third_party_images() {
    local i
    for i in "${!LAYER_FILES[@]}"; do
        [ "${LAYER_PROJECTS[$i]}" = "eip-ai" ] && { [ "$BUNDLED_AI" != "true" ] || [ "$NEED_AI" != "true" ]; } && continue
        [ -f "$ROOT/${LAYER_FILES[$i]}" ] || continue
        if [ "${LAYER_PROJECTS[$i]}" = "eip-app" ]; then
            # shellcheck disable=SC2086
            docker compose -f "${LAYER_FILES[$i]}" config --images $APP_SERVICES 2>/dev/null
        else
            docker compose -f "${LAYER_FILES[$i]}" config --images 2>/dev/null
        fi
    done | tr -d '\r' | grep -v '^eip-' | grep -v '^$'
    # connections-seed is started by name outside the profile set.
    echo "busybox:1.36"
}

pull_images() {
    local img missing=() failed=()
    while IFS= read -r img; do
        docker image inspect "$img" >/dev/null 2>&1 || missing+=("$img")
    done < <(active_third_party_images | sort -u)

    if [ "${#missing[@]}" -eq 0 ]; then
        ok "All third-party images are present."
        return 0
    fi

    step "Downloading ${#missing[@]} third-party image(s) from their public registries."
    echo "         One-time step; needs internet. The AI engine and document reader"
    echo "         are several GB each, so this can take a while on a slow line."
    for img in "${missing[@]}"; do
        echo "         pulling ${img%%@*} ..."
        docker pull "$img" || failed+=("$img")
    done
    if [ "${#failed[@]}" -gt 0 ]; then
        fail "Could not download ${#failed[@]} image(s):"
        printf '           %s\n' "${failed[@]}"
        echo "         Check the internet connection (and any Docker proxy settings), then"
        echo "         run this again. Images already downloaded are kept."
        return 1
    fi
    ok "Third-party images downloaded."
}

# -----------------------------------------------------------------------------
# Ollama models. Not part of any image and not bundled in this online package:
# pulled into the Ollama volumes once the AI layer is up, exactly as the repo's
# start.sh does, and kept there for every later start.
# -----------------------------------------------------------------------------
OLLAMA_MODELS=(
    "eip-ollama granite3.1-dense:2b"
    "eip-ollama phi3.5"
    "eip-ollama qwen2.5:1.5b"
    "eip-ollama-hi qwen2.5:1.5b"
    "eip-ollama-hi bge-m3"
)

install_ollama_models() {
    local entry c m
    for entry in "${OLLAMA_MODELS[@]}"; do
        c="${entry%% *}"; m="${entry#* }"
        docker exec "$c" ollama list 2>/dev/null | grep -qF "$m" && continue
        step "Downloading AI model $m into $c (one-time, needs internet)..."
        if ! docker exec "$c" ollama pull "$m"; then
            warn "Could not download $m. AI answers will fail until it is present;"
            echo "         run ./start.sh again once the internet connection is back."
        fi
    done
    ok "AI models ready."
}

# The network is declared external: true in every compose file, so it has to
# exist before the first up rather than being created by compose.
init_network() {
    if docker network inspect eip-net >/dev/null 2>&1; then
        ok "Docker network eip-net is present."
        return 0
    fi
    step "Creating the eip-net Docker network..."
    if ! docker network create eip-net >/dev/null 2>&1; then
        fail "Could not create the eip-net network."
        return 1
    fi
    ok "Network eip-net created."
    return 0
}

# -----------------------------------------------------------------------------
# Layer bring-up. --no-build is load-bearing: 36 of these services still carry a
# build: key (the compose files are copied from the repo unchanged) and without
# it compose would try to compile them against a source tree that is not here.
# -----------------------------------------------------------------------------
start_layer() {
    local project="$1" file="$2" label="$3"
    if [ ! -f "$ROOT/$file" ]; then
        fail "Missing $file - this package is incomplete."
        return 1
    fi
    step "Starting $label ..."
    if ! docker compose -p "$project" -f "$file" up -d --no-build --remove-orphans; then
        fail "$label failed to start. Inspect it with:"
        echo "             docker compose -p $project -f $file logs"
        return 1
    fi
    return 0
}

# Waits until nothing in the project is still 'starting'. Containers without a
# healthcheck report no health at all and are treated as ready.
wait_layer() {
    local project="$1" label="$2"
    local deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
    local last=""

    while [ "$(date +%s)" -lt "$deadline" ]; do
        local ids starting=0 unhealthy=0 total=0 id h
        ids="$(docker compose -p "$project" ps -q 2>/dev/null)"
        [ -n "$ids" ] || return 0

        while IFS= read -r id; do
            [ -n "$id" ] || continue
            total=$((total + 1))
            h="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2>/dev/null)"
            [ "$h" = "starting" ]  && starting=$((starting + 1))
            [ "$h" = "unhealthy" ] && unhealthy=$((unhealthy + 1))
        done <<< "$ids"

        if [ "$starting" -eq 0 ]; then
            if [ "$unhealthy" -gt 0 ]; then warn "$label is up, but $unhealthy container(s) report unhealthy."
            else ok "$label healthy."; fi
            return 0
        fi

        local report="$label - $starting of $total container(s) still starting..."
        if [ "$report" != "$last" ]; then echo "         $report"; last="$report"; fi
        sleep 5
    done
    warn "$label is still settling after ${HEALTH_TIMEOUT}s - continuing anyway."
    return 0
}

# The Infisical seed is a one-shot job that writes the machine identity every app
# service mounts. Starting the app layer before it finishes gives 36 services a
# missing-secret failure, so the wait is not optional.
wait_seed_job() {
    local container="$1" timeout="${2:-300}"
    local deadline=$(( $(date +%s) + timeout ))
    step "Waiting for $container to finish seeding..."
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local state code
        state="$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null)"
        if [ -z "$state" ]; then sleep 3; continue; fi
        if [ "$state" = "exited" ]; then
            code="$(docker inspect --format '{{.State.ExitCode}}' "$container" 2>/dev/null)"
            if [ "$code" = "0" ]; then ok "$container completed."; return 0; fi
            warn "$container exited with code $code - continuing, but sign-in may fail."
            return 1
        fi
        sleep 3
    done
    warn "$container did not finish within ${timeout}s - continuing anyway."
    return 1
}

# -----------------------------------------------------------------------------
# ArcadeDB schema. The same statements as scripts/init-arcadedb-schema.sh, run
# through the container's own curl so the client needs nothing installed. Every
# statement is IF NOT EXISTS, so this is a no-op after the first start.
# -----------------------------------------------------------------------------
init_arcade_schema() {
    local pass auth
    pass="$(env_value ARCADEDB_PASSWORD)"
    if [ -z "$pass" ]; then
        warn "ARCADEDB_PASSWORD is not set in .env - skipping schema initialisation."
        return 0
    fi
    auth="root:$pass"

    step "Initialising the ArcadeDB schema..."

    # ArcadeDB reports healthy before its HTTP API accepts commands, so poll.
    local ready=false i
    for i in $(seq 1 40); do
        if arcade_curl "$auth" GET "/api/v1/ready" ""; then ready=true; break; fi
        sleep 3
    done
    if [ "$ready" != "true" ]; then
        warn "ArcadeDB did not become reachable - skipping schema initialisation."
        echo "         The services create what they need on first use; this is usually harmless."
        return 0
    fi

    arcade_curl "$auth" POST "/api/v1/server" '{"command":"create database orphos"}' || true

    local v e t
    for v in Incident ITSystem CostCentre SLAMetric BudgetItem Alert Decision Project; do
        arcade_curl "$auth" POST "/api/v1/command/orphos" "{\"language\":\"sql\",\"command\":\"CREATE VERTEX TYPE $v IF NOT EXISTS\"}" || true
    done
    for e in IMPACTS FUNDS HAS_INCIDENT HAS_SLA_METRIC DEPENDS_ON TRIGGERS; do
        arcade_curl "$auth" POST "/api/v1/command/orphos" "{\"language\":\"sql\",\"command\":\"CREATE EDGE TYPE $e IF NOT EXISTS\"}" || true
    done
    for t in Incident Alert; do
        arcade_curl "$auth" POST "/api/v1/command/orphos" "{\"language\":\"sql\",\"command\":\"CREATE PROPERTY $t.id IF NOT EXISTS STRING\"}" || true
        arcade_curl "$auth" POST "/api/v1/command/orphos" "{\"language\":\"sql\",\"command\":\"CREATE INDEX IF NOT EXISTS ON $t(id) UNIQUE\"}" || true
    done

    ok "ArcadeDB schema ready."
}

# Runs curl in a throwaway container on eip-net rather than on the host: a
# minimal Linux install may have no curl, and the package must not require one.
# curlimages/curl is already one of the platform's images (the seed jobs), so
# nothing extra is shipped for this. ArcadeDB's own image carries only BusyBox
# wget, which has no --post-data, so `docker exec` is not an option here.
arcade_curl() {
    local auth="$1" method="$2" path="$3" body="$4"
    local url="http://eip-arcadedb:2480$path"
    if [ "$method" = "GET" ]; then
        docker run --rm --network eip-net curlimages/curl:8.10.1 \
            -sf -u "$auth" "$url" >/dev/null 2>&1
    else
        docker run --rm --network eip-net curlimages/curl:8.10.1 \
            -sf -u "$auth" -X POST -H 'Content-Type: application/json' -d "$body" "$url" >/dev/null 2>&1
    fi
}

# -----------------------------------------------------------------------------
# Status
# -----------------------------------------------------------------------------
show_status() {
    banner "Orphos Services"

    local any=0 running=0 stopped=0 unhealthy=0 i ids id line name state health
    for i in "${!LAYER_PROJECTS[@]}"; do
        ids="$(docker compose -p "${LAYER_PROJECTS[$i]}" ps -a -q 2>/dev/null)"
        [ -n "$ids" ] || continue

        echo
        echo "  ${LAYER_LABELS[$i]}"
        echo "  ------------------------------------------------------"
        while IFS= read -r id; do
            [ -n "$id" ] || continue
            line="$(docker inspect --format '{{.Name}}|{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$id" 2>/dev/null)"
            [ -n "$line" ] || continue
            any=$((any + 1))
            name="${line%%|*}"; name="${name#/}"
            state="$(echo "$line" | cut -d'|' -f2)"
            health="$(echo "$line" | cut -d'|' -f3)"

            local label
            if [ "$state" = "running" ]; then
                running=$((running + 1))
                case "$health" in
                    healthy)   label="Running (healthy)" ;;
                    starting)  label="Starting" ;;
                    unhealthy) label="Running (UNHEALTHY)"; unhealthy=$((unhealthy + 1)) ;;
                    *)         label="Running" ;;
                esac
            else
                # A completed seed job is 'exited' and is not a fault.
                stopped=$((stopped + 1))
                label="$state"
            fi
            printf '  %-34s %s\n' "$name" "$label"
        done <<< "$ids"
    done

    if [ "$any" -eq 0 ]; then
        echo "  No Orphos containers exist yet."
        echo
        echo "  Orphos Status: NOT INSTALLED"
        echo
        echo "  Run  ./start.sh  to bring the platform up."
        echo
        return
    fi

    echo
    echo "  ======================================================"
    if [ "$running" -gt 0 ] && [ "$unhealthy" -eq 0 ]; then
        echo "  Orphos Status: RUNNING"
    elif [ "$running" -gt 0 ]; then
        echo "  Orphos Status: DEGRADED ($unhealthy unhealthy)"
    else
        echo "  Orphos Status: STOPPED"
    fi
    echo "  $running running, $stopped not running, $any total"
    echo
    if [ "$running" -gt 0 ]; then
        echo "  Orphos        $ORPHOS_URL"
        echo "  Sign-in       $SIGNIN_URL"
        echo "  Platform API  $PLATFORM_API"
        echo
    fi
}

# Polls the UI itself rather than trusting container health: the shell container
# is healthy long before the services behind it can answer a sign-in.
wait_for_ui() {
    local timeout="${1:-300}"
    local deadline=$(( $(date +%s) + timeout ))
    step "Waiting for the Orphos web interface to answer..."
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if command -v curl >/dev/null 2>&1; then
            if curl -sf -o /dev/null "$ORPHOS_URL"; then ok "Orphos is answering at $ORPHOS_URL"; return 0; fi
        elif command -v wget >/dev/null 2>&1; then
            if wget -q -O /dev/null "$ORPHOS_URL"; then ok "Orphos is answering at $ORPHOS_URL"; return 0; fi
        else
            # Neither is installed; container health is the best signal available.
            return 0
        fi
        sleep 5
    done
    warn "The web interface did not answer within ${timeout}s."
    echo "         The containers are up; the services may still be seeding. Try"
    echo "         $ORPHOS_URL in a browser in a few minutes, or run ./status.sh."
    return 1
}

open_browser() {
    [ "$NO_BROWSER" = "true" ] && return 0
    step "Opening $ORPHOS_URL ..."
    if   command -v xdg-open >/dev/null 2>&1; then xdg-open "$ORPHOS_URL" >/dev/null 2>&1 &
    elif command -v open     >/dev/null 2>&1; then open     "$ORPHOS_URL" >/dev/null 2>&1 &
    else echo "         No browser launcher found - open $ORPHOS_URL yourself."; fi
    return 0
}

# -----------------------------------------------------------------------------
# Commands
# -----------------------------------------------------------------------------
cmd_start() {
    banner "Orphos - starting"

    docker_ready   || return 1
    init_env_file  || return 1
    set_compose_profiles
    load_images    || return 1
    pull_images    || return 1
    init_network   || return 1
    echo

    start_layer eip-infra docker-compose.infra.yml "infrastructure (Traefik, Postgres, Keycloak, Infisical)" || return 1
    wait_seed_job eip-infisical-seed 300 || true
    wait_layer eip-infra "Infrastructure"

    start_layer eip-data docker-compose.data.yml "data layer (ArcadeDB, Fuseki, Qdrant)" || return 1
    wait_layer eip-data "Data layer"
    [ "$BUNDLED_ARCADE" = "true" ] && init_arcade_schema

    if [ "$BUNDLED_AI" = "true" ]; then
        start_layer eip-ai docker-compose.ai.yml "AI layer (Ollama)" || return 1
        wait_layer eip-ai "AI layer"
        install_ollama_models
    else
        step "EIP_BUNDLED_AI=false - skipping the local AI layer."
    fi

    # Idempotent, and required every start: the app layer mounts the connections
    # registry as an external volume, which this job creates.
    step "Ensuring the DB connections registry is seeded..."
    docker compose -p eip-infra -f docker-compose.infra.yml up -d connections-seed >/dev/null 2>&1

    start_layer eip-app docker-compose.app.yml "application layer (services + UI)" || return 1
    wait_layer eip-app "Application layer"

    show_status
    wait_for_ui 300 || true

    echo
    echo "  Orphos is running successfully."
    echo
    open_browser
    return 0
}

cmd_stop() {
    banner "Orphos - stopping"

    if ! command -v docker >/dev/null 2>&1; then warn "Docker is not installed - nothing to stop."; return 0; fi
    if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
        warn "The Docker engine is not running - nothing to stop."
        return 0
    fi

    # `stop`, never `down`. `down` removes containers and, with -v, the volumes -
    # which is this installation's data. Reverse dependency order.
    # Every profile, so containers a modules start added (mobile-proxy, voice,
    # whisper, docling) are stopped too - compose ignores inactive profiles.
    export COMPOSE_PROFILES=full,bundled-arcade,bundled-postgres,bundled-infisical-db,bundled-docling,mobile,voice
    local i
    for (( i=${#LAYER_PROJECTS[@]}-1; i>=0; i-- )); do
        [ -f "$ROOT/${LAYER_FILES[$i]}" ] || continue
        step "Stopping ${LAYER_LABELS[$i]} ..."
        docker compose -p "${LAYER_PROJECTS[$i]}" -f "${LAYER_FILES[$i]}" stop >/dev/null 2>&1
    done

    echo
    ok "Orphos is stopped. All data has been kept."
    echo "         Run  ./start.sh  to bring it back up."
    echo
    return 0
}

cmd_status() {
    if ! command -v docker >/dev/null 2>&1; then
        banner "Orphos Services"
        echo "  Docker is not installed, so Orphos cannot be running."
        echo
        echo "  Orphos Status: STOPPED (Docker is not installed)"
        echo
        return 1
    fi
    if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
        banner "Orphos Services"
        echo "  The Docker engine is not running, so Orphos is not running either."
        echo
        echo "  Orphos Status: STOPPED (Docker is not running)"
        echo
        return 1
    fi
    show_status
    return 0
}

# -----------------------------------------------------------------------------
# Functionality-scoped start (startmodules.sh). Same catalogue as the repo's
# startmodules.sh and as orphos.ps1 - keep the three in sync.
# key|ai|plugins|services|description
# -----------------------------------------------------------------------------
BASE_BACKEND="platform-core platform-registry admin-console widgets"
BASE_UI="shell plugin-widgets"
MODULE_TABLE="
admin|false|-|-|Platform admin console (tenant / branding / config / RBAC / onboarding)
core|true|plugin-decision-log|graph-proxy okg-ingest okg-reasoning priority-engine briefing-service decision-log|Core knowledge-graph & insight services (graph, OKG, priority, briefing, decision log)
dataingestion|false|-|data-ingestion okg-ingest|Enterprise data ingestion (sources, mappings, batch jobs -> graph)
docingest|true|plugin-doc-ingest|doc-ingest docling|Document ingestion (doc-ingest + Docling parser)
aicopilot|true|plugin-ai-copilot plugin-rag-search|nl-query doc-ingest graph-proxy|AI Co-pilot (NL query, RAG document search, SPARQL chat)
communication|true|plugin-email-scanner|mail-insight notification-service nl-query mobile-bff|Email + channel communication (mail insight, Slack/Teams, notifications)
mobile|false|-|mobile-bff mail-insight notification-service decision-log priority-engine briefing-service|Native mobile app backend (mobile BFF + its services); API proxy on port 3002
voice|false|-|voice whisper|Voice / speech-to-text (Whisper); runs while a tenant has voice on in Admin -> Voice
"
SERVICE_PORTS="graph-proxy:8080 okg-ingest:8081 nl-query:8082 priority-engine:8083 briefing-service:8084 decision-log:8086 notification-service:8087 okg-reasoning:8088 doc-ingest:8091 mail-insight:8092 data-ingestion:8096 mobile-bff:8097 voice:8099 docling:5001"

has_word() { local w; for w in $1; do [ "$w" = "$2" ] && return 0; done; return 1; }
module_field() { # $1 = key, $2 = field number (2..5)
    printf '%s\n' "$MODULE_TABLE" | awk -F'|' -v k="$1" -v f="$2" '$1 == k { print $f }'
}

modules_usage() {
    echo "Usage: ./startmodules.sh <module>[,<module>...]     (Windows: startmodules.bat)"
    echo
    echo "Modules:"
    printf '%s\n' "$MODULE_TABLE" | awk -F'|' 'NF { printf "  %-15s %s\n", $1, $5 }'
    echo
    echo "Aliases:"
    echo "  email           communication"
    echo "  all             every module above"
    echo
    echo "Every module also starts the admin console, the dashboard and the web UI"
    echo "(except \"mobile\" on its own, which needs no web UI)."
    echo
    echo "Examples:"
    echo "  ./startmodules.sh admin"
    echo "  ./startmodules.sh core,aicopilot,docingest"
    echo "  ./startmodules.sh all"
}

cmd_modules() {
    local raw t m selected="" backend="$BASE_BACKEND" ui="$BASE_UI" voice="" need_ai="false"
    raw="$(echo "$*" | tr ',' ' ' | tr '[:upper:]' '[:lower:]')"
    if [ -z "${raw// /}" ] || has_word "$raw" help || has_word "$raw" -h || has_word "$raw" --help; then
        modules_usage
        return 0
    fi
    for t in $raw; do
        case "$t" in
            email) t="communication" ;;
            all)   t="admin core dataingestion docingest aicopilot communication mobile voice" ;;
        esac
        for m in $t; do
            if [ -z "$(module_field "$m" 2)" ]; then
                fail "Unknown module '$m'."
                echo
                modules_usage
                return 2
            fi
            has_word "$selected" "$m" || selected="${selected:+$selected }$m"
        done
    done

    banner "Orphos - starting modules"
    for m in $selected; do printf '  - %-14s %s\n' "$m" "$(module_field "$m" 5)"; done
    echo

    docker_ready  || return 1
    init_env_file || return 1
    set_compose_profiles

    local s
    for m in $selected; do
        for s in $(module_field "$m" 4); do [ "$s" = "-" ] || has_word "$backend" "$s" || backend="$backend $s"; done
        for s in $(module_field "$m" 3); do [ "$s" = "-" ] || has_word "$ui" "$s" || ui="$ui $s"; done
        [ "$(module_field "$m" 2)" = "true" ] && need_ai="true"
    done

    local profiles="$COMPOSE_PROFILES"
    # mobile on its own never loads the web SPA: the light mobile-proxy replaces
    # the shell. In a union both run - mobile-proxy has its own port (3002).
    if has_word "$selected" mobile; then
        [ "$selected" = "mobile" ] && ui=""
        ui="${ui:+$ui }mobile-proxy"
        profiles="${profiles:+$profiles,}mobile"
    fi
    # Voice containers are CREATED stopped; platform-core's reconciler starts
    # them for tenants that switch voice on.
    local rest=""
    for s in $backend; do
        case "$s" in
            voice|whisper) voice="${voice:+$voice }$s" ;;
            docling) case ",$profiles," in *,bundled-docling,*) rest="$rest $s" ;; esac ;;
            *) rest="$rest $s" ;;
        esac
    done
    backend="${rest# }"
    [ -n "$voice" ] && profiles="${profiles:+$profiles,}voice"
    export COMPOSE_PROFILES="$profiles"

    if [ "$need_ai" = "true" ] && [ "$BUNDLED_AI" != "true" ]; then
        step "EIP_BUNDLED_AI=false - using the remote Ollama named in .env."
        need_ai="false"
    fi
    NEED_AI="$need_ai"
    APP_SERVICES="$backend $ui $voice"

    load_images  || return 1
    pull_images  || return 1
    init_network || return 1
    echo

    start_layer eip-infra docker-compose.infra.yml "infrastructure (Traefik, Postgres, Keycloak, Infisical)" || return 1
    wait_seed_job eip-infisical-seed 300 || true
    wait_layer eip-infra "Infrastructure"

    start_layer eip-data docker-compose.data.yml "data layer (ArcadeDB, Fuseki, Qdrant)" || return 1
    wait_layer eip-data "Data layer"
    [ "$BUNDLED_ARCADE" = "true" ] && init_arcade_schema

    if [ "$need_ai" = "true" ]; then
        start_layer eip-ai docker-compose.ai.yml "AI layer (Ollama)" || return 1
        wait_layer eip-ai "AI layer"
        install_ollama_models
    else
        step "AI layer skipped - the selected modules need no local LLM."
    fi

    step "Ensuring the DB connections registry is seeded..."
    docker compose -p eip-infra -f docker-compose.infra.yml up -d connections-seed >/dev/null 2>&1

    # No --remove-orphans: containers of modules started earlier must survive.
    step "Starting services: $backend"
    # shellcheck disable=SC2086
    if ! docker compose -p eip-app -f docker-compose.app.yml up -d --no-build $backend; then
        fail "Services failed to start. Inspect them with:"
        echo "             docker compose -p eip-app -f docker-compose.app.yml logs"
        return 1
    fi
    # --no-deps keeps the shell from dragging in every plugin remote.
    step "Starting UI: $ui"
    # shellcheck disable=SC2086
    if ! docker compose -p eip-app -f docker-compose.app.yml up -d --no-build --no-deps $ui; then
        fail "The UI containers failed to start."
        return 1
    fi
    if [ -n "$voice" ]; then
        step "Preparing voice containers (created, not started)..."
        # shellcheck disable=SC2086
        if docker compose -p eip-app -f docker-compose.app.yml up --no-start --no-build $voice; then
            ok "Voice is available. It starts by itself for tenants with voice on in Admin -> Voice."
        else
            warn "Could not prepare the voice containers; Admin -> Voice will show it as unavailable."
        fi
    fi
    wait_layer eip-app "Application layer"

    echo
    echo "  Modules '$(echo "$selected" | tr ' ' ',')' are up."
    echo
    has_word "$ui" shell        && echo "  Orphos (web UI)      $ORPHOS_URL"
    has_word "$ui" mobile-proxy && echo "  Mobile API           http://localhost:3002"
    echo "  Sign-in (Keycloak)   $SIGNIN_URL"
    echo "  Platform API         $PLATFORM_API"
    local pair
    for pair in $SERVICE_PORTS; do
        has_word "$backend" "${pair%%:*}" && printf '  %-20s http://localhost:%s\n' "${pair%%:*}" "${pair##*:}"
    done
    echo
    echo "  Only these modules run: menus for the others will not appear."
    echo "  Add more with e.g.  ./startmodules.sh communication   - full platform: ./start.sh"
    echo
    if has_word "$ui" shell; then
        wait_for_ui 300 || true
        open_browser
    fi
    return 0
}

case "${1:-start}" in
    start)   cmd_start;  exit $? ;;
    stop)    cmd_stop;   exit $? ;;
    status)  cmd_status; exit $? ;;
    restart) cmd_stop; cmd_start; exit $? ;;
    modules) shift; cmd_modules "$@"; exit $? ;;
    *)
        echo "Usage: $(basename "$0") {start|stop|restart|status|modules <module,...>}"
        exit 2
        ;;
esac
