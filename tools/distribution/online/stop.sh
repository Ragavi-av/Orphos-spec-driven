#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Orphos - stop the platform (Linux / macOS).
#
# Stops the containers and keeps every byte of data: databases, uploaded
# documents and AI models all live in Docker volumes, which this never removes.
# -----------------------------------------------------------------------------
exec "$(dirname "$0")/orphos.sh" stop
