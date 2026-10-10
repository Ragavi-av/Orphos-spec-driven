@echo off
REM ---------------------------------------------------------------------------
REM  Orphos - start only selected modules.
REM
REM    startmodules.bat admin
REM    startmodules.bat core,aicopilot,docingest
REM    startmodules.bat all
REM
REM  Modules: admin, core, dataingestion, docingest, aicopilot, communication,
REM           mobile, voice   (aliases: email = communication, all = every one)
REM
REM  Run it with no module to see the list. Like start.bat it needs only Docker
REM  Desktop; modules already running are left alone, so you can add more later.
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0orphos.ps1" modules %*
set RC=%ERRORLEVEL%

echo.
if not "%RC%"=="0" (
  echo The modules did not start cleanly ^(exit code %RC%^).
  echo See documentation\TROUBLESHOOTING.md for the usual causes.
  echo.
)
pause
exit /b %RC%
