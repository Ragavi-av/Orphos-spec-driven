#!/usr/bin/env bash
# Orphos EIP - Functionality-scoped startup script
#
# The single implementation for every OS:
#   Linux / macOS : ./startmodules.sh <functionality>[,<functionality>...]
#   Windows       : startmodules.bat <functionality>[,...]
#                   (locates Git Bash and runs this same script)
#
# Starts ONLY the containers required for one (or several) functionalities,
# instead of the whole platform. Functionalities are GRANULAR and COMPOSABLE:
# each one declares exactly the plugins (frontend remotes), backend services,
# MCP tool servers and agents it needs, and the union of the requested set is
# what gets started. Everything else stays down.
#
#   Application functionalities
#   ---------------------------
#   ./startmodules.sh admin           -> Platform admin console (tenant/branding/config/RBAC)
#   ./startmodules.sh core            -> Lean core knowledge-graph & insight services
#   ./startmodules.sh dataingestion   -> Enterprise data ingestion (sources/mappings/jobs)
#   ./startmodules.sh docingest       -> Document ingestion (doc-ingest + Docling parser)
#   ./startmodules.sh aicopilot       -> AI Co-pilot (NL query + RAG search + SPARQL chat)
#   ./startmodules.sh communication   -> Email + channel comms (mail insight, Slack/Teams, notifications)
#   ./startmodules.sh mobile          -> Native mobile app backend only (Keycloak + DBs + mobile BFF & its services; no Ollama/MCP/agents/web plugins).
#                                        OPT-IN: its containers sit behind the "mobile" compose profile, so the full-platform
#                                        start never brings them up, and they listen on their OWN ports (API proxy 3002,
#                                        BFF 8097) rather than the web UI's 3001.
#   ./startmodules.sh voice           -> Voice / speech-to-text (Whisper). Created stopped; runs automatically while a tenant has voice on in Admin -> Voice
#
#   MCP tool servers (each pulls in only the upstream service it wraps)
#   ------------------------------------------------------------------
#   ./startmodules.sh mcp-graph | mcp-reasoning | mcp-priority | mcp-decision
#   ./startmodules.sh mcp-rag   | mcp-ingest    | mcp-mail     | mcp-comms
#
#   Agents (each pulls in its MCP tool server(s) + their upstream service(s))
#   ------------------------------------------------------------------------
#   ./startmodules.sh agent-orchestrator | agent-briefing | agent-attention
#   ./startmodules.sh agent-decision     | agent-email-triage | agent-comms-triage
#
#   Aliases / bundles
#   -----------------
#   ./startmodules.sh email           -> alias of `communication`
#   ./startmodules.sh mcp             -> every MCP tool server + upstreams
#   ./startmodules.sh agents          -> the whole agent mesh (all agents + MCP + upstreams)
#
#   Unions
#   ------
#   ./startmodules.sh aicopilot,communication,agent-email-triage
#
# Run "./startmodules.sh help" for the full list. The catalogue below is the
# single source of truth: tools/setup-wizard reads it by running this script's
# `list-json` mode, on every OS.
#
# Compose files are referenced by RELATIVE name on purpose: Git Bash rewrites
# POSIX-looking absolute paths when passing them to docker.exe, which corrupts
# the -f arguments. Everything below therefore runs from $ROOT_DIR.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"
# shellcheck source=scripts/lib/eip-platform.sh
. "$ROOT_DIR/scripts/lib/eip-platform.sh"

SELF="$(eip_launcher startmodules)"
FULL_LAUNCHER="$(eip_launcher start)"

# ---------------------------------------------------------------
# Mandatory control-plane / UI base
# ---------------------------------------------------------------
BASE_BACKEND=(platform-core platform-registry admin-console widgets)
BASE_UI=(shell plugin-widgets)

# ---------------------------------------------------------------
# Functionality catalogue: key|group|ai|desc|plugins|services|mcp|agents
# (space-separated lists; "-" means empty).
# ---------------------------------------------------------------
FUNC_TABLE="
admin|app|false|Platform admin console (tenant / branding / config / RBAC / onboarding)|-|-|-|-
core|app|true|Lean core knowledge-graph & insight services (graph, OKG ingest/reasoning, priority, briefing, decision log)|plugin-decision-log|graph-proxy okg-ingest okg-reasoning priority-engine briefing-service decision-log|-|-
dataingestion|app|false|Enterprise data ingestion (sources, mappings, batch jobs -> graph)|-|data-ingestion okg-ingest|-|-
docingest|app|true|Document ingestion (doc-ingest + Docling parser)|plugin-doc-ingest|doc-ingest docling|-|-
aicopilot|app|true|AI Co-pilot (NL query, RAG document search, SPARQL chat)|plugin-ai-copilot plugin-rag-search|nl-query doc-ingest graph-proxy|-|-
communication|app|true|Email + channel communication (mail insight, Slack/Teams, notifications)|plugin-email-scanner|mail-insight notification-service nl-query mobile-bff|-|-
mobile|app|false|Native mobile app backend only: Keycloak + databases + the mobile BFF and the services it aggregates (decisions, mail meetings/notifications, priority, briefing). No Ollama, MCP, agents or web plugins. Opt-in ("mobile" compose profile) and on its own ports - API proxy 3002, BFF 8097 - so it never clashes with the web UI on 3001.|-|mobile-bff mail-insight notification-service decision-log priority-engine briefing-service|-|-
voice|app|false|Voice / speech-to-text ("VoiceEnabled"): dictation in Ask Orphos + audio-file transcription in document ingestion. Containers are CREATED stopped and run automatically while any tenant has voice switched on in Admin -> Voice.|-|voice whisper|-|-
mcp-graph|mcp|false|MCP: graph.sparql_query (wraps graph-proxy)|-|graph-proxy|mcp-graph|-
mcp-reasoning|mcp|false|MCP: okg.get_insights / run_reasoning (wraps okg-reasoning)|-|okg-reasoning|mcp-reasoning|-
mcp-priority|mcp|false|MCP: priority.get_queue / get_sla / get_alerts (wraps priority-engine)|-|priority-engine|mcp-priority|-
mcp-decision|mcp|true|MCP: decision.search_decisions / log_decision (wraps decision-log)|-|decision-log|mcp-decision|-
mcp-rag|mcp|true|MCP: rag.search_documents / get_document / answer (wraps doc-ingest + nl-query)|-|doc-ingest nl-query|mcp-rag|-
mcp-ingest|mcp|true|MCP: ingest.list_jobs / run_job, okg.trigger_sync (wraps doc-ingest + okg-ingest)|-|doc-ingest okg-ingest|mcp-ingest|-
mcp-mail|mcp|false|MCP: mail.list_tasks / draft_reply / send_email (wraps mail-insight)|-|mail-insight|mcp-mail|-
mcp-comms|mcp|false|MCP: comms.list_tasks / draft_reply / send_message (wraps mail-insight)|-|mail-insight|mcp-comms|-
agent-orchestrator|agent|true|Agent orchestrator (coordinates the mesh)|-|-|-|agent-orchestrator
agent-briefing|agent|true|Daily Briefing agent, SUGGEST_ONLY (priority + reasoning tools)|-|priority-engine okg-reasoning nl-query|mcp-priority mcp-reasoning|agent-briefing
agent-attention|agent|true|Attention / Priority agent, SUGGEST_ONLY (priority + reasoning tools)|-|priority-engine okg-reasoning nl-query|mcp-priority mcp-reasoning|agent-attention
agent-decision|agent|true|Decision Intelligence agent, ACT_WITH_APPROVAL (decision tool)|-|decision-log nl-query|mcp-decision|agent-decision
agent-email-triage|agent|true|Email Triage agent, ACT_WITH_APPROVAL (mail tools)|-|mail-insight nl-query|mcp-mail|agent-email-triage
agent-comms-triage|agent|true|Communications Triage agent, ACT_WITH_APPROVAL (channel tools)|-|mail-insight nl-query|mcp-comms|agent-comms-triage
"

FUNC_KEYS=()
declare -A FUNC_GROUP FUNC_AI FUNC_DESC FUNC_PLUGINS FUNC_SERVICES FUNC_MCP FUNC_AGENTS
while IFS='|' read -r key group ai desc plugins services mcp agents; do
    [ -z "$key" ] && continue
    FUNC_KEYS+=("$key")
    FUNC_GROUP["$key"]="$group"
    FUNC_AI["$key"]="$ai"
    FUNC_DESC["$key"]="$desc"
    FUNC_PLUGINS["$key"]="$([ "$plugins" = "-" ] && echo "" || echo "$plugins")"
    FUNC_SERVICES["$key"]="$([ "$services" = "-" ] && echo "" || echo "$services")"
    FUNC_MCP["$key"]="$([ "$mcp" = "-" ] && echo "" || echo "$mcp")"
    FUNC_AGENTS["$key"]="$([ "$agents" = "-" ] && echo "" || echo "$agents")"
done <<< "$FUNC_TABLE"

# Aliases expand to a union of catalogue keys (one level - aliases never nest).
ALIAS_KEYS=(email mcp agents)
declare -A ALIAS_MEMBERS
ALIAS_MEMBERS[email]="communication"
ALIAS_MEMBERS[mcp]="mcp-graph mcp-reasoning mcp-priority mcp-decision mcp-rag mcp-ingest mcp-mail mcp-comms"
ALIAS_MEMBERS[agents]="agent-orchestrator agent-briefing agent-attention agent-decision agent-email-triage agent-comms-triage"

# compose service -> image name from docker-compose.app.yml (keep in sync).
# Services absent here (docling) use pulled images and need no local build.
declare -A SERVICE_IMAGE
SERVICE_IMAGE[platform-core]=eip-platform-core
SERVICE_IMAGE[platform-registry]=eip-platform-registry
SERVICE_IMAGE[admin-console]=eip-admin-console
SERVICE_IMAGE[widgets]=eip-widgets
SERVICE_IMAGE[shell]=eip-shell
SERVICE_IMAGE[mobile-proxy]=eip-mobile-proxy
SERVICE_IMAGE[plugin-ai-copilot]=eip-plugin-ai-copilot
SERVICE_IMAGE[plugin-email-scanner]=eip-plugin-email-scanner
SERVICE_IMAGE[plugin-doc-ingest]=eip-plugin-doc-ingest
SERVICE_IMAGE[plugin-rag-search]=eip-plugin-rag-search
SERVICE_IMAGE[plugin-widgets]=eip-plugin-widgets
SERVICE_IMAGE[plugin-decision-log]=eip-plugin-decision-log
SERVICE_IMAGE[nl-query]=eip-nl-query
SERVICE_IMAGE[briefing-service]=eip-briefing
SERVICE_IMAGE[graph-proxy]=eip-graph-proxy
SERVICE_IMAGE[okg-ingest]=eip-okg-ingest
SERVICE_IMAGE[okg-reasoning]=eip-okg-reasoning
SERVICE_IMAGE[priority-engine]=eip-priority-engine
SERVICE_IMAGE[decision-log]=eip-decision-log
SERVICE_IMAGE[mail-insight]=eip-mail-insight
SERVICE_IMAGE[notification-service]=eip-notification
SERVICE_IMAGE[mobile-bff]=eip-mobile-bff
SERVICE_IMAGE[doc-ingest]=eip-doc-ingest
SERVICE_IMAGE[voice]=eip-voice
SERVICE_IMAGE[data-ingestion]=eip-data-ingestion
SERVICE_IMAGE[agent-orchestrator]=eip-agent-orchestrator
SERVICE_IMAGE[mcp-priority]=eip-mcp-priority
SERVICE_IMAGE[mcp-decision]=eip-mcp-decision
SERVICE_IMAGE[mcp-rag]=eip-mcp-rag
SERVICE_IMAGE[mcp-mail]=eip-mcp-mail
SERVICE_IMAGE[mcp-reasoning]=eip-mcp-reasoning
SERVICE_IMAGE[mcp-graph]=eip-mcp-graph
SERVICE_IMAGE[mcp-ingest]=eip-mcp-ingest
SERVICE_IMAGE[mcp-comms]=eip-mcp-comms
SERVICE_IMAGE[agent-briefing]=eip-agent-briefing
SERVICE_IMAGE[agent-decision]=eip-agent-decision
SERVICE_IMAGE[agent-email-triage]=eip-agent-email-triage
SERVICE_IMAGE[agent-attention]=eip-agent-attention
SERVICE_IMAGE[agent-comms-triage]=eip-agent-comms-triage

# compose service -> "label  port" for the summary.
declare -A ENDPOINT_LABEL ENDPOINT_PORT
add_endpoint() { ENDPOINT_LABEL["$1"]="$2"; ENDPOINT_PORT["$1"]="$3"; }
add_endpoint nl-query             "nl-query"           8082
add_endpoint briefing-service     "briefing"           8084
add_endpoint graph-proxy          "graph-proxy"        8080
add_endpoint okg-ingest           "okg-ingest"         8081
add_endpoint okg-reasoning        "okg-reasoning"      8088
add_endpoint priority-engine      "priority-engine"    8083
add_endpoint decision-log         "decision-log"       8086
add_endpoint doc-ingest           "doc-ingest"         8091
add_endpoint docling              "docling"            5001
add_endpoint voice                "voice"              8099
add_endpoint whisper              "whisper (ASR)"      9000
add_endpoint data-ingestion       "data-ingestion"     8096
add_endpoint mail-insight         "mail-insight"       8092
add_endpoint notification-service "notification"       8087
add_endpoint mobile-bff           "mobile-bff"         8097
add_endpoint mcp-priority         "mcp-priority"       8101
add_endpoint mcp-decision         "mcp-decision"       8102
add_endpoint mcp-rag              "mcp-rag"            8105
add_endpoint mcp-mail             "mcp-mail"           8106
add_endpoint mcp-reasoning        "mcp-reasoning"      8107
add_endpoint mcp-graph            "mcp-graph"          8108
add_endpoint mcp-ingest           "mcp-ingest"         8109
add_endpoint mcp-comms            "mcp-comms"          8112
add_endpoint agent-orchestrator   "agent-orchestrator" 8085
add_endpoint agent-briefing       "agent-briefing"     8103
add_endpoint agent-decision       "agent-decision"     8104
add_endpoint agent-email-triage   "agent-email-triage" 8110
add_endpoint agent-attention      "agent-attention"    8111
add_endpoint agent-comms-triage   "agent-comms-triage" 8113

# ---------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------
contains_word() {
    # $1 = space-separated haystack, $2 = needle
    local w
    for w in $1; do [ "$w" = "$2" ] && return 0; done
    return 1
}

add_unique() {
    # $1 = name of a space-separated string variable (by nameref), $2 = value to add
    local -n ref="$1"
    if ! contains_word "$ref" "$2"; then
        ref="${ref:+$ref }$2"
    fi
}

# eip_env_val / eip_env_bool live in scripts/lib/eip-platform.sh

show_usage() {
    echo "Usage: $SELF <functionality>[,<functionality>...]"
    echo
    echo "Application functionalities:"
    for k in "${FUNC_KEYS[@]}"; do
        [ "${FUNC_GROUP[$k]}" = "app" ] && printf "  %-18s %s\n" "$k" "${FUNC_DESC[$k]}"
    done
    echo
    echo "MCP tool servers (server + the upstream service it wraps):"
    for k in "${FUNC_KEYS[@]}"; do
        [ "${FUNC_GROUP[$k]}" = "mcp" ] && printf "  %-18s %s\n" "$k" "${FUNC_DESC[$k]}"
    done
    echo
    echo "Agents (agent + its MCP tool server(s) + upstream service(s)):"
    for k in "${FUNC_KEYS[@]}"; do
        [ "${FUNC_GROUP[$k]}" = "agent" ] && printf "  %-18s %s\n" "$k" "${FUNC_DESC[$k]}"
    done
    echo
    echo "Aliases / bundles:"
    for k in "${ALIAS_KEYS[@]}"; do
        printf "  %-18s %s\n" "$k" "$(echo "${ALIAS_MEMBERS[$k]}" | tr ' ' ',')"
    done
    echo
    echo "Examples:"
    echo "  $SELF aicopilot"
    echo "  $SELF communication,agent-comms-triage"
    echo "  $SELF core,mcp"
    echo
    echo "For the full platform (every layer + service) or a full --no-cache"
    echo "rebuild, use $FULL_LAUNCHER instead."
}

# ---------------------------------------------------------------
# Machine-readable catalogue (hidden mode). Emits JSON and exits, WITHOUT
# touching Docker - this is what tools/setup-wizard reads to build its catalogue.
# ---------------------------------------------------------------
FUNCTIONALITY_RAW="$(IFS=,; echo "$*")"
rawLower="$(echo "$FUNCTIONALITY_RAW" | tr '[:upper:]' '[:lower:]')"

json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
json_str_array() {
    local out="[" first=1 w
    for w in $1; do
        [ $first -eq 0 ] && out+=","
        out+="\"$(json_escape "$w")\""
        first=0
    done
    out+="]"
    printf '%s' "$out"
}

if [ "$rawLower" = "list-json" ] || [ "$rawLower" = "--list-json" ]; then
    out="{\"base\":{\"backend\":$(json_str_array "${BASE_BACKEND[*]}"),\"ui\":$(json_str_array "${BASE_UI[*]}")},\"functionalities\":["
    first=1
    for k in "${FUNC_KEYS[@]}"; do
        [ $first -eq 0 ] && out+=","
        out+="{\"key\":\"$(json_escape "$k")\",\"group\":\"${FUNC_GROUP[$k]}\",\"description\":\"$(json_escape "${FUNC_DESC[$k]}")\",\"plugins\":$(json_str_array "${FUNC_PLUGINS[$k]}"),\"services\":$(json_str_array "${FUNC_SERVICES[$k]}"),\"mcp\":$(json_str_array "${FUNC_MCP[$k]}"),\"agents\":$(json_str_array "${FUNC_AGENTS[$k]}"),\"ai\":${FUNC_AI[$k]}}"
        first=0
    done
    out+="],\"aliases\":{"
    first=1
    for k in "${ALIAS_KEYS[@]}"; do
        [ $first -eq 0 ] && out+=","
        out+="\"$k\":$(json_str_array "${ALIAS_MEMBERS[$k]}")"
        first=0
    done
    out+="}}"
    echo "$out"
    exit 0
fi

if [ -z "$FUNCTIONALITY_RAW" ] || [[ "$rawLower" =~ ^(-h|--help|help|/\?)$ ]]; then
    show_usage
    exit 0
fi

# ---------------------------------------------------------------
# Resolve requested functionalities (comma- or space-separated union).
# Aliases are expanded to their member functionality keys first.
# ---------------------------------------------------------------
requested=""
IFS=',' read -ra tokens <<< "$rawLower"
for raw_t in "${tokens[@]}"; do
    t="$(echo "$raw_t" | sed -e 's/^ *//' -e 's/ *$//')"
    [ -z "$t" ] && continue
    if [ -n "${ALIAS_MEMBERS[$t]+x}" ]; then
        for member in ${ALIAS_MEMBERS[$t]}; do add_unique requested "$member"; done
    elif [ -n "${FUNC_GROUP[$t]+x}" ]; then
        add_unique requested "$t"
    else
        echo "[ERROR] Unknown functionality '$t'."
        echo
        show_usage
        exit 1
    fi
done

# ---------------------------------------------------------------
# Collect the union of required containers.
# ---------------------------------------------------------------
backend="$(echo "${BASE_BACKEND[*]}")"
plugins=""
needAi="false"
for f in $requested; do
    for svc in ${FUNC_SERVICES[$f]}; do add_unique backend "$svc"; done
    for svc in ${FUNC_MCP[$f]}; do add_unique backend "$svc"; done
    for svc in ${FUNC_AGENTS[$f]}; do add_unique backend "$svc"; done
    for pl in ${FUNC_PLUGINS[$f]}; do add_unique plugins "$pl"; done
    [ "${FUNC_AI[$f]}" = "true" ] && needAi="true"
done
uiList="$(echo "${BASE_UI[*]}")"
for pl in $plugins; do add_unique uiList "$pl"; done

# Mobile-only: the native app uses the host purely as its /api/* reverse proxy and
# never loads the web SPA, so swap the heavy `shell` (Vite build) for the lightweight
# `mobile-proxy` (nginx + the same routing, no build) and drop the web plugin remotes.
# Any union with another functionality (e.g. mobile,aicopilot) keeps the full shell —
# and since mobile-proxy now listens on 3002 (not the shell's 3001) the two coexist,
# so the union also gets the mobile proxy rather than having to choose.
if [ "$requested" = "mobile" ]; then
    uiList="mobile-proxy"
elif contains_word "$requested" "mobile"; then
    add_unique uiList "mobile-proxy"
fi

echo
echo "============================================================"
echo "  Orphos EIP - Functionality Startup"
for f in $requested; do
    echo "    - $f : ${FUNC_DESC[$f]}"
done
echo "============================================================"
echo

# ---------------------------------------------------------------
# Docker check (launches Docker Desktop on Windows/macOS if needed) and
# .env bootstrap - DEMO_MODE below controls the infra layer's optional services.
# ---------------------------------------------------------------
eip_require_docker
eip_ensure_env_file

demoMode="$(eip_env_bool DEMO_MODE true)"
profiles=()
[ "$demoMode" != "true" ] && profiles+=("full")

bundledDb="$(eip_env_bool EIP_BUNDLED_DB true)"
bundledArcade="$(eip_env_bool EIP_BUNDLED_ARCADEDB "$bundledDb")"
bundledPostgres="$(eip_env_bool EIP_BUNDLED_POSTGRES "$bundledDb")"
bundledInfisicalDb="$(eip_env_bool EIP_BUNDLED_INFISICAL_DB "$bundledDb")"
bundledDocling="$(eip_env_bool EIP_BUNDLED_DOCLING true)"
bundledAi="$(eip_env_bool EIP_BUNDLED_AI true)"
[ "$bundledArcade" = "true" ] && profiles+=("bundled-arcade")
[ "$bundledPostgres" = "true" ] && profiles+=("bundled-postgres")
[ "$bundledInfisicalDb" = "true" ] && profiles+=("bundled-infisical-db")
[ "$bundledDocling" = "true" ] && profiles+=("bundled-docling")

# ── Voice ("VoiceEnabled") ──────────────────────────────────────────────────
# Split the voice containers out of the backend list. Everything else in that
# list is started with `up -d`; these two must be CREATED and left stopped, so
# that the tenant toggles in Admin -> Voice decide whether a 3 GB speech model runs
# (platform-core's VoiceModuleReconciler starts them). They are
# handled by their own pass after the normal phases below.
#
# The profile has to be active for compose to see them at all - without it,
# `build voice` and `up --no-start whisper voice` both fail with "no such
# service", since a profiled service is absent from the compose model entirely.
voiceWanted=""
backendNoVoice=""
for svc in $backend; do
    case "$svc" in
        voice|whisper) add_unique voiceWanted "$svc" ;;
        *)             add_unique backendNoVoice "$svc" ;;
    esac
done
backend="$backendNoVoice"
[ -n "$voiceWanted" ] && profiles+=("voice")

# ── Mobile ──────────────────────────────────────────────────────────────────
# mobile-proxy sits behind the "mobile" compose profile so the full-platform start
# never opens port 3002. Like voice, a profiled service is absent from the compose
# model entirely unless its profile is active, so `up mobile-proxy` would fail with
# "no such service" without this. mobile-bff itself is NOT profiled: the web
# dashboard's Tasks & Approvals panel and Travel rail read it too.
if contains_word "$uiList" "mobile-proxy"; then
    profiles+=("mobile")
fi

# EIP_BUNDLED_AI=false -> the Ollama containers live on another machine, so the
# local ai layer must never start even when a selected module declares ai=true.
if [ "$needAi" = "true" ] && [ "$bundledAi" != "true" ]; then
    echo "[AI] EIP_BUNDLED_AI=false - skipping local Ollama layer (running remotely)."
    echo "     Endpoints in use: OLLAMA_URL / OLLAMA_HI_URL / OLLAMA_EMBED_URL from .env"
    needAi="false"
fi

profilesJoined=""
[ "${#profiles[@]}" -gt 0 ] && profilesJoined="$(IFS=,; echo "${profiles[*]}")"
export COMPOSE_PROFILES="$profilesJoined"

# ---------------------------------------------------------------
# Infra layer (required by everything). Started only if not already running.
# ---------------------------------------------------------------
netUp="$(docker network ls -q --filter "name=^eip-net$" 2>/dev/null)"
kcUp="$(docker ps -q --filter "name=^eip-keycloak$" --filter "status=running" 2>/dev/null)"
if [ -z "$netUp" ] || [ -z "$kcUp" ]; then
    echo "[INFRA] Starting infrastructure layer..."
    if ! docker compose -p eip-infra -f docker-compose.infra.yml up -d $EIP_COMPOSE_NO_BUILD --remove-orphans; then
        echo "[ERROR] Infrastructure stack failed. Run: docker compose -p eip-infra -f docker-compose.infra.yml logs"
        exit 1
    fi

    echo "      Waiting for Infisical seed..."
    if ! eip_wait_for_seed eip-infisical-seed 180; then
        exit 1
    fi
    echo "[OK] Infrastructure layer ready."
else
    echo "[OK] Infrastructure layer already running."
fi
echo

# ---------------------------------------------------------------
# Data layer (ArcadeDB, Fuseki, Qdrant) - platform-core needs ArcadeDB
# on every functionality, so this is unconditional too.
# ---------------------------------------------------------------
if [ "$bundledArcade" != "true" ]; then
    echo "[DATA] EIP_BUNDLED_ARCADEDB=false - skipping bundled ArcadeDB + localhost schema-init."
    echo "       Starting the rest of the data layer (Jena Fuseki, Qdrant)..."
    if ! docker compose -p eip-data -f docker-compose.data.yml up -d $EIP_COMPOSE_NO_BUILD --remove-orphans; then
        echo "[ERROR] Data stack failed. Run: docker compose -p eip-data -f docker-compose.data.yml logs"
        exit 1
    fi
    echo "[OK] Data layer ready (external ArcadeDB expected via DB Connections)."
else
    arcadeUp="$(docker ps -q --filter "name=^eip-arcadedb$" --filter "status=running" 2>/dev/null)"
    if [ -z "$arcadeUp" ]; then
        echo "[DATA] Starting data layer (ArcadeDB, Jena Fuseki, Qdrant)..."
        if ! docker compose -p eip-data -f docker-compose.data.yml up -d $EIP_COMPOSE_NO_BUILD --remove-orphans; then
            echo "[ERROR] Data stack failed. Run: docker compose -p eip-data -f docker-compose.data.yml logs"
            exit 1
        fi
        echo "Initialising ArcadeDB schema..."
        bash "$ROOT_DIR/scripts/init-arcadedb-schema.sh" >/dev/null 2>&1 || true
        echo "[OK] Data layer ready."
    else
        echo "[OK] Data layer already running."
    fi
fi
echo

# ---------------------------------------------------------------
# Ollama AI layer - only when a requested functionality needs it
# ---------------------------------------------------------------
if [ "$needAi" = "true" ]; then
    r1="$(docker ps -q -f name='^eip-ollama$' -f status=running 2>/dev/null)"
    r2="$(docker ps -q -f name='^eip-ollama-hi$' -f status=running 2>/dev/null)"
    if [ -z "$r1" ] || [ -z "$r2" ]; then
        echo "[AI] Starting Ollama containers..."
        eip_stop_host_ollama
        docker compose -p eip-ai -f docker-compose.ai.yml up -d $EIP_COMPOSE_NO_BUILD --remove-orphans

        ollamaWait=0
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
    fi

    # qwen2.5:1.5b is pulled onto BOTH Ollama containers: the hi-memory one
    # serves it as the 'inhouse-gemma' provider, and the primary serves it as
    # the default 'inhouse' chat model (OLLAMA_CHAT_MODEL) — the primary must
    # actually hold whatever OLLAMA_CHAT_MODEL names, or the copilot 404s with
    # "model not found".
    declare -a MODEL_CONTAINERS=(eip-ollama eip-ollama eip-ollama eip-ollama-hi eip-ollama-hi)
    declare -a MODEL_NAMES=(granite3.1-dense:2b phi3.5 qwen2.5:1.5b qwen2.5:1.5b bge-m3)
    for i in "${!MODEL_NAMES[@]}"; do
        c="${MODEL_CONTAINERS[$i]}"; m="${MODEL_NAMES[$i]}"
        if ! docker exec "$c" ollama list 2>/dev/null | grep -q "$m"; then
            echo "      Pulling $m into $c..."
            docker exec "$c" ollama pull "$m"
        fi
    done
    echo "[OK] Ollama layer ready."
else
    echo "[AI] Skipped - requested functionality needs no LLM."
fi
echo

# ---------------------------------------------------------------
# Build images that are missing - one at a time (see start.sh for why).
# ---------------------------------------------------------------
# $voiceWanted is included so the voice image is BUILT here even though its
# containers are only created (never started) further down.
allContainers="$backend $uiList $voiceWanted"
toBuild=""
for svc in $allContainers; do
    image="${SERVICE_IMAGE[$svc]:-}"
    [ -z "$image" ] && continue
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        add_unique toBuild "$svc"
    fi
done

if [ -n "$toBuild" ] && [ "$EIP_IS_DISTRIBUTION" = "true" ]; then
    # Client package: there is no source tree to build from, so the missing
    # images come out of the bundled archive instead. Same step orphos-start.ps1
    # performs; the marker file documents the contract.
    echo "[BUILD] Missing images: $(echo "$toBuild" | tr ' ' ',')"
    firstMissing="${toBuild%% *}"
    if ! eip_load_distribution_images "${SERVICE_IMAGE[$firstMissing]:-eip-platform-core}"; then
        exit 1
    fi
    # Re-check: an image still absent after the load means the archive does not
    # match this compose file, and `up --no-build` would fail with a less
    # obvious message.
    stillMissing=""
    for svc in $toBuild; do
        image="${SERVICE_IMAGE[$svc]:-}"
        [ -z "$image" ] && continue
        docker image inspect "$image" >/dev/null 2>&1 || add_unique stillMissing "$svc"
    done
    if [ -n "$stillMissing" ]; then
        echo "[ERROR] Still missing after the load: $(echo "$stillMissing" | tr ' ' ',')"
        echo "        The bundled archive does not match this package's compose files."
        exit 1
    fi
elif [ -n "$toBuild" ]; then
    echo "[BUILD] Missing images: $(echo "$toBuild" | tr ' ' ',')"

    # Every Java service, MCP and agent Dockerfile starts with `COPY target/*.jar`,
    # so Maven runs first. The UI images (shell / plugin-*) are the only ones that
    # need no JAR, so anything else in $toBuild means packaging the reactor
    # (see the equivalent block in start.sh).
    needsJars=""
    for svc in $toBuild; do
        case "$svc" in
            shell|mobile-proxy|plugin-*) ;;
            *) needsJars="yes" ;;
        esac
    done
    if [ -n "$needsJars" ]; then
        echo "      Building service JARs (full Maven reactor)..."
        if ! eip_mvn -B -q -DskipTests package; then
            echo "[ERROR] Maven build failed. Run: mvn -DskipTests package"
            exit 1
        fi
    fi

    for svc in $toBuild; do
        echo "      Building $svc..."
        if ! docker compose -p eip-app -f docker-compose.app.yml build "$svc"; then
            echo "[ERROR] Build failed ($svc)."
            exit 1
        fi
    done
    echo "[OK] Images built."
else
    echo "[OK] All required images already exist (use $FULL_LAUNCHER to rebuild)."
fi
echo

# ---------------------------------------------------------------
# Ensure the DB connections registry volume exists and is seeded.
# ---------------------------------------------------------------
echo "[DATA] Ensuring DB connections registry is seeded..."
if ! docker compose -p eip-infra -f docker-compose.infra.yml up -d connections-seed >/dev/null 2>&1; then
    echo "[WARN] Could not run connections-seed; the app stack may fail if eip-infra_eip_connections is missing."
fi
echo

# ---------------------------------------------------------------
# Phase 1 - backend services (domain services + MCP + agents + base).
# No --remove-orphans: other functionalities' containers must survive.
# ---------------------------------------------------------------
echo "[APP] Starting backend: $(echo "$backend" | tr ' ' ',')"
# shellcheck disable=SC2086
if ! docker compose -p eip-app -f docker-compose.app.yml up -d --no-build $backend; then
    echo "[ERROR] Backend services failed to start. Run: docker compose -p eip-app -f docker-compose.app.yml logs"
    exit 1
fi
echo "[OK] Backend services running."
echo

# ---------------------------------------------------------------
# Phase 2 - UI shell + scoped plugin remotes (--no-deps for plugin scoping).
# ---------------------------------------------------------------
echo "[APP] Starting UI + plugins: $(echo "$uiList" | tr ' ' ',')"
# shellcheck disable=SC2086
if ! docker compose -p eip-app -f docker-compose.app.yml up -d --no-build --no-deps $uiList; then
    echo "[ERROR] UI / plugin containers failed to start. Run: docker compose -p eip-app -f docker-compose.app.yml logs"
    exit 1
fi
echo "[OK] UI + plugin remotes running."
echo

# ---------------------------------------------------------------
# Voice ("VoiceEnabled") - created, deliberately NOT started.
# `up --no-start` leaves the containers in the "created" state so the reconciler
# has something to start; a container that was never created cannot be started
# through the Docker API. No --remove-orphans: this call names only the voice
# services, and compose would otherwise treat every other container as an orphan.
# ---------------------------------------------------------------
if [ -n "$voiceWanted" ]; then
    echo "[VOICE] Preparing voice containers (created, NOT started): $(echo "$voiceWanted" | tr ' ' ',')"
    # shellcheck disable=SC2086
    if docker compose -p eip-app -f docker-compose.app.yml up --no-start --no-build $voiceWanted; then
        echo "[OK] Voice containers are prepared."
        echo "     It starts by itself within ~30s for tenants with voice on in Admin -> Voice."
    else
        echo "[WARN] Could not prepare the voice containers; voice will show as unavailable in Admin -> Voice."
    fi
    echo
fi

# ---------------------------------------------------------------
# Summary
# ---------------------------------------------------------------
docker compose -p eip-app -f docker-compose.app.yml ps

echo
echo "============================================================"
echo "  Functionality '$(echo "$requested" | tr ' ' ',')' is UP."
echo
contains_word "$uiList" "shell" \
    && echo "  Shell (web UI)       http://localhost:3001"
# The mobile proxy has its own port so it can run alongside the web UI; the
# native app's BuildConfig.API_BASE_URL points here.
contains_word "$uiList" "mobile-proxy" \
    && echo "  Mobile API (native)  http://localhost:3002"
echo "  Keycloak Admin       http://localhost:8443"
echo "  platform-core        http://localhost:8090"
echo "  platform-registry    http://localhost:8093"
echo "  admin-console        http://localhost:8094"
echo "  widgets              http://localhost:8095"

printed=""
for svc in $backend; do
    contains_word "${BASE_BACKEND[*]}" "$svc" && continue
    if [ -n "${ENDPOINT_LABEL[$svc]+x}" ] && ! contains_word "$printed" "$svc"; then
        printf "  %-18s http://localhost:%s\n" "${ENDPOINT_LABEL[$svc]}" "${ENDPOINT_PORT[$svc]}"
        add_unique printed "$svc"
    fi
done

echo
echo "  Started plugins : $(echo "$plugins" | tr ' ' ',')"
echo
echo "  NOTE: only the plugins/services above are running. Nav tabs and admin"
echo "  sections for functionalities you did NOT start will not appear, and"
echo "  dashboard widgets backed by down services show empty/error states."
echo "  Start more with e.g.:  $SELF communication"
echo "  Full platform:  $FULL_LAUNCHER"
echo "============================================================"
echo

exit 0
