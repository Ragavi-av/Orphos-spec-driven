#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Orphos - start the platform (Linux / macOS).
#
# Needs Docker and nothing else: no Java, no Maven, no Node, no source code.
#
# Safe to run repeatedly. Images already loaded are not reloaded, containers that
# already exist are reused, and your data is never touched.
# -----------------------------------------------------------------------------
exec "$(dirname "$0")/orphos.sh" start
