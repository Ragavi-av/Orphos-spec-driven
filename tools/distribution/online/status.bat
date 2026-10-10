@echo off
REM ---------------------------------------------------------------------------
REM  Orphos - show what is running.
REM
REM  Read-only: it starts and stops nothing.
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0orphos.ps1" status
set RC=%ERRORLEVEL%

echo.
pause
exit /b %RC%
