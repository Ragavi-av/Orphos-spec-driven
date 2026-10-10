@echo off
setlocal EnableDelayedExpansion
REM ============================================================================
REM  Orphos EIP - grouped build helper
REM
REM  Builds the service jars for a broad classification, then rebuilds the
REM  matching docker images. It does NOT start / restart containers - bringing
REM  the stack up (and the infra/data/ai/infisical-seed/schema steps) is owned by
REM  start.bat. Run build.bat to refresh jars + images, then start.bat to run.
REM
REM  Usage:
REM     build.bat [group ...] [-nodocker] [info]
REM
REM  Groups fall into two families:
REM
REM   A) Functionality groups - same names/slices as startmodules.bat, so you can
REM      "build.bat <name>" then "startmodules.bat <name>". Shared upstreams
REM      (nl-query, priority-engine, ...) are built by their own groups or 'all'.
REM     aicopilot           nl-query, briefing
REM     email               mail-insight, notification, nl-query
REM     admin               platform-core, admin-console, widgets
REM     docingest           doc-ingest                       (alias: docs)
REM     ragsearch           nl-query, doc-ingest, graph-proxy
REM     decisions           decision-log + its UI remote     (alias: decision-log)
REM     dataingestion       data-ingestion                   (alias: data-ingestion)
REM     agents              full catalog mesh: 8 MCP servers + 5 agents   (alias: mcp)
REM     agent-briefing        agent-briefing + mcp-priority
REM     agent-decision        agent-decision + mcp-decision
REM     agent-email-triage    agent-email-triage + mcp-mail
REM     agent-comms-triage    agent-comms-triage + mcp-comms
REM     agent-attention       agent-attention + mcp-priority + mcp-reasoning
REM     mcp-priority | mcp-decision | mcp-rag | mcp-mail | mcp-comms |
REM       mcp-reasoning | mcp-graph | mcp-ingest    one MCP tool server each
REM
REM   B) Build-only groups (technical bundles, no startmodules equivalent):
REM     ai      nl-query, agent-orchestrator
REM     data    okg-ingest, okg-reasoning, graph-proxy, priority-engine,
REM             briefing, decision-log, notification
REM     mail    mail-insight only (soft-depends on priority-engine's EPQ queue;
REM             build 'data' too if you want both current)
REM     admin-console       admin-console-service only (branding, email, query-history)
REM     platform-core       platform-core-service only (tenant lifecycle, auth, SSO)
REM     platform-registry   platform-registry-service (plugin manifest & tenant features)
REM     ui      shell + the 6 plugin remotes (Module Federation; npm build inside Docker)
REM     libs    eip-common, eip-secrets (shared libraries)
REM     mobile  mobile-bff-service + mobile-proxy (native mobile app backend).
REM             OPT-IN: excluded from 'all' unless you also pass 'mobile'. Its
REM             compose services sit behind the "mobile" profile and use their
REM             own ports (API proxy 3002, BFF 8097) - never the web UI's 3001 -
REM             and only startmodules.bat mobile (or the setup wizard's web UI)
REM             starts them; start.bat never does.
REM
REM     all     full clean package + every app image  (DEFAULT when no group is
REM             given). Excludes the mobile app + BFF - add 'mobile' to include them.
REM
REM  Options:
REM     -nodocker   Build jars only; skip the docker image rebuild.
REM     info        Verbose mode - print details of each step and the exact
REM                 command being executed.
REM
REM  Examples:
REM     build.bat                 (same as 'build.bat all'; no mobile)
REM     build.bat mail
REM     build.bat ai data
REM     build.bat data -nodocker
REM     build.bat all info
REM     build.bat mobile          (just the mobile app + BFF)
REM     build.bat all mobile      (full build including the mobile app + BFF)
REM
REM  Model mode is read from .env before building:
REM     OLLAMA_MODEL_MODE=SINGLE    (default) only Gemma is used/pulled.
REM     OLLAMA_MODEL_MODE=MULTIPLE  also pulls models in OLLAMA_EXTRA_MODELS
REM     OLLAMA_EXTRA_MODELS=phi,granite,qwen  (any combination)
REM
REM  Note: service groups use Maven -am, so the shared libs (eip-common /
REM  eip-secrets) are rebuilt automatically when a dependent service needs them.
REM ============================================================================

cd /d "%~dp0"

REM ── Read model config and DEMO_MODE from .env ────────────────────────────────
set "OLLAMA_MODEL_MODE=SINGLE"
set "OLLAMA_EXTRA_MODELS="
set "DEMO_MODE=true"
if exist ".env" (
  for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b "OLLAMA_MODEL_MODE OLLAMA_EXTRA_MODELS DEMO_MODE" .env`) do (
    if "%%A"=="OLLAMA_MODEL_MODE"   set "OLLAMA_MODEL_MODE=%%B"
    if "%%A"=="OLLAMA_EXTRA_MODELS" set "OLLAMA_EXTRA_MODELS=%%B"
    if "%%A"=="DEMO_MODE"           set "DEMO_MODE=%%B"
  )
)
REM Normalise: uppercase mode, strip whitespace
for /f "tokens=* delims= " %%X in ("!OLLAMA_MODEL_MODE!") do set "OLLAMA_MODEL_MODE=%%X"

REM Validate mode value
if /i not "!OLLAMA_MODEL_MODE!"=="SINGLE" if /i not "!OLLAMA_MODEL_MODE!"=="MULTIPLE" (
  echo.
  echo  [WARN] OLLAMA_MODEL_MODE '!OLLAMA_MODEL_MODE!' is not recognised. Defaulting to SINGLE.
  set "OLLAMA_MODEL_MODE=SINGLE"
)

REM ── Display model configuration banner ───────────────────────────────────────
echo.
echo  ============================================================
echo   Orphos EIP Build  -  In-house Model Configuration
echo  ============================================================
if /i "!OLLAMA_MODEL_MODE!"=="SINGLE" (
  echo   Mode   : SINGLE
  echo   Models : Gemma 3 4B  [gemma3:4b]  ^(default, primary Ollama^)
  echo.
  echo   To add more models, set in .env:
  echo     OLLAMA_MODEL_MODE=MULTIPLE
  echo     OLLAMA_EXTRA_MODELS=phi,granite,qwen   ^(any combination^)
) else (
  echo   Mode   : MULTIPLE
  echo   Models : Gemma 3 4B  [gemma3:4b]  ^(always included, primary Ollama^)
  set "NEED_HI=0"
  REM Show each selected extra model
  for %%M in (!OLLAMA_EXTRA_MODELS!) do (
    set "MV=%%M"
    for /f "tokens=* delims= " %%X in ("!MV!") do set "MV=%%X"
    if /i "!MV!"=="phi"     echo           + Phi-3.5 Mini  [phi3.5]          ~2.2 GB  primary Ollama
    if /i "!MV!"=="granite" echo           + Granite 3.1 Dense 2B  [granite3.1-dense:2b]  ~1.6 GB  primary Ollama
    if /i "!MV!"=="qwen"  ( echo           + Qwen 2.5 1.5B  [qwen2.5:1.5b]   ~1.0 GB  hi-mem Ollama & set "NEED_HI=1" )
  )
  if "!NEED_HI!"=="1" (
    echo.
    echo   Note: Qwen requires the eip-ollama-hi container ^(start.bat ai^).
  )
  if "!OLLAMA_EXTRA_MODELS!"=="" (
    echo   [WARN] OLLAMA_MODEL_MODE=MULTIPLE but OLLAMA_EXTRA_MODELS is empty.
    echo          Only Gemma will be pulled. Add phi, granite, or qwen to OLLAMA_EXTRA_MODELS.
  )
)
echo  ============================================================
echo   Run install-models.bat after 'start.bat ai' to pull models.
echo  ============================================================
echo.
echo  ============================================================
echo   Demo Mode
echo  ============================================================
if /i "!DEMO_MODE!"=="true" (
  echo   DEMO_MODE : true  ^(default^)
  echo   Skipped   : Kafka, Fluent-bit, Jaeger, VictoriaLogs, Prometheus, Perses
  echo   To start the full stack set DEMO_MODE=false in .env
) else (
  echo   DEMO_MODE : false  -- full stack
)
echo  ============================================================
echo.

set "MODULES="
set "SERVICES="
set "DOCKER=1"
set "DOALL=0"
set "UI=0"
set "VERBOSE=0"
REM Mobile app backend (mobile-bff + mobile-proxy) is OPT-IN: excluded from the
REM default / 'all' build, built only when the 'mobile' group is requested.
set "MOBILE=0"

:parse
if "%~1"=="" goto build
set "A=%~1"
shift
if /i "!A!"=="-nodocker" ( set "DOCKER=0" & goto parse )
if /i "!A!"=="/nodocker" ( set "DOCKER=0" & goto parse )
if /i "!A!"=="info"  ( set "VERBOSE=1" & goto parse )
if /i "!A!"=="-info" ( set "VERBOSE=1" & goto parse )
if /i "!A!"=="-h"     goto usage
if /i "!A!"=="--help" goto usage
if /i "!A!"=="/?"     goto usage
if /i "!A!"=="all"   ( set "DOALL=1" & goto parse )
if /i "!A!"=="ui"    ( set "UI=1"    & goto parse )
if /i "!A!"=="ai"    goto g_ai
if /i "!A!"=="data"  goto g_data
if /i "!A!"=="docs"  goto g_docs
if /i "!A!"=="mail"  goto g_mail
if /i "!A!"=="admin"             goto g_admin
if /i "!A!"=="admin-console"     goto g_admin_console
if /i "!A!"=="platform-core"     goto g_platform_core
if /i "!A!"=="libs"              goto g_libs
if /i "!A!"=="platform-registry" goto g_platform_registry
if /i "!A!"=="data-ingestion"    goto g_data_ingestion
if /i "!A!"=="mobile"            goto g_mobile
if /i "!A!"=="decisions"         goto g_decisions
if /i "!A!"=="decision-log"      goto g_decisions
if /i "!A!"=="agents"            goto g_agents
if /i "!A!"=="mcp"               goto g_agents
REM ── Functionality groups (names match startmodules.bat) ──
if /i "!A!"=="aicopilot"         goto g_aicopilot
if /i "!A!"=="email"             goto g_email
if /i "!A!"=="docingest"         goto g_docs
if /i "!A!"=="ragsearch"         goto g_ragsearch
if /i "!A!"=="dataingestion"     goto g_data_ingestion
if /i "!A!"=="agent-briefing"      goto g_agent_briefing
if /i "!A!"=="agent-decision"      goto g_agent_decision
if /i "!A!"=="agent-email-triage"  goto g_agent_emailtriage
if /i "!A!"=="agent-comms-triage"  goto g_agent_commstriage
if /i "!A!"=="agent-attention"     goto g_agent_attention
if /i "!A!"=="mcp-priority"      goto g_mcp_priority
if /i "!A!"=="mcp-decision"      goto g_mcp_decision
if /i "!A!"=="mcp-rag"           goto g_mcp_rag
if /i "!A!"=="mcp-mail"          goto g_mcp_mail
if /i "!A!"=="mcp-comms"         goto g_mcp_comms
if /i "!A!"=="mcp-reasoning"     goto g_mcp_reasoning
if /i "!A!"=="mcp-graph"         goto g_mcp_graph
if /i "!A!"=="mcp-ingest"        goto g_mcp_ingest
echo.
echo  Unknown group: !A!
echo.
goto usage

:g_ai
call :vlog "group 'ai' -> modules nl-query-service, agent-orchestrator-service; images nl-query, agent-orchestrator"
call :add services/nl-query-service nl-query
call :add services/agent-orchestrator-service agent-orchestrator
goto parse
:g_data
call :vlog "group 'data' -> okg-ingest, okg-reasoning, graph-proxy, priority-engine, briefing, decision-log, notification"
call :add services/okg-ingest-service okg-ingest
call :add services/okg-reasoning-service okg-reasoning
call :add services/graph-proxy-service graph-proxy
call :add services/priority-engine-service priority-engine
call :add services/briefing-service briefing-service
call :add services/decision-log-service decision-log
call :add services/notification-service notification-service
goto parse
:g_docs
call :vlog "group 'docs' -> module doc-ingest-service; image doc-ingest"
call :add services/doc-ingest-service doc-ingest
goto parse
:g_mail
call :vlog "group 'mail' -> module mail-insight-service; image mail-insight"
call :add services/mail-insight-service mail-insight
goto parse
:g_admin
call :vlog "group 'admin' -> modules platform-core-service, admin-console-service, widgets-service; images platform-core, admin-console, widgets"
call :add services/platform-core-service platform-core
call :add services/admin-console-service admin-console
call :add services/widgets-service widgets
goto parse
:g_admin_console
call :vlog "group 'admin-console' -> module admin-console-service; image admin-console"
call :add services/admin-console-service admin-console
goto parse
:g_platform_core
call :vlog "group 'platform-core' -> module platform-core-service; image platform-core"
call :add services/platform-core-service platform-core
goto parse
:g_libs
call :vlog "group 'libs' -> shared libraries eip-common, eip-secrets (no docker image)"
call :add libs/eip-common ""
call :add libs/eip-secrets ""
goto parse
:g_platform_registry
call :vlog "group 'platform-registry' -> module platform-registry-service; image platform-registry"
call :add services/platform-registry-service platform-registry
goto parse
:g_data_ingestion
call :vlog "group 'data-ingestion' -> module data-ingestion-service; image data-ingestion"
call :add services/data-ingestion-service data-ingestion
goto parse
:g_mobile
call :vlog "group 'mobile' -> module mobile-bff-service; images mobile-bff, mobile-proxy (native mobile app backend)"
set "MOBILE=1"
call :add services/mobile-bff-service mobile-bff
call :add "" mobile-proxy
goto parse
:g_decisions
call :vlog "group 'decisions' -> module decision-log-service; images decision-log, plugin-decision-log"
call :add services/decision-log-service decision-log
call :add "" plugin-decision-log
goto parse
:g_agents
call :vlog "group 'agents' -> Modular Agents & MCP mesh (full catalog): 8 MCP servers + 5 agents"
call :add mcp/eip-mcp-priority mcp-priority
call :add mcp/eip-mcp-decision mcp-decision
call :add mcp/eip-mcp-rag mcp-rag
call :add mcp/eip-mcp-mail mcp-mail
call :add mcp/eip-mcp-comms mcp-comms
call :add mcp/eip-mcp-reasoning mcp-reasoning
call :add mcp/eip-mcp-graph mcp-graph
call :add mcp/eip-mcp-ingest mcp-ingest
call :add agents/eip-agent-briefing agent-briefing
call :add agents/eip-agent-decision agent-decision
call :add agents/eip-agent-email-triage agent-email-triage
call :add agents/eip-agent-comms-triage agent-comms-triage
call :add agents/eip-agent-attention agent-attention
goto parse

REM ── Functionality groups: same names/slices as startmodules.bat, so you can
REM    build.bat <functionality> then startmodules.bat <functionality>. Shared
REM    upstreams (nl-query, priority-engine, …) are built by their own groups or 'all'. ──
:g_aicopilot
call :vlog "group 'aicopilot' -> nl-query, briefing-service"
call :add services/nl-query-service nl-query
call :add services/briefing-service briefing-service
goto parse
:g_email
call :vlog "group 'email' -> mail-insight (incl. comms channels), notification-service, nl-query, comms mesh"
call :add services/mail-insight-service mail-insight
call :add services/notification-service notification-service
call :add services/nl-query-service nl-query
call :add mcp/eip-mcp-comms mcp-comms
call :add agents/eip-agent-comms-triage agent-comms-triage
goto parse
:g_ragsearch
call :vlog "group 'ragsearch' -> nl-query, doc-ingest, graph-proxy (docling is a pulled image)"
call :add services/nl-query-service nl-query
call :add services/doc-ingest-service doc-ingest
call :add services/graph-proxy-service graph-proxy
goto parse
:g_agent_briefing
call :vlog "group 'agent-briefing' -> agent-briefing + mcp-priority"
call :add agents/eip-agent-briefing agent-briefing
call :add mcp/eip-mcp-priority mcp-priority
goto parse
:g_agent_decision
call :vlog "group 'agent-decision' -> agent-decision + mcp-decision"
call :add agents/eip-agent-decision agent-decision
call :add mcp/eip-mcp-decision mcp-decision
goto parse
:g_agent_emailtriage
call :vlog "group 'agent-email-triage' -> agent-email-triage + mcp-mail"
call :add agents/eip-agent-email-triage agent-email-triage
call :add mcp/eip-mcp-mail mcp-mail
goto parse
:g_agent_commstriage
call :vlog "group 'agent-comms-triage' -> agent-comms-triage + mcp-comms"
call :add agents/eip-agent-comms-triage agent-comms-triage
call :add mcp/eip-mcp-comms mcp-comms
goto parse
:g_agent_attention
call :vlog "group 'agent-attention' -> agent-attention + mcp-priority + mcp-reasoning"
call :add agents/eip-agent-attention agent-attention
call :add mcp/eip-mcp-priority mcp-priority
call :add mcp/eip-mcp-reasoning mcp-reasoning
goto parse
:g_mcp_priority
call :vlog "group 'mcp-priority' -> mcp-priority"
call :add mcp/eip-mcp-priority mcp-priority
goto parse
:g_mcp_decision
call :vlog "group 'mcp-decision' -> mcp-decision"
call :add mcp/eip-mcp-decision mcp-decision
goto parse
:g_mcp_rag
call :vlog "group 'mcp-rag' -> mcp-rag"
call :add mcp/eip-mcp-rag mcp-rag
goto parse
:g_mcp_mail
call :vlog "group 'mcp-mail' -> mcp-mail"
call :add mcp/eip-mcp-mail mcp-mail
goto parse
:g_mcp_comms
call :vlog "group 'mcp-comms' -> mcp-comms"
call :add mcp/eip-mcp-comms mcp-comms
goto parse
:g_mcp_reasoning
call :vlog "group 'mcp-reasoning' -> mcp-reasoning"
call :add mcp/eip-mcp-reasoning mcp-reasoning
goto parse
:g_mcp_graph
call :vlog "group 'mcp-graph' -> mcp-graph"
call :add mcp/eip-mcp-graph mcp-graph
goto parse
:g_mcp_ingest
call :vlog "group 'mcp-ingest' -> mcp-ingest"
call :add mcp/eip-mcp-ingest mcp-ingest
goto parse

REM ── %1 = maven module path, %2 = compose service name (use "" if none) ──
:add
if not "%~1"=="" (
  if defined MODULES ( set "MODULES=!MODULES!,%~1" ) else ( set "MODULES=%~1" )
)
if not "%~2"=="" (
  if defined SERVICES ( set "SERVICES=!SERVICES! %~2" ) else ( set "SERVICES=%~2" )
)
goto :eof

REM ── verbose logger: only prints when 'info' was passed ──
:vlog
if "%VERBOSE%"=="1" echo    [info] %~1
goto :eof

:build
REM  No group selected -> default to 'all'.
if "%DOALL%"=="0" if not defined MODULES if "%UI%"=="0" (
  call :vlog "No group given - defaulting to 'all'."
  set "DOALL=1"
)

if "%DOALL%"=="1" goto buildall

if "%VERBOSE%"=="1" (
  echo.
  echo  ============================================================
  echo   build.bat - verbose mode
  echo   Maven modules : %MODULES%
  echo   Docker images : %SERVICES%
  echo   Rebuild images: %DOCKER%   (0 = jars only)
  echo   UI (shell+remotes): %UI%
  echo  ============================================================
)

if defined MODULES (
  echo.
  echo === Building jars: %MODULES% ===
  call :vlog "Running: mvnw.cmd -pl %MODULES% -am package -DskipTests"
  call :vlog "  -pl  builds only the listed modules; -am also builds their upstream deps (eip-common/eip-secrets)."
  call :vlog "  -DskipTests packages without running unit tests."
  call mvnw.cmd -pl %MODULES% -am package -DskipTests
  if errorlevel 1 ( echo. & echo  Maven build FAILED. & exit /b 1 )
)

if "%DOCKER%"=="0" (
  echo.
  echo  Jars built. Skipping docker image rebuild ^(-nodocker^).
  goto end
)

set "ALLSVC=%SERVICES%"
set "UISVC=shell plugin-ai-copilot plugin-email-scanner plugin-doc-ingest plugin-rag-search plugin-widgets plugin-decision-log"

REM mobile-bff / mobile-proxy sit behind the "mobile" compose profile (they are
REM excluded from the full-platform start), and a profiled service is absent
REM from the compose model entirely unless its profile is active - so without
REM this the build dies with "no such service: mobile-bff". Same reason the
REM voice build in :buildall activates the "voice" profile.
if "%MOBILE%"=="1" set "COMPOSE_PROFILES=%COMPOSE_PROFILES%,mobile"

if not defined ALLSVC if "%UI%"=="0" (
  echo.
  echo  Nothing to build as an image ^(libs only^). Rebuild a dependent service group to apply.
  goto end
)

if defined ALLSVC (
  echo.
  echo === Rebuilding images: %ALLSVC% ===
  call :vlog "Running: docker compose -p eip-app -f docker-compose.app.yml build %ALLSVC%"
  docker compose -p eip-app -f docker-compose.app.yml build %ALLSVC%
  if errorlevel 1 ( echo. & echo  Docker image build FAILED. & exit /b 1 )
)

if "%UI%"=="1" (
  REM UI images (shell + 6 plugin remotes) are memory-heavy Vite/Node builds
  REM (~11,000+ modules transformed each, incl. the full @mui/icons-material
  REM bundle). Passing them all to one "docker compose build" call lets
  REM BuildKit build them concurrently, which can crash the build worker on a
  REM Docker Desktop VM with a modest memory allocation - surfaces as
  REM "rpc error: code = Unavailable ... EOF" mid-build (each image builds
  REM fine alone). Building one at a time avoids that at the cost of some
  REM wall-clock time.
  echo.
  echo === Rebuilding UI images one at a time: %UISVC% ===
  for %%S in (%UISVC%) do (
    echo.
    echo   -- building %%S --
    docker compose -p eip-app -f docker-compose.app.yml build %%S
    if errorlevel 1 ( echo. & echo  Docker image build FAILED for %%S. & exit /b 1 )
  )
)
goto end

:buildall
REM Disable delayed expansion for this section so a literal '!' survives in the
REM Maven module-exclusion arg below. buildall uses only %VAR%-style expansion,
REM so turning it off here is safe.
setlocal DisableDelayedExpansion
echo.
echo === Full build: all modules + all app images ===
if "%VERBOSE%"=="1" (
  echo.
  echo  ============================================================
  echo   build.bat all - verbose mode
  echo   Rebuild images: %DOCKER%   (0 = jars only)
  echo   Mobile (bff+proxy): %MOBILE%   (0 = excluded; pass 'mobile' to include)
  echo  ============================================================
)
if "%MOBILE%"=="1" (
  call :vlog "Running: mvnw.cmd clean package -DskipTests  (incl. mobile-bff)"
  call :vlog "  'clean' wipes prior target/ output; 'package' builds every module's jar; tests skipped."
  call mvnw.cmd clean package -DskipTests
) else (
  call :vlog "Running: mvnw.cmd clean package -DskipTests -pl !services/mobile-bff-service  (mobile excluded)"
  call :vlog "  'clean' wipes prior target/ output; 'package' builds every module's jar except mobile-bff; tests skipped."
  call mvnw.cmd clean package -DskipTests -pl !services/mobile-bff-service
)
if errorlevel 1 ( echo. & echo  Maven build FAILED. & exit /b 1 )
if "%DOCKER%"=="0" ( echo. & echo  Jars built. Skipping docker image rebuild ^(-nodocker^). & goto end )
echo.
echo === Building all app images ===
REM Split into a batched non-UI build + a one-at-a-time UI loop instead of a
REM single bare "docker compose build" (which hands every image, including
REM the 6 memory-heavy UI/Vite builds, to BuildKit concurrently and can crash
REM the build worker on a modest Docker Desktop memory allocation - "rpc
REM error: code = Unavailable ... EOF" mid-build). "docling" is excluded: it
REM has no build directive (pulled image), so it has nothing to build.
set "NONUISVC=platform-core priority-engine admin-console doc-ingest widgets graph-proxy notification-service okg-ingest briefing-service mail-insight nl-query okg-reasoning agent-orchestrator decision-log platform-registry data-ingestion mcp-priority mcp-decision mcp-rag mcp-mail mcp-comms mcp-reasoning mcp-graph mcp-ingest agent-briefing agent-decision agent-email-triage agent-comms-triage agent-attention"
set "UISVC=shell plugin-ai-copilot plugin-email-scanner plugin-doc-ingest plugin-rag-search plugin-widgets plugin-decision-log"
REM Mobile app backend is opt-in: only build its images when 'mobile' was requested.
REM Its compose services sit behind the "mobile" profile (so the full-platform
REM start never runs them), and a profiled service is invisible to compose unless
REM the profile is active - hence COMPOSE_PROFILES, exactly as for voice below.
if "%MOBILE%"=="1" set "NONUISVC=%NONUISVC% mobile-bff mobile-proxy"
if "%MOBILE%"=="1" set "COMPOSE_PROFILES=%COMPOSE_PROFILES%,mobile"
REM Voice is opt-in too, and its compose services sit behind the "voice" profile —
REM so the profile has to be active for compose to see them at all, or the build
REM fails with "no such service: voice". "whisper" is excluded for the same reason
REM "docling" is: it is a pulled image with no build directive.
if /i "%EIP_VOICE_ENABLED%"=="true" (
  set "NONUISVC=%NONUISVC% voice"
  set "COMPOSE_PROFILES=%COMPOSE_PROFILES%,voice"
)

call :vlog "Running: docker compose -p eip-app -f docker-compose.app.yml build %NONUISVC%"
docker compose -p eip-app -f docker-compose.app.yml build %NONUISVC%
if errorlevel 1 ( echo. & echo  Docker build FAILED. & exit /b 1 )

echo.
echo === Rebuilding UI images one at a time: %UISVC% ===
for %%S in (%UISVC%) do (
  echo.
  echo   -- building %%S --
  docker compose -p eip-app -f docker-compose.app.yml build %%S
  if errorlevel 1 ( echo. & echo  Docker image build FAILED for %%S. & exit /b 1 )
)
goto end

:usage
echo.
echo  Usage: build.bat [group ...] [-nodocker] [info]
echo.
echo  Functionality groups (same names as startmodules.bat):
echo     aicopilot           nl-query, briefing
echo     email               mail-insight, notification, nl-query
echo     admin               platform-core, admin-console, widgets
echo     docingest           doc-ingest                       (alias: docs)
echo     ragsearch           nl-query, doc-ingest, graph-proxy
echo     decisions           decision-log + its UI remote     (alias: decision-log)
echo     dataingestion       data-ingestion                   (alias: data-ingestion)
echo     agents  (alias mcp) full catalog mesh: 8 MCP servers + 5 agents
echo     agent-briefing / agent-decision / agent-email-triage / agent-comms-triage / agent-attention
echo                         one agent + its MCP server(s)
echo     mcp-priority / mcp-decision / mcp-rag / mcp-mail / mcp-comms / mcp-reasoning
echo       / mcp-graph / mcp-ingest        one MCP tool server each
echo.
echo  Build-only groups (technical bundles):
echo     ai      nl-query, agent-orchestrator
echo     data    okg-ingest, okg-reasoning, graph-proxy, priority-engine,
echo             briefing, decision-log, notification
echo     mail    mail-insight only (soft-reads priority-engine's EPQ queue)
echo     admin-console       admin-console-service only
echo     platform-core       platform-core-service only
echo     platform-registry   platform-registry-service
echo     ui      shell + the 6 plugin remotes (Module Federation)
echo     libs    eip-common, eip-secrets
echo     mobile  mobile-bff + mobile-proxy (native app backend; OPT-IN, excluded from 'all')
echo     all     full clean package + every app image  (DEFAULT; excludes mobile)
echo.
echo  Options:
echo     -nodocker   build jars only (skip docker image rebuild)
echo     info        verbose - print each step and the exact command run
echo.
echo  Note: build.bat only builds jars and images. Use start.bat to start
echo        / restart the containers.
echo.
echo  Examples:
echo     build.bat                 (same as 'build.bat all'; no mobile)
echo     build.bat aicopilot
echo     build.bat agent-briefing
echo     build.bat ragsearch -nodocker
echo     build.bat all info
echo     build.bat mobile          (just the mobile app + BFF)
echo     build.bat all mobile      (full build including the mobile app + BFF)
echo.
exit /b 2

:end
echo.
echo  Done. Build complete - run start.bat to (re)start the stack.
endlocal
