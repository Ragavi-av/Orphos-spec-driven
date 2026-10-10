@echo off
:: Orphos EIP - Startup launcher (Windows)
::
:: Double-click this file (or run it from a Command Prompt) to bring up the
:: platform. The actual logic lives in start.sh - ONE implementation shared by
:: Windows, Linux and macOS - and this wrapper just locates Git Bash and runs
:: it, so there is no PowerShell port to keep in sync any more.
::
:: On Linux/macOS run the same script directly:  ./start.sh [module]
::
:: Optionally pass a single module to start only that part of the platform:
::   start.bat            -> full platform (all layers)
::   start.bat infra      -> infrastructure layer only
::   start.bat data       -> data layer only
::   start.bat ai         -> Ollama AI layer only
::   start.bat app        -> application layer only
::   start.bat <service>  -> one app-layer service (e.g. nl-query, shell,
::                           doc-ingest, plugin-ai-copilot)
:: Run "start.bat help" for the full list.
::
:: Requires Git for Windows (Git Bash). See scripts\run-sh.bat for the
:: EIP_BASH / EIP_NO_PAUSE overrides.

call "%~dp0scripts\run-sh.bat" start.sh %*
exit /b %ERRORLEVEL%
