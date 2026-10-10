#!/bin/sh
# ---------------------------------------------------------------------------
# Infisical bootstrap + dev secret seeder (replaces the old openbao-seed).
#
# On a FRESH stack it:
#   1. bootstraps the Infisical instance (admin user + organization),
#   2. creates the "eip" project,
#   3. creates a Universal-Auth machine identity (eip-app) and a client secret,
#   4. grants that identity admin on the project,
#   5. persists project-id / client-id / client-secret to the shared
#      infisical_svc volume (app services read these read-only),
#   6. seeds the dev KV secrets every EIP service reads at startup.
#
# On a RE-RUN (the svc files already exist) it skips bootstrap/provisioning and
# just re-seeds the secrets idempotently. This mirrors the old OpenBao coupling:
# the infisical DB volume and the infisical_svc volume must be wiped together
# (a `docker compose down -v` does exactly that) to force a clean re-bootstrap.
#
# Secret VALUES come from the developer's local .env — never baked into source.
# ---------------------------------------------------------------------------
set -eu

H="${INFISICAL_ADDR:-http://eip-infisical:8080}"
ADMIN_EMAIL="${INFISICAL_ADMIN_EMAIL:-admin@orphos.local}"
ADMIN_PASSWORD="${INFISICAL_ADMIN_PASSWORD:?INFISICAL_ADMIN_PASSWORD is required}"
ORG_NAME="${INFISICAL_ORG_NAME:-orphos}"
PROJECT_SLUG="${INFISICAL_PROJECT_SLUG:-eip-mvp}"
ENVIRONMENT="${INFISICAL_ENV:-prod}"
SVC=/infisical/svc

# JSON-escape backslashes and double quotes in secret values.
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
# Pull the first "key":"value" out of a JSON blob.
jget() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed 's/.*:"//; s/"$//'; }

echo "[seed] waiting for Infisical at $H ..."
i=0
while [ "$i" -lt 60 ]; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$H/api/status" || true)
  [ "$code" = "200" ] && break
  i=$((i+1)); sleep 3
done
[ "$code" = "200" ] || { echo "[seed] Infisical did not become ready"; exit 1; }

provision() {
  echo "[seed] bootstrapping instance + provisioning project/identity ..."
  BT=$(curl -s -X POST "$H/api/v1/admin/bootstrap" -H "Content-Type: application/json" \
        -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$(esc "$ADMIN_PASSWORD")\",\"organization\":\"$ORG_NAME\"}")
  ADMIN_TOKEN=$(jget "$BT" token)
  ORG_ID=$(printf '%s' "$BT" | grep -o '"organization":{"id":"[^"]*"' | sed 's/.*"id":"//; s/"$//')
  if [ -z "$ADMIN_TOKEN" ] || [ -z "$ORG_ID" ]; then
    echo "[seed] bootstrap failed (instance already bootstrapped but svc files missing?)."
    echo "[seed] response: $BT"
    echo "[seed] wipe the infisical DB + infisical_svc volumes together and retry."
    exit 1
  fi

  PJ=$(curl -s -X POST "$H/api/v2/workspace" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d "{\"projectName\":\"eip\",\"slug\":\"$PROJECT_SLUG\"}")
  PID=$(jget "$PJ" id)

  IDR=$(curl -s -X POST "$H/api/v1/identities" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d "{\"name\":\"eip-app\",\"organizationId\":\"$ORG_ID\",\"role\":\"member\"}")
  IID=$(jget "$IDR" id)

  UA=$(curl -s -X POST "$H/api/v1/auth/universal-auth/identities/$IID" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d '{"accessTokenTTL":7200,"accessTokenMaxTTL":7200,"accessTokenNumUsesLimit":0,"clientSecretTrustedIps":[{"ipAddress":"0.0.0.0/0"},{"ipAddress":"::/0"}],"accessTokenTrustedIps":[{"ipAddress":"0.0.0.0/0"},{"ipAddress":"::/0"}]}')
  CID=$(jget "$UA" clientId)

  CS=$(curl -s -X POST "$H/api/v1/auth/universal-auth/identities/$IID/client-secrets" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d '{"description":"eip-app","numUsesLimit":0,"ttl":0}')
  CSEC=$(jget "$CS" clientSecret)

  curl -s -o /dev/null -X POST "$H/api/v2/workspace/$PID/identity-memberships/$IID" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
        -d '{"role":"admin"}'

  [ -n "$PID" ] && [ -n "$CID" ] && [ -n "$CSEC" ] || { echo "[seed] provisioning incomplete: PID=$PID CID=$CID CSEC_len=${#CSEC}"; exit 1; }

  mkdir -p "$SVC"
  printf '%s' "$PID"  > "$SVC/project-id"
  printf '%s' "$CID"  > "$SVC/client-id"
  printf '%s' "$CSEC" > "$SVC/client-secret"
  echo "[seed] provisioned project=$PID identity client-id=$CID"
}

if [ -f "$SVC/client-secret" ] && [ -f "$SVC/project-id" ] && [ -f "$SVC/client-id" ]; then
  echo "[seed] svc files present — skipping bootstrap, re-seeding only."
  PID=$(cat "$SVC/project-id"); CID=$(cat "$SVC/client-id"); CSEC=$(cat "$SVC/client-secret")
else
  provision
fi

# Log in as the app identity (also validates the runtime auth path).
LOGIN=$(curl -s -X POST "$H/api/v1/auth/universal-auth/login" -H "Content-Type: application/json" \
        -d "{\"clientId\":\"$CID\",\"clientSecret\":\"$CSEC\"}")
AT=$(jget "$LOGIN" accessToken)
[ -n "$AT" ] || { echo "[seed] universal-auth login failed: $LOGIN"; exit 1; }

# Ensure each segment of a folder path exists (idempotent — Infisical de-dupes).
ensure_folder() {
  parent="/"
  oldifs="$IFS"; IFS='/'
  for seg in $1; do
    [ -z "$seg" ] && continue
    curl -s -o /dev/null -X POST "$H/api/v1/folders" -H "Authorization: Bearer $AT" -H "Content-Type: application/json" \
      -d "{\"workspaceId\":\"$PID\",\"environment\":\"$ENVIRONMENT\",\"name\":\"$seg\",\"path\":\"$parent\"}"
    if [ "$parent" = "/" ]; then parent="/$seg"; else parent="$parent/$seg"; fi
  done
  IFS="$oldifs"
}

# Upsert <field>=<value> at folder /<path>. Blank values are skipped.
put() {
  path="$1"; field="$2"; value="$3"
  [ -z "$value" ] && { echo "[seed] skip $path#$field (blank)"; return 0; }
  ensure_folder "$path"
  body="{\"workspaceId\":\"$PID\",\"environment\":\"$ENVIRONMENT\",\"secretPath\":\"/$path\",\"secretValue\":\"$(esc "$value")\"}"
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$H/api/v3/secrets/raw/$field" -H "Authorization: Bearer $AT" -H "Content-Type: application/json" -d "$body")
  if [ "${code%??}" != "2" ]; then
    code=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$H/api/v3/secrets/raw/$field" -H "Authorization: Bearer $AT" -H "Content-Type: application/json" -d "$body")
  fi
  echo "[seed] wrote $path#$field (http $code)"
}

put eip/arcadedb        password       "${ARCADEDB_PASSWORD:-}"
put eip/keycloak-admin  password       "${KEYCLOAK_ADMIN_PASSWORD:-}"
put eip/fuseki-admin    password       "${FUSEKI_ADMIN_PASSWORD:-}"
put eip/smtp            password       "${SMTP_PASSWORD:-}"
put eip/gemini          api_key        "${GEMINI_API_KEY:-}"
put eip/anthropic       api_key        "${ANTHROPIC_API_KEY:-}"
put eip/azure           client_secret  "${AZURE_CLIENT_SECRET:-}"
put eip/internal-service token         "${EIP_INTERNAL_SERVICE_TOKEN:-}"

echo "[seed] infisical-seed done"
