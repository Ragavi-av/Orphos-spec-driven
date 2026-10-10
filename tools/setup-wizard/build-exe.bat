@echo off
:: Orphos EIP - Setup Wizard executable builder (Windows double-click wrapper)
::
:: Windows never RUNS a .ps1 on double-click - it opens it in an editor - so
:: build-exe.ps1 on its own looked like "just a script". This wrapper is the
:: double-clickable entry point: it runs that same script with the execution
:: policy bypassed for this one process only.
::
:: Output:  tools\setup-wizard\dist\Orphos Setup\Orphos Setup.exe
::          plus a bundled Java runtime - the TARGET machine needs no Java.
::
:: Requires on THIS machine: a JDK 17+ (21 recommended) providing jpackage and
:: jlink, reachable via JAVA_HOME or PATH.
::
::   build-exe.bat              build the jar, then package the .exe
::   build-exe.bat -SkipMaven   package an already-built jar

setlocal

:: Prefer PowerShell 7 (pwsh) when present, fall back to Windows PowerShell.
set "PS=pwsh.exe"
where /q pwsh.exe || set "PS=powershell.exe"

echo.
echo ============================================================
echo   Building the Orphos Setup wizard executable
echo ============================================================
echo.

"%PS%" -ExecutionPolicy Bypass -NoProfile -File "%~dp0build-exe.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (
    echo Done. The executable is at:
    echo     %~dp0dist\Orphos Setup\Orphos Setup.exe
) else (
    echo Build FAILED with exit code %RC%.
    echo Check that a JDK 17+ is installed and JAVA_HOME points at it:
    echo     java -version
)

echo.
pause
exit /b %RC%
