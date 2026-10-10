#!/bin/sh
# ---------------------------------------------------------------------------
# secrets-ready boot gate
#
# One-shot job that eliminates the secret-seeding startup race. It logs in to
# Infisical with the eip-app machine identity (the same project-id / client-id /
# client-secret files infisical-seed wrote to the shared infisical_svc volume)
# and verifies that every REQUIRED secret resolves NON-BLANK before the app layer
# is allowed to start.
#
# platform-core depends_on this with condition: service_completed_successfully,
# and because every other app service depends_on platform-core: service_healthy,
# the whole app layer transitively waits for this gate. This replaces start.sh's
# sleep-and-poll with a real dependency edge, so a service can never boot with a
# blank secret and cache the failure (the tenant-creation 401 class of bug).
#
# Exit 0 = all required secrets present. Exit 1 = timed out with at least one
# missing/blank, printed by name so a misconfigured .env is obvious immediately.
# ---------------------------------------------------------------------------
set -eu

H="${INFISICAL_HOST:-http://eip-infisical:8080}"
SVC=/run/infisical
ENVIRONMENT="${INFISICAL_ENV:-prod}"
TIMEOUT="${GATE_TIMEOUT_SECONDS:-180}"
REQ="${REQUIRED_SECRETS:-}"

# Pull the first "key":"value" out of a JSON blob.
jget() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed 's/.*:"//; s/"$//'; }

AT=""

# Log in with the machine identity; sets AT (access token) on success.
login() {
  LOGIN=$(curl -s -X POST "$H/api/v1/auth/universal-auth/login" \
            -H "Content-Type: application/json" \
            -d "{\"clientId\":\"$CID\",\"clientSecret\":\"$CSEC\"}" || true)
  AT=$(jget "$LOGIN" accessToken)
  [ -n "$AT" ]
}

# check "path#field" — true when the secret resolves to a non-blank value.
check() {
  path="${1%#*}"; field="${1##*#}"
  url="$H/api/v3/secrets/raw/$field?workspaceId=$PID&environment=$ENVIRONMENT&secretPath=/$path"
  RESP=$(curl -s -H "Authorization: Bearer $AT" "$url" || true)
  val=$(jget "$RESP" secretValue)
  [ -n "$val" ]
}

echo "[secrets-ready] gate starting — required: ${REQ:-<none>} (timeout ${TIMEOUT}s)"
[ -z "$REQ" ] && { echo "[secrets-ready] no REQUIRED_SECRETS configured — nothing to gate on"; exit 0; }

elapsed=0
while :; do
  CID=$(cat "$SVC/client-id" 2>/dev/null || true)
  CSEC=$(cat "$SVC/client-secret" 2>/dev/null || true)
  PID=$(cat "$SVC/project-id" 2>/dev/null || true)

  if [ -n "$CID" ] && [ -n "$CSEC" ] && [ -n "$PID" ] && login; then
    missing=""
    oldifs=$IFS; IFS=','
    for s in $REQ; do
      [ -z "$s" ] && continue
      check "$s" || missing="$missing $s"
    done
    IFS=$oldifs
    if [ -z "$missing" ]; then
      echo "[secrets-ready] OK — all required secrets present."
      exit 0
    fi
    echo "[secrets-ready] waiting — missing/blank:$missing"
  else
    echo "[secrets-ready] waiting for Infisical + machine identity ..."
  fi

  elapsed=$((elapsed + 5))
  if [ "$elapsed" -ge "$TIMEOUT" ]; then
    echo "[secrets-ready] TIMEOUT after ${TIMEOUT}s — required secrets are not all present."
    echo "[secrets-ready] Fill the blank value(s) in .env, then re-seed:"
    echo "[secrets-ready]   docker compose -p eip-infra -f docker-compose.infra.yml up -d --force-recreate infisical-seed"
    exit 1
  fi
  sleep 5
done
