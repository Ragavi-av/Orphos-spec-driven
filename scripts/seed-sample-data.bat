@echo off
title EIP - Seed Sample Data into ArcadeDB
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
echo   Orphos EIP - Seeding Sample Data
echo ============================================================
echo.

:: ??? Helper macro ???????????????????????????????????????????
:: Usage: call :RUN "SQL statement"
goto :MAIN

:RUN
curl -sf -X POST -u %AUTH% %HOST%/api/v1/command/%DB% ^
    -H "Content-Type: application/json" ^
    -d "{\"language\":\"sql\",\"command\":\"%~1\"}" >nul
if %errorlevel% neq 0 (
    echo [WARN] Command may have failed - continuing...
)
exit /b 0

:MAIN

:: ??? IT Systems ?????????????????????????????????????????????
echo [1/6] Creating IT Systems...

call :RUN "INSERT INTO ITSystem SET id='sys-001', name='SAP ERP', status='DEGRADED', environment='production', owner='IT Operations', lastChecked='2026-05-07T08:00:00'"
call :RUN "INSERT INTO ITSystem SET id='sys-002', name='Salesforce CRM', status='HEALTHY', environment='production', owner='Sales IT', lastChecked='2026-05-07T08:00:00'"
call :RUN "INSERT INTO ITSystem SET id='sys-003', name='AWS Infrastructure', status='CRITICAL', environment='production', owner='Cloud Team', lastChecked='2026-05-07T07:45:00'"
call :RUN "INSERT INTO ITSystem SET id='sys-004', name='Oracle Financials', status='HEALTHY', environment='production', owner='Finance IT', lastChecked='2026-05-07T08:00:00'"

echo [OK] IT Systems created.

:: ??? Incidents ??????????????????????????????????????????????
echo [2/6] Creating Incidents...

call :RUN "INSERT INTO Incident SET id='inc-001', title='SAP ERP Response Time Degradation', severity='HIGH', status='OPEN', domain='IT', affectedSystem='sys-001', createdAt='2026-05-07T06:30:00', estimatedResolution='2026-05-07T14:00:00', description='SAP ERP response times exceeding 8s SLA threshold. Affecting 240 users.'"
call :RUN "INSERT INTO Incident SET id='inc-002', title='AWS us-east-1 Availability Zone Partial Outage', severity='CRITICAL', status='IN_PROGRESS', domain='IT', affectedSystem='sys-003', createdAt='2026-05-07T05:15:00', estimatedResolution='2026-05-07T12:00:00', description='AZ-1 experiencing intermittent failures. DR failover initiated.'"
call :RUN "INSERT INTO Incident SET id='inc-003', title='Salesforce API Rate Limit Breach', severity='MEDIUM', status='RESOLVED', domain='IT', affectedSystem='sys-002', createdAt='2026-05-06T14:00:00', estimatedResolution='2026-05-06T16:00:00', description='Third-party integration exceeding API limits. Throttling applied.'"

echo [OK] Incidents created.

:: ??? SLA Metrics ????????????????????????????????????????????
echo [3/6] Creating SLA Metrics...

call :RUN "INSERT INTO SLAMetric SET id='sla-001', system='sys-001', metricName='Response Time', target=2.0, actual=8.3, unit='seconds', status='BREACHED', measuredAt='2026-05-07T08:00:00'"
call :RUN "INSERT INTO SLAMetric SET id='sla-002', system='sys-002', metricName='Uptime', target=99.9, actual=99.95, unit='percent', status='MET', measuredAt='2026-05-07T08:00:00'"
call :RUN "INSERT INTO SLAMetric SET id='sla-003', system='sys-003', metricName='Availability', target=99.99, actual=98.7, unit='percent', status='BREACHED', measuredAt='2026-05-07T07:45:00'"
call :RUN "INSERT INTO SLAMetric SET id='sla-004', system='sys-004', metricName='Transaction Throughput', target=1000.0, actual=1050.0, unit='tps', status='MET', measuredAt='2026-05-07T08:00:00'"

echo [OK] SLA Metrics created.

:: ??? Cost Centres ???????????????????????????????????????????
echo [4/6] Creating Cost Centres...

call :RUN "INSERT INTO CostCentre SET id='cc-001', name='IT Operations', budget=5000000.0, spent=4200000.0, currency='USD', period='FY2026', owner='CTO', variance=-300000.0"
call :RUN "INSERT INTO CostCentre SET id='cc-002', name='Cloud Infrastructure', budget=2000000.0, spent=2450000.0, currency='USD', period='FY2026', owner='VP Engineering', variance=-450000.0"
call :RUN "INSERT INTO CostCentre SET id='cc-003', name='Cybersecurity', budget=1500000.0, spent=1100000.0, currency='USD', period='FY2026', owner='CISO', variance=400000.0"

echo [OK] Cost Centres created.

:: ??? Budget Items ????????????????????????????????????????????
echo [5/6] Creating Budget Items...

call :RUN "INSERT INTO BudgetItem SET id='bud-001', title='AWS Reserved Instances Renewal', amount=800000.0, currency='USD', status='PENDING_APPROVAL', dueDate='2026-05-15', costCentre='cc-002', requestedBy='VP Engineering'"
call :RUN "INSERT INTO BudgetItem SET id='bud-002', title='SAP License Upgrade', amount=350000.0, currency='USD', status='APPROVED', dueDate='2026-06-01', costCentre='cc-001', requestedBy='CTO'"
call :RUN "INSERT INTO BudgetItem SET id='bud-003', title='Security Audit Q2', amount=120000.0, currency='USD', status='IN_REVIEW', dueDate='2026-05-20', costCentre='cc-003', requestedBy='CISO'"

echo [OK] Budget Items created.

:: ??? Alerts ??????????????????????????????????????????????????
echo [6/8] Creating Alerts...

call :RUN "INSERT INTO Alert SET id='alr-001', title='Cloud Spend 22%% Over Budget', severity='CRITICAL', domain='FINANCE', status='ACTIVE', message='Cloud infrastructure spend has exceeded FY2026 budget by USD 450K. Immediate review required.', createdAt='2026-05-07T07:00:00', source='BudgetMonitor'"
call :RUN "INSERT INTO Alert SET id='alr-002', title='2 SLAs Breached This Morning', severity='HIGH', domain='IT', status='ACTIVE', message='SAP ERP response time at 8.3s (target 2s). AWS availability at 98.7%% (target 99.99%%).', createdAt='2026-05-07T08:05:00', source='SLAMonitor'"
call :RUN "INSERT INTO Alert SET id='alr-003', title='AWS Budget Item Needs CEO Approval', severity='MEDIUM', domain='FINANCE', status='ACTIVE', message='USD 800K AWS Reserved Instances renewal requires executive sign-off by May 15.', createdAt='2026-05-07T06:00:00', source='BudgetWorkflow'"

echo [OK] Alerts created.

:: ??? Decisions ???????????????????????????????????????????????
echo [7/8] Creating Decision Log...

call :RUN "INSERT INTO Decision SET id='dec-001', title='Approved SAP Emergency Patch Deployment', status='APPROVED', domain='IT', rationale='SAP ERP SLA breach requires immediate patch. Risk of continued degradation outweighs deployment risk.', decidedBy='CTO', decidedAt='2026-05-07T09:00:00', linkedIncident='inc-001'"
call :RUN "INSERT INTO Decision SET id='dec-002', title='Initiated AWS DR Failover to us-west-2', status='APPROVED', domain='IT', rationale='AZ-1 partial outage exceeding 2hr threshold. DR SLA requires failover initiation.', decidedBy='VP Engineering', decidedAt='2026-05-07T07:15:00', linkedIncident='inc-002'"
call :RUN "INSERT INTO Decision SET id='dec-003', title='AWS Reserved Instances Renewal - Pending CEO', status='PENDING', domain='FINANCE', rationale='Annual renewal for cost optimisation. 35%% saving vs on-demand. Requires CEO approval above USD 500K threshold.', decidedBy='VP Engineering', decidedAt='2026-05-07T06:00:00', linkedBudget='bud-001'"

echo [OK] Decisions created.

:: ??? Edges ???????????????????????????????????????????????????
echo [8/8] Creating relationships (edges)...

:: HAS_INCIDENT: ITSystem ? Incident
call :RUN "CREATE EDGE HAS_INCIDENT FROM (SELECT FROM ITSystem WHERE id='sys-001') TO (SELECT FROM Incident WHERE id='inc-001')"
call :RUN "CREATE EDGE HAS_INCIDENT FROM (SELECT FROM ITSystem WHERE id='sys-003') TO (SELECT FROM Incident WHERE id='inc-002')"
call :RUN "CREATE EDGE HAS_INCIDENT FROM (SELECT FROM ITSystem WHERE id='sys-002') TO (SELECT FROM Incident WHERE id='inc-003')"

:: HAS_SLA_METRIC: ITSystem ? SLAMetric
call :RUN "CREATE EDGE HAS_SLA_METRIC FROM (SELECT FROM ITSystem WHERE id='sys-001') TO (SELECT FROM SLAMetric WHERE id='sla-001')"
call :RUN "CREATE EDGE HAS_SLA_METRIC FROM (SELECT FROM ITSystem WHERE id='sys-002') TO (SELECT FROM SLAMetric WHERE id='sla-002')"
call :RUN "CREATE EDGE HAS_SLA_METRIC FROM (SELECT FROM ITSystem WHERE id='sys-003') TO (SELECT FROM SLAMetric WHERE id='sla-003')"

:: FUNDS: CostCentre ? ITSystem
call :RUN "CREATE EDGE FUNDS FROM (SELECT FROM CostCentre WHERE id='cc-001') TO (SELECT FROM ITSystem WHERE id='sys-001')"
call :RUN "CREATE EDGE FUNDS FROM (SELECT FROM CostCentre WHERE id='cc-002') TO (SELECT FROM ITSystem WHERE id='sys-003')"

:: IMPACTS: CostCentre ? Incident
call :RUN "CREATE EDGE IMPACTS FROM (SELECT FROM CostCentre WHERE id='cc-002') TO (SELECT FROM Incident WHERE id='inc-002')"
call :RUN "CREATE EDGE IMPACTS FROM (SELECT FROM CostCentre WHERE id='cc-001') TO (SELECT FROM Incident WHERE id='inc-001')"

:: TRIGGERS: Alert ? Incident
call :RUN "CREATE EDGE TRIGGERS FROM (SELECT FROM Alert WHERE id='alr-002') TO (SELECT FROM Incident WHERE id='inc-001')"
call :RUN "CREATE EDGE TRIGGERS FROM (SELECT FROM Alert WHERE id='alr-002') TO (SELECT FROM Incident WHERE id='inc-002')"

echo [OK] Edges created.

echo.
echo ============================================================
echo   Sample data seeded successfully!
echo.
echo   IT Systems  : 4  (SAP ERP, Salesforce, AWS, Oracle)
echo   Incidents   : 3  (1 CRITICAL, 1 HIGH, 1 RESOLVED)
echo   SLA Metrics : 4  (2 BREACHED, 2 MET)
echo   Cost Centres: 3
echo   Budget Items: 3  (1 needs CEO approval)
echo   Alerts      : 3  (1 CRITICAL, 1 HIGH, 1 MEDIUM)
echo   Decisions   : 3  (2 APPROVED, 1 PENDING)
echo.
echo   View at: http://localhost:2480
echo   Dashboard : http://localhost:3001
echo ============================================================
