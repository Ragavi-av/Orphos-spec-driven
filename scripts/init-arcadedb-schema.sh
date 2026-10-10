#!/usr/bin/env bash
# Orphos EIP - Initialise ArcadeDB schema (Linux/macOS port of init-arcadedb-schema.bat)
set -uo pipefail

HOST="http://localhost:2480"
DB="orphos"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Read the real password from .env instead of hardcoding it - ARCADEDB_PASSWORD
# has no fallback default, and install.ps1 may have generated/prompted a value
# other than the historical "arcadedb123".
ARCADEDB_PASSWORD="arcadedb123"
if [ -f "$ROOT_DIR/.env" ]; then
    envVal="$(grep -E '^\s*ARCADEDB_PASSWORD\s*=' "$ROOT_DIR/.env" | tail -1 | cut -d'=' -f2- | tr -d '\r')"
    if [ -n "$envVal" ]; then
        ARCADEDB_PASSWORD="$envVal"
    fi
fi
AUTH="root:${ARCADEDB_PASSWORD}"

echo "============================================================"
echo "  Orphos EIP - Initialising ArcadeDB Schema"
echo "============================================================"
echo

# Wait for ArcadeDB to be ready
echo "Checking ArcadeDB is reachable..."
if ! curl -sf -u "$AUTH" "$HOST/api/v1/ready" >/dev/null 2>&1; then
    echo "[WARN] ArcadeDB not ready yet - waiting 10 seconds..."
    sleep 10
fi

# Create database using server command API (v26+)
echo "Creating database: $DB"
curl -sf -X POST -u "$AUTH" "$HOST/api/v1/server" \
    -H "Content-Type: application/json" \
    -d "{\"command\":\"create database $DB\"}"
echo

# Create Vertex types
echo "Creating Vertex types..."
for V in Incident ITSystem CostCentre SLAMetric BudgetItem Alert Decision Project; do
    echo "  Creating vertex: $V"
    curl -sf -X POST -u "$AUTH" "$HOST/api/v1/command/$DB" \
        -H "Content-Type: application/json" \
        -d "{\"language\":\"sql\",\"command\":\"CREATE VERTEX TYPE $V IF NOT EXISTS\"}"
    echo
done

# Create Edge types
echo "Creating Edge types..."
for E in IMPACTS FUNDS HAS_INCIDENT HAS_SLA_METRIC DEPENDS_ON TRIGGERS; do
    echo "  Creating edge: $E"
    curl -sf -X POST -u "$AUTH" "$HOST/api/v1/command/$DB" \
        -H "Content-Type: application/json" \
        -d "{\"language\":\"sql\",\"command\":\"CREATE EDGE TYPE $E IF NOT EXISTS\"}"
    echo
done

# Create property indexes for fast lookups
echo "Creating indexes..."

curl -sf -X POST -u "$AUTH" "$HOST/api/v1/command/$DB" \
    -H "Content-Type: application/json" \
    -d "{\"language\":\"sql\",\"command\":\"CREATE PROPERTY Incident.id IF NOT EXISTS STRING\"}"

curl -sf -X POST -u "$AUTH" "$HOST/api/v1/command/$DB" \
    -H "Content-Type: application/json" \
    -d "{\"language\":\"sql\",\"command\":\"CREATE INDEX IF NOT EXISTS ON Incident(id) UNIQUE\"}"

curl -sf -X POST -u "$AUTH" "$HOST/api/v1/command/$DB" \
    -H "Content-Type: application/json" \
    -d "{\"language\":\"sql\",\"command\":\"CREATE PROPERTY Alert.id IF NOT EXISTS STRING\"}"

curl -sf -X POST -u "$AUTH" "$HOST/api/v1/command/$DB" \
    -H "Content-Type: application/json" \
    -d "{\"language\":\"sql\",\"command\":\"CREATE INDEX IF NOT EXISTS ON Alert(id) UNIQUE\"}"

echo
echo "============================================================"
echo "  Schema initialised successfully!"
echo "  Database : $DB"
echo "  Vertices : Incident, ITSystem, CostCentre, SLAMetric,"
echo "             BudgetItem, Alert, Decision, Project"
echo "  Edges    : IMPACTS, FUNDS, HAS_INCIDENT, HAS_SLA_METRIC,"
echo "             DEPENDS_ON, TRIGGERS"
echo "  Indexes  : Incident.id (UNIQUE), Alert.id (UNIQUE)"
echo "============================================================"
