#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Orphos - start only selected modules (Linux / macOS).
#
#   ./startmodules.sh admin
#   ./startmodules.sh core,aicopilot,docingest
#   ./startmodules.sh all
#
# Modules: admin, core, dataingestion, docingest, aicopilot, communication,
#          mobile, voice   (aliases: email = communication, all = every one)
#
# Run with no module to see the list. Modules already running are left alone,
# so more can be added later.
# -----------------------------------------------------------------------------
exec "$(dirname "$0")/orphos.sh" modules "$@"
