@REM ----------------------------------------------------------------------------
@REM Maven wrapper for Orphos EIP
@REM Uses MAVEN_HOME/JAVA_HOME if set & valid; otherwise falls back to the
@REM mvn found on PATH. Keeps the project portable across machines.
@REM ----------------------------------------------------------------------------
@echo off
setlocal

@REM --- Prefer an explicit, valid MAVEN_HOME; else use mvn from PATH ---
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    call "%MAVEN_HOME%\bin\mvn.cmd" %*
    exit /b %errorlevel%
)

where mvn >nul 2>&1
if %errorlevel% equ 0 (
    call mvn %*
    exit /b %errorlevel%
)

echo [ERROR] Maven not found. Install Maven and add it to PATH, or set MAVEN_HOME.
exit /b 1
