@echo off
:: Orphos EIP - Windows bridge to the shell launchers.
::
:: The platform launchers (start.sh / stop.sh / startmodules.sh) are the single
:: implementation on every OS. start.bat, stop.bat and startmodules.bat are thin
:: wrappers that call this file, which locates a bash interpreter and runs the
:: requested script from the repo root.
::
:: Usage (from a wrapper):  call "%~dp0scripts\run-sh.bat" start.sh %*
::
:: Environment overrides:
::   EIP_BASH      full path to a bash.exe to use instead of the auto-detected one
::   EIP_NO_PAUSE  set to any value to skip the "press a key" pause on failure
::                 (the setup wizard sets this - it captures output non-interactively)

setlocal EnableDelayedExpansion

:: Repo root = parent of this scripts\ directory. Resolve it BEFORE any shift:
:: a bare `shift` also shifts %0, after which %~dp0 no longer points here.
for %%I in ("%~dp0..") do set "ROOT=%%~fI"

:: First argument is the .sh to run; everything after it is forwarded verbatim.
:: `shift /1` leaves %0 alone and only moves the argument list.
set "SCRIPT=%~1"
if "%SCRIPT%"=="" (
    echo [ERROR] run-sh.bat: no script name given.
    exit /b 2
)
shift /1

set "ARGS="
:collect_args
if "%~1"=="" goto args_collected
set "ARGS=!ARGS! %1"
shift /1
goto collect_args
:args_collected

if not exist "%ROOT%\%SCRIPT%" (
    echo [ERROR] %SCRIPT% not found in %ROOT%.
    echo         Run this from a complete checkout of the repository.
    call :maybe_pause
    exit /b 1
)

call :find_bash
if not defined BASH (
    echo.
    echo [ERROR] No bash interpreter found. The Orphos EIP launchers are shell
    echo         scripts, shared by Windows, Linux and macOS.
    echo.
    echo         A client package bundles one at tools\bash\usr\bin\bash.exe -
    echo         if that is missing, re-extract the package. Otherwise install
    echo         Git for Windows ^(it ships Git Bash^):
    echo             https://git-scm.com/download/win
    echo         winget install --id Git.Git -e
    echo.
    echo         Already have bash somewhere else? Point the launcher at it:
    echo             set EIP_BASH=C:\path\to\bash.exe
    echo.
    echo         Or run the script yourself from WSL / Git Bash:
    echo             ./%SCRIPT%
    echo.
    call :maybe_pause
    exit /b 1
)

pushd "%ROOT%"
"%BASH%" "./%SCRIPT%"!ARGS!
set "EC=%ERRORLEVEL%"
popd

if not "%EC%"=="0" (
    echo.
    echo [ERROR] %SCRIPT% exited with code %EC%. Review the output above.
    call :maybe_pause
)
exit /b %EC%

:: ---------------------------------------------------------------
:: Locate bash.exe. Preference order:
::   1. EIP_BASH override
::   2. the bash bundled in a client package under tools\bash - checked FIRST so
::      a client needs no Git install. Note it is usr\bin\bash.exe: the
::      bin\bash.exe alongside it is an MSYS launcher stub that fails with
::      "Top-level not found".
::   3. Git for Windows, in its usual install locations
::   4. PATH - skipping the bash.exe in System32, which is the WSL launcher and
::      runs in a different filesystem namespace (and often a different Docker
::      context) than the directory we resolved.
:: ---------------------------------------------------------------
:find_bash
set "BASH="
if defined EIP_BASH if exist "%EIP_BASH%" (
    set "BASH=%EIP_BASH%"
    goto :eof
)
call :try_bash "%ROOT%\tools\bash\usr\bin\bash.exe"
if defined BASH (
    :: The bundled binary is raw bash, not Git's launcher wrapper, so it does
    :: NOT put its own /usr/bin on PATH. Started from cmd.exe that leaves uname,
    :: grep, sed and tr missing and the launchers die on the first line. Put the
    :: package's own toolset in front for this process only (we are inside
    :: setlocal, so nothing leaks back to the caller).
    set "PATH=%ROOT%\tools\bash\usr\bin;%PATH%"
    goto :eof
)
call :try_bash "%ProgramFiles%\Git\bin\bash.exe"
if defined BASH goto :eof
call :try_bash "%ProgramW6432%\Git\bin\bash.exe"
if defined BASH goto :eof
call :try_bash "%ProgramFiles(x86)%\Git\bin\bash.exe"
if defined BASH goto :eof
call :try_bash "%LocalAppData%\Programs\Git\bin\bash.exe"
if defined BASH goto :eof
for /f "delims=" %%B in ('where bash.exe 2^>nul') do (
    if not defined BASH (
        echo %%B | find /i "\System32\" >nul
        if errorlevel 1 set "BASH=%%B"
    )
)
goto :eof

:try_bash
if exist "%~1" set "BASH=%~1"
goto :eof

:maybe_pause
if not defined EIP_NO_PAUSE pause
goto :eof
