@echo off
REM ---------------------------------------------------------------------------
REM  Orphos - stop the platform.
REM
REM  Stops the containers and keeps every byte of data. Your databases, uploaded
REM  documents and AI models are all in Docker volumes, which this never removes.
REM  Run start.bat to bring everything back.
REM ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0orphos.ps1" stop
set RC=%ERRORLEVEL%

echo.
pause
exit /b %RC%
