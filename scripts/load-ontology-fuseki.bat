@echo off
title EIP - Load OWL Ontology into Fuseki
color 0B

set FUSEKI=http://localhost:3030
set DATASET=orphos

:: Read the real password from .env instead of hardcoding it - FUSEKI_ADMIN_PASSWORD
:: has no fallback default, and install.ps1 may have generated/prompted a value
:: other than the historical "fuseki123".
set FUSEKI_ADMIN_PASSWORD=fuseki123
for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0..\.env") do (
    if "%%A"=="FUSEKI_ADMIN_PASSWORD" if not "%%B"=="" set FUSEKI_ADMIN_PASSWORD=%%B
)
set AUTH=admin:%FUSEKI_ADMIN_PASSWORD%
set OWL_FILE=%~dp0..\ontology\eip-core.owl
set ENTERPRISE_OWL_FILE=%~dp0..\ontology\eip-enterprise.owl

echo ============================================================
echo   Orphos EIP - Loading OWL Ontology into Fuseki
echo ============================================================
echo.

:: Check Fuseki is running
echo [1/3] Checking Fuseki is reachable...
curl -sf -u %AUTH% %FUSEKI%/$/ping >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Fuseki is not reachable at %FUSEKI%
    echo         Make sure eip-fuseki container is running.
    pause
    exit /b 1
)
echo [OK] Fuseki is reachable.
echo.

:: Create dataset if it does not exist
echo [2/3] Ensuring dataset '%DATASET%' exists...
curl -sf -u %AUTH% ^
    -X POST %FUSEKI%/$/datasets ^
    -H "Content-Type: application/x-www-form-urlencoded" ^
    -d "dbName=%DATASET%&dbType=tdb2" >nul 2>&1
echo [OK] Dataset ready (created or already exists).
echo.

:: Upload the core OWL ontology file
echo [3/4] Uploading core ontology: %OWL_FILE%
curl -sf -u %AUTH% ^
    -X POST "%FUSEKI%/%DATASET%/data" ^
    -H "Content-Type: application/rdf+xml" ^
    --data-binary "@%OWL_FILE%"
if %errorlevel% neq 0 (
    echo [ERROR] Failed to upload core ontology.
    echo         Check Fuseki logs: docker logs eip-fuseki --tail 30
    pause
    exit /b 1
)
echo.
echo [OK] Core ontology uploaded successfully.
echo.

:: Upload the enterprise OWL ontology file (own namespace — unions with core)
echo [4/4] Uploading enterprise ontology: %ENTERPRISE_OWL_FILE%
curl -sf -u %AUTH% ^
    -X POST "%FUSEKI%/%DATASET%/data" ^
    -H "Content-Type: application/rdf+xml" ^
    --data-binary "@%ENTERPRISE_OWL_FILE%"
if %errorlevel% neq 0 (
    echo [ERROR] Failed to upload enterprise ontology.
    echo         Check Fuseki logs: docker logs eip-fuseki --tail 30
    pause
    exit /b 1
)
echo.
echo [OK] Enterprise ontology uploaded successfully.
echo.

:: Verify - count triples
echo Verifying - counting triples in graph...
curl -sf -u %AUTH% ^
    -X POST "%FUSEKI%/%DATASET%/sparql" ^
    -H "Content-Type: application/sparql-query" ^
    -H "Accept: application/sparql-results+json" ^
    -d "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }"
echo.
echo.
echo ============================================================
echo   Ontologies loaded into Fuseki dataset: %DATASET%
echo   Core       : Incident, ITSystem, CostCentre,
echo                SLAMetric, BudgetItem, Alert
echo   Enterprise : Company, OrgUnit, Executive, FunctionalArea,
echo                Branch, Client (reportsTo/subUnitOf transitive)
echo.
echo   Browse at: http://localhost:3030/#/dataset/%DATASET%/query
echo ============================================================
pause
