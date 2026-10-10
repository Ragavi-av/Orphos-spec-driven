@echo off
:: Orphos EIP - Functionality-scoped startup launcher (Windows)
::
:: Starts only the containers required for one business functionality (plus the
:: shared infra/data layers and, where needed, the Ollama AI layer), so far
:: fewer containers run than with the full start.bat.
::
:: The actual logic lives in startmodules.sh - ONE implementation shared by
:: Windows, Linux and macOS - and this wrapper just locates Git Bash and runs
:: it. On Linux/macOS run the same script directly: ./startmodules.sh <func>
::
:: Functionalities are granular and composable - each starts only the plugins,
:: services, MCP servers and agents it needs:
::   startmodules.bat admin          -> Platform admin console only
::   startmodules.bat core           -> Lean core knowledge-graph & insight services
::   startmodules.bat docingest      -> Document ingestion (doc-ingest + Docling parser)
::   startmodules.bat dataingestion  -> Enterprise data ingestion (sources/mappings/jobs)
::   startmodules.bat aicopilot      -> AI Co-pilot (NL query + RAG search + SPARQL chat)
::   startmodules.bat communication  -> Email + channel comms (mail insight, Slack/Teams, notifications)
::   startmodules.bat mobile         -> Native mobile app backend only (Keycloak + DBs + mobile BFF & its services).
::                                      Opt-in: never started by start.bat; own ports (API proxy 3002, BFF 8097).
::   startmodules.bat voice          -> Voice / speech-to-text (Whisper); created stopped, runs automatically while a tenant has voice on (Admin -> Voice)
::   startmodules.bat mcp-rag        -> a single MCP tool server + its upstream service
::   startmodules.bat agent-email-triage -> a single agent + its MCP tools + upstream
::   startmodules.bat aicopilot,communication -> union of several functionalities
::   startmodules.bat mcp | agents   -> the whole MCP / agent mesh (aliases)
::
:: Run "startmodules.bat help" for the full list.
::
:: Requires Git for Windows (Git Bash). See scripts\run-sh.bat for the
:: EIP_BASH / EIP_NO_PAUSE overrides.

call "%~dp0scripts\run-sh.bat" startmodules.sh %*
exit /b %ERRORLEVEL%
