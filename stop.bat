@echo off
:: Orphos EIP - Shutdown launcher (Windows)
::
:: Tears down every layer (app -> ai -> data -> infra). The actual logic lives
:: in stop.sh - ONE implementation shared by Windows, Linux and macOS - and this
:: wrapper just locates Git Bash and runs it, so the Windows and Linux shutdown
:: paths can no longer drift apart.
::
:: On Linux/macOS run the same script directly:  ./stop.sh
::
:: Named volumes are kept, so data (and the pulled Ollama models) survive a
:: stop/start cycle. Use "docker volume prune" to wipe them.
::
:: Requires Git for Windows (Git Bash). See scripts\run-sh.bat for the
:: EIP_BASH / EIP_NO_PAUSE overrides.

call "%~dp0scripts\run-sh.bat" stop.sh %*
exit /b %ERRORLEVEL%
