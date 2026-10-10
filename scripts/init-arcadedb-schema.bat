@echo off
title EIP - ArcadeDB Schema Init
color 0A

set HOST=http://localhost:2480
set DB=orphos

:: Read the real password from .env instead of hardcoding it - ARCADEDB_PASSWORD
:: has no fallback default, and install.ps1 may have generated/prompted a value
:: other than the historical "arcadedb123".
set ARCADEDB_PASSWORD=arcadedb123
for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0..\.env") do (
    if "%%A"=="ARCADEDB_PASSWORD" if not "%%B"=="" set ARCADEDB_PASSWORD=%%B
)
set AUTH=root:%ARCADEDB_PASSWORD%

echo ============================================================
echo   Orphos EIP - Initialising ArcadeDB Schema
echo ============================================================
echo.

:: Wait for ArcadeDB to be ready
echo Checking ArcadeDB is reachable...
curl -sf -u %AUTH% %HOST%/api/v1/ready >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] ArcadeDB not ready yet - waiting 10 seconds...
    timeout /t 10 /nobreak >nul
)

:: Create database using server command API (v26+)
echo Creating database: %DB%
curl -sf -X POST -u %AUTH% %HOST%/api/v1/server ^
    -H "Content-Type: application/json" ^
    -d "{\"command\":\"create database %DB%\"}"
echo.

:: Create Vertex types
echo Creating Vertex types...

for %%V in (Incident ITSystem CostCentre SLAMetric BudgetItem Alert Decision Project) do (
    echo   Creating vertex: %%V
    curl -sf -X POST -u %AUTH% %HOST%/api/v1/command/%DB% ^
        -H "Content-Type: application/json" ^
        -d "{\"language\":\"sql\",\"command\":\"CREATE VERTEX TYPE %%V IF NOT EXISTS\"}"
    echo.
)

:: Create Edge types
echo Creating Edge types...

for %%E in (IMPACTS FUNDS HAS_INCIDENT HAS_SLA_METRIC DEPENDS_ON TRIGGERS) do (
    echo   Creating edge: %%E
    curl -sf -X POST -u %AUTH% %HOST%/api/v1/command/%DB% ^
        -H "Content-Type: application/json" ^
        -d "{\"language\":\"sql\",\"command\":\"CREATE EDGE TYPE %%E IF NOT EXISTS\"}"
    echo.
)

:: Create property indexes for fast lookups
echo Creating indexes...

curl -sf -X POST -u %AUTH% %HOST%/api/v1/command/%DB% ^
    -H "Content-Type: application/json" ^
    -d "{\"language\":\"sql\",\"command\":\"CREATE PROPERTY Incident.id IF NOT EXISTS STRING\"}"

curl -sf -X POST -u %AUTH% %HOST%/api/v1/command/%DB% ^
    -H "Content-Type: application/json" ^
    -d "{\"language\":\"sql\",\"command\":\"CREATE INDEX IF NOT EXISTS ON Incident(id) UNIQUE\"}"

curl -sf -X POST -u %AUTH% %HOST%/api/v1/command/%DB% ^
    -H "Content-Type: application/json" ^
    -d "{\"language\":\"sql\",\"command\":\"CREATE PROPERTY Alert.id IF NOT EXISTS STRING\"}"

curl -sf -X POST -u %AUTH% %HOST%/api/v1/command/%DB% ^
    -H "Content-Type: application/json" ^
    -d "{\"language\":\"sql\",\"command\":\"CREATE INDEX IF NOT EXISTS ON Alert(id) UNIQUE\"}"

echo.
echo ============================================================
echo   Schema initialised successfully!
echo   Database : %DB%
echo   Vertices : Incident, ITSystem, CostCentre, SLAMetric,
echo              BudgetItem, Alert, Decision, Project
echo   Edges    : IMPACTS, FUNDS, HAS_INCIDENT, HAS_SLA_METRIC,
echo              DEPENDS_ON, TRIGGERS
echo   Indexes  : Incident.id (UNIQUE), Alert.id (UNIQUE)
echo ============================================================
