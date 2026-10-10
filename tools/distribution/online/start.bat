@echo off
REM ---------------------------------------------------------------------------
REM  Orphos - start the platform.
REM
REM  Double-click this file. It needs Docker Desktop and nothing else: no Java,
REM  no Maven, no Node, no source code. Everything else is in this folder.
REM
REM  Safe to run as many times as you like. Images already loaded are not
REM  reloaded, containers that already exist are reused, and your data is never
REM  touched.
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

REM The command is passed positionally so it binds to the script's first
REM parameter - "-Command" is also a powershell.exe switch and would be ambiguous.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0orphos.ps1" start
set RC=%ERRORLEVEL%

echo.
if not "%RC%"=="0" (
  echo Orphos did not start cleanly ^(exit code %RC%^).
  echo See documentation\TROUBLESHOOTING.md for the usual causes.
  echo.
)
pause
exit /b %RC%
