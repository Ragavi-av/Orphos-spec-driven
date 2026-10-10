# -----------------------------------------------------------------------------
# Orphos EIP - Make targets (cross-platform: works with GNU Make on Windows,
# macOS, and Linux without depending on POSIX-shell features in recipes).
#
# Compose stacks are kept as 4 separate Compose projects (eip-infra, eip-data,
# eip-ai, eip-app) to match start.bat / stop.bat. Named volumes
# (e.g. eip-infra_postgres_data, eip-ai_eip-ollama-data) are therefore
# identical across both flows, so data survives whether you start the
# platform via `make` or via the .bat scripts.
# -----------------------------------------------------------------------------

# Pick the right Maven wrapper for the host OS. GNU Make defines
# OS=Windows_NT on Windows; the variable is empty on macOS/Linux.
ifeq ($(OS),Windows_NT)
	MVNW := mvnw.cmd
else
	MVNW := ./mvnw
endif

STACKS_UP   := infra data ai app
STACKS_DOWN := app ai data infra

UP_TARGETS     := $(addprefix up-,$(STACKS_UP))
DOWN_TARGETS   := $(addprefix down-,$(STACKS_DOWN))
STATUS_TARGETS := $(addprefix status-,$(STACKS_UP))

.PHONY: start stop build status logs \
        logs-infra logs-data logs-ai logs-app \
        backup \
        $(UP_TARGETS) $(DOWN_TARGETS) $(STATUS_TARGETS)

# Composite targets — Make runs prerequisites in the listed order (single-job
# build), so we get infra->data->ai->app on start and reverse on stop, with
# no shell-specific `for` loop.
start: $(UP_TARGETS)
stop:  $(DOWN_TARGETS)
status: $(STATUS_TARGETS)

# Per-stack pattern rules — one line each, runs identically in cmd and sh.
up-%:
	docker compose -p eip-$* -f docker-compose.$*.yml up -d --remove-orphans

down-%:
	docker compose -p eip-$* -f docker-compose.$*.yml down --remove-orphans

status-%:
	docker compose -p eip-$* -f docker-compose.$*.yml ps

build:
	$(MVNW) clean package -DskipTests
	docker compose -p eip-app -f docker-compose.app.yml build

# `logs` tails the app stack by default. compose -f can only stream one
# project at a time, so use logs-{infra,data,ai,app} for the others.
logs: logs-app

logs-infra:
	docker compose -p eip-infra -f docker-compose.infra.yml logs -f

logs-data:
	docker compose -p eip-data -f docker-compose.data.yml logs -f

logs-ai:
	docker compose -p eip-ai -f docker-compose.ai.yml logs -f

logs-app:
	docker compose -p eip-app -f docker-compose.app.yml logs -f

# backup.sh is POSIX shell; needs bash. On Windows install Git for Windows
# (provides bash.exe on PATH) or run from WSL.
backup:
	bash scripts/backup.sh
