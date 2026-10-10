#!/usr/bin/env bash
# Orphos EIP - Platform Shutdown
#
# The single shutdown implementation for every OS:
#   Linux / macOS : ./stop.sh
#   Windows       : stop.bat  (locates Git Bash and runs this same script)
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"
# shellcheck source=scripts/lib/eip-platform.sh
. "$ROOT_DIR/scripts/lib/eip-platform.sh"

echo
echo "============================================================"
echo "  Orphos EIP - Platform Shutdown"
echo "============================================================"
echo

# ---------------------------------------------------------------
# Docker check. Unlike startup this must NOT try to launch Docker just to
# shut things down - if the engine is down, so is the platform.
# ---------------------------------------------------------------
if ! eip_require_docker --optional; then
    exit 0
fi

# ---------------------------------------------------------------
# Activate all compose profiles so profiled services (postgres via
# "bundled-postgres", jaeger/kafka/etc via "full") are part of the project.
# start.sh sets COMPOSE_PROFILES the same way; without it, compose rejects
# the infra file ("keycloak depends on undefined service postgres") because
# the profiled postgres service is excluded.
# Extra profiles are harmless on 'down' - missing containers are skipped.
# ---------------------------------------------------------------
export COMPOSE_PROFILES="full,bundled-arcade,bundled-postgres,bundled-infisical-db,bundled-docling"

# ---------------------------------------------------------------
# Tear down all stacks (app first, infra last).
# The AI compose down covers BOTH Ollama containers (eip-ollama and
# eip-ollama-hi); their named volumes (eip-ollama-data,
# eip-ollama-hi-data) survive so pulled models are not re-downloaded
# on the next start.
# ---------------------------------------------------------------
echo "Stopping all layers..."
docker compose -p eip-app   -f docker-compose.app.yml   down --remove-orphans
docker compose -p eip-ai    -f docker-compose.ai.yml    down --remove-orphans
docker compose -p eip-data  -f docker-compose.data.yml  down --remove-orphans
docker compose -p eip-infra -f docker-compose.infra.yml down --remove-orphans

# ---------------------------------------------------------------
# Safety net: the 'down' commands above only remove containers
# tracked by their compose project. A container created under a
# different project (or manually) keeps its fixed name (e.g.
# eip-doc-ingest) and survives - later causing "container name is
# already in use" on the next build/start. Force-remove any leftover
# eip-* containers by name so none linger.
# ---------------------------------------------------------------
leftover="$(docker ps -aq --filter "name=eip-" 2>/dev/null)"
if [ -n "$leftover" ]; then
    # shellcheck disable=SC2086
    docker rm -f $leftover >/dev/null 2>&1
fi

# ---------------------------------------------------------------
# Confirm
# ---------------------------------------------------------------
echo
docker compose -p eip-infra -f docker-compose.infra.yml ps
docker compose -p eip-data  -f docker-compose.data.yml  ps
docker compose -p eip-ai    -f docker-compose.ai.yml    ps
docker compose -p eip-app   -f docker-compose.app.yml   ps

echo
echo "============================================================"
echo "  Platform is DOWN."
echo "  Both Ollama containers stopped; pulled models retained in"
echo "  volumes eip-ollama-data and eip-ollama-hi-data."
echo "  Use 'docker volume prune' to wipe data (forces a full"
echo "  model re-pull on the next $(eip_launcher start))."
echo "============================================================"
echo

exit 0
