@echo off
REM ---------------------------------------------------------------------------
REM  Orphos - restart the platform.
REM
REM  Stops the containers, starts them again, waits for the services to become
REM  healthy and opens the browser. Data is kept throughout.
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0orphos.ps1" restart
set RC=%ERRORLEVEL%

echo.
if not "%RC%"=="0" (
  echo Orphos did not restart cleanly ^(exit code %RC%^).
  echo See documentation\TROUBLESHOOTING.md for the usual causes.
  echo.
)
pause
exit /b %RC%
