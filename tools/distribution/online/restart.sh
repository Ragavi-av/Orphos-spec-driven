#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Orphos - restart the platform (Linux / macOS).
#
# Stop, start, wait for health, open the browser. Data is kept throughout.
# -----------------------------------------------------------------------------
exec "$(dirname "$0")/orphos.sh" restart
