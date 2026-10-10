#!/usr/bin/env bash
# Orphos EIP - Platform Startup
#
# The single startup implementation for every OS:
#   Linux / macOS : ./start.sh [module]
#   Windows       : start.bat [module]   (locates Git Bash and runs this script)
#
# Usage:
#   start          -> full platform (all layers)
#   start infra    -> infrastructure layer only
#   start data     -> data layer only
#   start ai       -> Ollama AI layer only
#   start app      -> application layer only
#   start <svc>    -> one app-layer service (e.g. nl-query, doc-ingest, shell,
#                     plugin-ai-copilot)
#
# When a single module is requested, the infrastructure layer is started
# automatically if it is not already running (every other layer depends on
# the eip-net network and Keycloak/Postgres it provides). Already-running
# containers from other layers are left untouched.
#
# On Windows and macOS a stopped Docker Desktop is launched automatically and
# waited for; on Linux the engine must already be up (sudo systemctl start
# docker) - see scripts/lib/eip-platform.sh.
#
# Compose files are referenced by RELATIVE name on purpose: Git Bash rewrites
# POSIX-looking absolute paths when passing them to docker.exe, which corrupts
# the -f arguments. Everything below therefore runs from $ROOT_DIR.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"
# shellcheck source=scripts/lib/eip-platform.sh
. "$ROOT_DIR/scripts/lib/eip-platform.sh"

SELF="$(eip_launcher start)"
MODULE="${1:-all}"
MODULE="$(echo "$MODULE" | tr '[:upper:]' '[:lower:]')"

if [[ "$MODULE" =~ ^(-h|--help|help|/\?)$ ]]; then
    echo "Usage: $SELF [module]"
    echo
    echo "  (no argument)   full platform - all layers"
    echo "  infra           infrastructure layer (Traefik, PostgreSQL, Keycloak, Infisical, ...)"
    echo "  data            data layer (ArcadeDB, Jena Fuseki, Qdrant)"
    echo "  ai              Ollama AI layer (models pulled if missing)"
    echo "  app             application layer (all microservices + UI)"
    echo "  <service>       a single app-layer service, e.g. nl-query, doc-ingest,"
    echo "                  shell, plugin-ai-copilot, plugin-rag-search, ..."
    exit 0
fi

echo
echo "============================================================"
echo "  Orphos EIP - Platform Startup  (module: $MODULE)"
echo "============================================================"
echo

# A client package has prebuilt images and no source tree, so the Maven and
# `docker compose build` steps below cannot run. Hand over to the package's own
# launcher instead of failing mid-build. No-op in an ordinary checkout.
eip_delegate_if_distribution

# ---------------------------------------------------------------
# Docker check (launches Docker Desktop on Windows/macOS if needed) and
# .env bootstrap.
# ---------------------------------------------------------------
eip_require_docker
eip_ensure_env_file

# ---------------------------------------------------------------
# Read DEMO_MODE from .env (default: true)
# ---------------------------------------------------------------
demoMode="$(eip_env_bool DEMO_MODE true)"

profiles=()
if [ "$demoMode" = "true" ]; then
    echo "[INFO] DEMO_MODE=true  - demo startup (Kafka, Fluent-bit, Jaeger, VictoriaLogs,"
    echo "       Prometheus, and Perses are skipped)."
    echo "       Set DEMO_MODE=false in .env for the full stack."
else
    echo "[INFO] DEMO_MODE=false - full stack (all services will start)."
    profiles+=("full")
fi

# Bundled databases run in-container by default. ArcadeDB and Postgres are
# INDEPENDENT: EIP_BUNDLED_ARCADEDB and EIP_BUNDLED_POSTGRES each default to
# EIP_BUNDLED_DB (the legacy master, default true) so existing .env files behave
# exactly as before, but either can be flipped alone to point that one component
# at an external host.
bundledDb="$(eip_env_bool EIP_BUNDLED_DB true)"
bundledArcade="$(eip_env_bool EIP_BUNDLED_ARCADEDB "$bundledDb")"
bundledPostgres="$(eip_env_bool EIP_BUNDLED_POSTGRES "$bundledDb")"
bundledInfisicalDb="$(eip_env_bool EIP_BUNDLED_INFISICAL_DB "$bundledDb")"
# Docling is not a database, so it does NOT inherit EIP_BUNDLED_DB - it defaults
# to true on its own. Set EIP_BUNDLED_DOCLING=false to run it on a second
# machine and point DOCLING_URL there (see docker-compose.remote-ai.yml).
bundledDocling="$(eip_env_bool EIP_BUNDLED_DOCLING true)"
# The Ollama layer (docker-compose.ai.yml) can run on a SECOND machine to free
# ~9 GB here. Set EIP_BUNDLED_AI=false and point OLLAMA_URL / OLLAMA_HI_URL /
# OLLAMA_EMBED_URL at that host (see docker-compose.remote-ai.yml). Unlike the
# bundled DBs this is not a compose profile - the ai layer is its own file, so
# it is gated in the layer resolution below instead.
bundledAi="$(eip_env_bool EIP_BUNDLED_AI true)"

# Voice ("VoiceEnabled") - speech-to-text for Ask Orphos and for audio files in
# document ingestion. Opt-in because the Whisper model container costs ~3 GB and
# downloads its weights on first use.
#
# EIP_VOICE_ENABLED=true does NOT start voice by itself. It makes voice AVAILABLE:
# the "voice" profile containers are CREATED (see the --no-start pass below), and
# platform-core's VoiceModuleReconciler then runs them whenever at least one
# tenant has voice switched on in Admin -> Voice (and stops them when none has).
# So an operator enables the capability once, each tenant is turned on from that
# tab, and from then on every launch brings voice up for those tenants by itself.
voiceEnabled="$(eip_env_bool EIP_VOICE_ENABLED false)"

[ "$bundledArcade" = "true" ] && profiles+=("bundled-arcade")
[ "$bundledPostgres" = "true" ] && profiles+=("bundled-postgres")
[ "$bundledInfisicalDb" = "true" ] && profiles+=("bundled-infisical-db")
[ "$bundledDocling" = "true" ] && profiles+=("bundled-docling")
# Note: "voice" is deliberately NOT added to COMPOSE_PROFILES. Adding it would
# make the main `up -d` below START Whisper, which is exactly what the tenant
# toggles are meant to decide. The voice containers are created in a separate
# --no-start pass after the app layer is up (search for eip_voice_prepare).
#
# "mobile" is likewise never added. Only mobile-proxy (the app's own port 3002)
# is opt-in: it comes up only via `startmodules mobile` (or the same
# functionality chosen in the setup wizard's web UI). mobile-bff is NOT behind
# the profile and starts with the platform, because the web dashboard's Tasks &
# Approvals panel and Travel rail read it — web and app share one approval store.
echo "[INFO] Bundled DBs - ArcadeDB: $bundledArcade | Postgres(Keycloak): $bundledPostgres | Postgres(Infisical): $bundledInfisicalDb | Docling: $bundledDocling"
echo "[INFO] Voice (speech-to-text) - $voiceEnabled"

profilesJoined=""
if [ "${#profiles[@]}" -gt 0 ]; then
    profilesJoined="$(IFS=,; echo "${profiles[*]}")"
fi
export COMPOSE_PROFILES="$profilesJoined"
echo

# ---------------------------------------------------------------
# Resolve which layers to start from the requested module
# ---------------------------------------------------------------
appService=""
case "$MODULE" in
    all|infra|data|ai|app) ;;
    *)
        appServices="$(docker compose -f docker-compose.app.yml config --services 2>/dev/null)"
        if echo "$appServices" | tr -d '\r' | grep -qx "$MODULE"; then
            appService="$MODULE"
        else
            echo "[ERROR] Unknown module '$MODULE'."
            echo "        Layers: infra, data, ai, app, all"
            echo "        App services: $(echo "$appServices" | tr '\n' ' ')"
            exit 1
        fi
        ;;
esac

startInfra=false; startData=false; startAi=false; startApp=false
case "$MODULE" in all|infra) startInfra=true ;; esac
case "$MODULE" in all|data)  startData=true ;; esac
case "$MODULE" in all|ai)    startAi=true ;; esac
case "$MODULE" in all|app)   startApp=true ;; esac

# EIP_BUNDLED_AI=false -> the Ollama containers live on another machine, so never
# start them locally. Warn (rather than silently no-op) if 'ai' was asked for by
# name, so a deliberate `start ai` does not look like it succeeded.
if [ "$startAi" = "true" ] && [ "$bundledAi" != "true" ]; then
    if [ "$MODULE" = "ai" ]; then
        echo "[AI] EIP_BUNDLED_AI=false - the Ollama layer is remote; nothing to start locally."
        echo "     Endpoints in use: OLLAMA_URL / OLLAMA_HI_URL / OLLAMA_EMBED_URL from .env"
    else
        echo "[AI] EIP_BUNDLED_AI=false - skipping local Ollama layer (running remotely)."
    fi
    startAi=false
fi

# Every other layer joins the eip-net network created by the infra stack and
# talks to Keycloak/Postgres, so bring infra up first if it is not running.
if [ "$startInfra" != "true" ]; then
    netUp="$(docker network ls -q --filter "name=^eip-net$" 2>/dev/null)"
    kcUp="$(docker ps -q --filter "name=^eip-keycloak$" --filter "status=running" 2>/dev/null)"
    if [ -z "$netUp" ] || [ -z "$kcUp" ]; then
        echo "[INFO] Infrastructure layer is not running - it will be started first (required dependency)."
        startInfra=true
    fi
fi

# ---------------------------------------------------------------
# Clean up stale eip- containers (full startup only - a single-module
# start must leave the other layers' containers running)
# ---------------------------------------------------------------
if [ "$MODULE" = "all" ]; then
    stale="$(docker ps -aq --filter "name=eip-" 2>/dev/null)"
    if [ -n "$stale" ]; then
        # shellcheck disable=SC2086
        docker rm -f $stale >/dev/null 2>&1
    fi
    docker network rm eip-net >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------
# Infrastructure layer
# ---------------------------------------------------------------
if [ "$startInfra" = "true" ]; then
    if [ "$demoMode" = "true" ]; then
        infraDesc="Traefik, PostgreSQL, Keycloak, Infisical"
    else
        infraDesc="Traefik, PostgreSQL, Keycloak, Kafka, Fluent-bit, Infisical, Jaeger, VictoriaLogs"
    fi
    echo "[INFRA] Starting infrastructure layer ($infraDesc)..."
    if ! docker compose -p eip-infra -f docker-compose.infra.yml up -d --remove-orphans; then
        echo "[ERROR] Infrastructure stack failed. Run: docker compose -p eip-infra -f docker-compose.infra.yml logs"
        exit 1
    fi
    echo "[OK] Infrastructure layer ready."
    echo

    # Wait for infisical-seed to finish (bootstrap + secret seeding can take a
    # bit longer on first boot than the old OpenBao seed).
    echo "      Waiting for Infisical seed..."
    if ! eip_wait_for_seed eip-infisical-seed 180; then
        exit 1
    fi
    echo "[OK] Infisical secrets seeded."
    echo
fi

# ---------------------------------------------------------------
# Data layer
# ---------------------------------------------------------------
if [ "$startData" = "true" ]; then
    if [ "$bundledArcade" != "true" ]; then
        # External ArcadeDB mode: the bundled ArcadeDB container and the localhost
        # schema-init are skipped. Fuseki/Qdrant still start (they are not part of
        # the DB Connections registry). The external ArcadeDB's schema is created
        # in-app by SchemaBootstrap on first boot (it self-creates the database).
        echo "[DATA] EIP_BUNDLED_ARCADEDB=false - skipping bundled ArcadeDB + localhost schema-init."
        echo "       Starting the rest of the data layer (Jena Fuseki, Qdrant)..."
        if ! docker compose -p eip-data -f docker-compose.data.yml up -d --remove-orphans; then
            echo "[ERROR] Data stack failed. Run: docker compose -p eip-data -f docker-compose.data.yml logs"
            exit 1
        fi
        echo "[OK] Data layer ready (external ArcadeDB expected via DB Connections)."
        echo
    else
        echo "[DATA] Starting data layer (ArcadeDB, Jena Fuseki, Qdrant)..."
        if ! docker compose -p eip-data -f docker-compose.data.yml up -d --remove-orphans; then
            echo "[ERROR] Data stack failed. Run: docker compose -p eip-data -f docker-compose.data.yml logs"
            exit 1
        fi
        echo "[OK] Data layer ready."
        echo

        # ArcadeDB schema (the init script waits for ArcadeDB to become reachable)
        echo "Initialising ArcadeDB schema..."
        bash "$ROOT_DIR/scripts/init-arcadedb-schema.sh" >/dev/null 2>&1 || true
        echo "[OK] ArcadeDB schema ready."
        echo
    fi
fi

# ---------------------------------------------------------------
# Ollama AI layer
# ---------------------------------------------------------------
if [ "$startAi" = "true" ]; then
    echo "[AI] Starting Ollama containers..."
    # A natively installed Ollama holds 11434 and blocks the container's port.
    eip_stop_host_ollama
    docker compose -p eip-ai -f docker-compose.ai.yml up -d --remove-orphans

    ollamaWait=0
    r1=""; r2=""
    while { [ -z "$r1" ] || [ -z "$r2" ]; } && [ "$ollamaWait" -lt 60 ]; do
        sleep 5
        ollamaWait=$((ollamaWait + 5))
        r1="$(docker ps -q -f name='^eip-ollama$' -f status=running 2>/dev/null)"
        r2="$(docker ps -q -f name='^eip-ollama-hi$' -f status=running 2>/dev/null)"
    done

    if [ -z "$r1" ] || [ -z "$r2" ]; then
        echo "[ERROR] Ollama containers did not start. Check: docker logs eip-ollama"
        exit 1
    fi
    echo "[OK] Ollama containers started."

    # Pull models only if missing. Embeddings run on the hi-memory container
    # (see docker-compose.ai.yml), so bge-m3 must live there, not on the primary
    # Ollama. bge-m3 is multilingual (incl. Japanese), 1024-dim - it replaces
    # nomic-embed-text.
    # qwen2.5:1.5b is pulled onto BOTH Ollama containers: the hi-memory one
    # serves it as the 'inhouse-gemma' provider, and the primary serves it as
    # the default 'inhouse' chat model (OLLAMA_CHAT_MODEL) — the primary must
    # actually hold whatever OLLAMA_CHAT_MODEL names, or the copilot 404s with
    # "model not found".
    MODEL_CONTAINERS=(eip-ollama eip-ollama eip-ollama eip-ollama-hi eip-ollama-hi)
    MODEL_NAMES=(granite3.1-dense:2b phi3.5 qwen2.5:1.5b qwen2.5:1.5b bge-m3)
    for i in "${!MODEL_NAMES[@]}"; do
        c="${MODEL_CONTAINERS[$i]}"; m="${MODEL_NAMES[$i]}"
        if ! docker exec "$c" ollama list 2>/dev/null | grep -q "$m"; then
            echo "      Pulling $m into $c..."
            docker exec "$c" ollama pull "$m"
        fi
    done
    echo "[OK] Ollama models ready."
    echo
fi

# ---------------------------------------------------------------
# Ensure the DB connections registry volume exists and is seeded.
# The app stack mounts eip-infra_eip_connections as an EXTERNAL volume. If the
# infra layer was already running from before this feature (so its infra 'up'
# was skipped above), that volume/seed does not exist yet and the app stack
# fails with "external volume eip-infra_eip_connections not found". The
# connections-seed job is idempotent (writes connections.json only when absent),
# so running it here is safe on every start.
# ---------------------------------------------------------------
if [ "$startApp" = "true" ] || [ -n "$appService" ]; then
    echo "[DATA] Ensuring DB connections registry is seeded..."
    if ! docker compose -p eip-infra -f docker-compose.infra.yml up -d connections-seed >/dev/null 2>&1; then
        echo "[WARN] Could not run connections-seed; the app stack may fail if eip-infra_eip_connections is missing."
    fi
    echo
fi

# ---------------------------------------------------------------
# Application layer (full)
# ---------------------------------------------------------------
if [ "$startApp" = "true" ]; then
    # Every Java service Dockerfile starts with `COPY target/*.jar`, so Maven
    # runs first. Package the whole reactor rather than a named subset: a module
    # left out has no target/ directory and its image build dies with
    # "lstat /target: no such file or directory", which also cancels the sibling
    # builds sharing that `docker compose build` call.
    echo "Building service JARs (full Maven reactor)..."
    if ! eip_mvn -B -q -DskipTests package; then
        echo "[ERROR] Maven build failed. Run: mvn -DskipTests package"
        exit 1
    fi
    echo "[OK] Service JARs built."
    echo

    echo "[APP] Building and starting application layer..."

    # nl-query, doc-ingest, data-ingestion and decision-log are ordinary Java
    # images - fine to build together.
    if ! docker compose -p eip-app -f docker-compose.app.yml build --no-cache nl-query doc-ingest data-ingestion decision-log; then
        echo "[ERROR] App build failed (nl-query/doc-ingest/data-ingestion/decision-log)."
        exit 1
    fi

    # UI images (shell + 6 plugin remotes) are memory-heavy Vite/Node builds
    # (~11,000+ modules transformed each, incl. the full @mui/icons-material
    # bundle). Passing them all to one "docker compose build" call lets BuildKit
    # build them concurrently, which can crash the build worker on a Docker
    # Desktop VM with a modest memory allocation - surfaces as "rpc error: code
    # = Unavailable ... EOF" mid-build (each image builds fine alone). Building
    # one at a time avoids that at the cost of some wall-clock time.
    for img in shell plugin-ai-copilot plugin-email-scanner plugin-doc-ingest plugin-rag-search plugin-widgets plugin-decision-log; do
        echo "      Building $img..."
        if ! docker compose -p eip-app -f docker-compose.app.yml build --no-cache "$img"; then
            echo "[ERROR] App build failed ($img)."
            exit 1
        fi
    done
    if ! docker compose -p eip-app -f docker-compose.app.yml up -d --remove-orphans; then
        echo "[ERROR] App stack failed to start."
        exit 1
    fi
    echo "[OK] Application layer ready."
    echo

    # ── Voice ("VoiceEnabled"): create, do not start ──────────────────────────
    # eip_voice_prepare. The voice containers are built and CREATED here but left
    # stopped, so that Admin -> Voice can start them on demand. `up --no-start`
    # is the whole trick: a container that was never created cannot be started
    # through the Docker API, so the admin switch would have nothing to act on,
    # while a container that was created and started would defeat the opt-in.
    #
    # --remove-orphans is deliberately absent: this call names only the voice
    # services, and with the "voice" profile active compose would otherwise
    # consider every non-voice container an orphan and tear the stack down.
    if [ "$voiceEnabled" = "true" ]; then
        echo "[VOICE] Preparing voice containers (created, NOT started)..."
        if docker compose -p eip-app -f docker-compose.app.yml --profile voice build voice \
           && docker compose -p eip-app -f docker-compose.app.yml --profile voice up --no-start whisper voice; then
            echo "[OK] Voice is available. It starts automatically for tenants that have voice"
            echo "     switched on in Admin -> Voice, and stays stopped while none has."
        else
            echo "[WARN] Could not prepare the voice containers. Voice will show as unavailable in Admin -> Voice."
        fi
        echo
    fi
fi

# ---------------------------------------------------------------
# Application layer (single service)
# ---------------------------------------------------------------
if [ -n "$appService" ]; then
    case "$appService" in
        doc-ingest|data-ingestion|decision-log)
            # These services' Docker builds copy a prebuilt JAR, so Maven runs first.
            svcModule="services/${appService}-service"
            echo "Building $svcModule JAR..."
            if ! eip_mvn -B -q -pl "$svcModule" -am -DskipTests package; then
                echo "[ERROR] Maven build failed. Run: mvn -pl $svcModule -am -DskipTests package"
                exit 1
            fi
            echo "[OK] $svcModule JAR built."
            echo
            ;;
    esac

    echo "[APP] Building and starting service '$appService' (plus its declared dependencies)..."
    # No --remove-orphans here: the other app services must keep running.
    if ! docker compose -p eip-app -f docker-compose.app.yml up -d --build "$appService"; then
        echo "[ERROR] Failed to start '$appService'. Run: docker compose -p eip-app -f docker-compose.app.yml logs $appService"
        exit 1
    fi
    echo "[OK] Service '$appService' running."
    echo
    echo "[NOTE] Only the requested service (and services it depends_on) were started."
    echo "       If it needs the data or AI layer, run: $SELF data  /  $SELF ai"
fi

# ---------------------------------------------------------------
# Summary
# ---------------------------------------------------------------
[ "$startInfra" = "true" ] && docker compose -p eip-infra -f docker-compose.infra.yml ps
[ "$startData" = "true" ] && docker compose -p eip-data -f docker-compose.data.yml ps
[ "$startAi" = "true" ] && docker compose -p eip-ai -f docker-compose.ai.yml ps
{ [ "$startApp" = "true" ] || [ -n "$appService" ]; } && docker compose -p eip-app -f docker-compose.app.yml ps

if [ "$MODULE" = "all" ]; then
    echo
    echo "============================================================"
    echo "  Platform is UP. Service endpoints:"
    echo
    echo "  Shell (Module Federation host)  http://localhost:3001"
    echo "  ArcadeDB Studio      http://localhost:2480"
    echo "  Keycloak Admin       http://localhost:8443"
    echo "  Jena Fuseki          http://localhost:3030"
    echo "  Qdrant Dashboard     http://localhost:6333/dashboard"
    echo "  Infisical            http://localhost:8222"
    echo "  Ollama (primary)     http://localhost:11434"
    echo "  Ollama (hi-mem)      http://localhost:11435"
    if [ "$demoMode" != "true" ]; then
        echo "  Prometheus           http://localhost:9090"
        echo "  Perses (dashboards)  http://localhost:3000"
        echo "  Jaeger (traces)      http://localhost:16686"
        echo "  VictoriaLogs         http://localhost:9428/select/vmui"
    fi
    echo
    echo "  Microservices:"
    echo "    graph-proxy        http://localhost:8080"
    echo "    okg-ingest         http://localhost:8081"
    echo "    nl-query           http://localhost:8082"
    echo "    priority-engine    http://localhost:8083"
    echo "    briefing           http://localhost:8084"
    echo "    agent-orchestrator http://localhost:8085"
    echo "    decision-log       http://localhost:8086"
    echo "    notification       http://localhost:8087"
    echo "    okg-reasoning      http://localhost:8088"
    echo "    platform-core      http://localhost:8090   (tenant/auth/onboarding/ops - admin-service split)"
    echo "    doc-ingest         http://localhost:8091"
    echo "    mail-insight       http://localhost:8092"
    echo "    platform-registry  http://localhost:8093   (plugin manifests)"
    echo "    admin-console      http://localhost:8094   (branding/email/query-history)"
    echo "    widgets            http://localhost:8095   (dashboard layout)"
    echo "    data-ingestion     http://localhost:8096   (enterprise data-source ingestion)"
    echo "    mcp-priority       http://localhost:8101   (MCP tool server: priority.*)"
    echo "    mcp-decision       http://localhost:8102   (MCP tool server: decision.*)"
    echo "    mcp-rag            http://localhost:8105   (MCP tool server: rag.*)"
    echo "    mcp-mail           http://localhost:8106   (MCP tool server: mail.*)"
    echo "    mcp-comms          http://localhost:8112   (MCP tool server: comms.*)"
    echo "    mcp-reasoning      http://localhost:8107   (MCP tool server: okg.get_insights/run_reasoning)"
    echo "    mcp-graph          http://localhost:8108   (MCP tool server: graph.sparql_query)"
    echo "    mcp-ingest         http://localhost:8109   (MCP tool server: ingest.*/okg.trigger_sync)"
    echo "    agent-briefing     http://localhost:8103   (Briefing agent, SUGGEST_ONLY)"
    echo "    agent-decision     http://localhost:8104   (Decision agent, ACT_WITH_APPROVAL)"
    echo "    agent-email-triage http://localhost:8110   (Email Triage agent, ACT_WITH_APPROVAL)"
    echo "    agent-comms-triage http://localhost:8113   (Communications Triage agent, ACT_WITH_APPROVAL)"
    echo "    agent-attention    http://localhost:8111   (Attention/Priority agent, SUGGEST_ONLY)"
    if [ "$demoMode" = "true" ]; then
        echo
        echo "  (DEMO_MODE=true - Kafka, Fluent-bit, Jaeger, VictoriaLogs, Prometheus, Perses not started)"
        echo "  (Set DEMO_MODE=false in .env to enable the full stack)"
    fi
    echo "============================================================"
    echo
else
    echo
    echo "============================================================"
    echo "  Module '$MODULE' is UP."
    echo "============================================================"
    echo
fi

exit 0
