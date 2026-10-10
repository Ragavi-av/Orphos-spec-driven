#!/bin/sh
# Seed shiro.ini from the bundled template on first boot, substitute the admin
# password from ADMIN_PASSWORD, then launch Fuseki. Mirrors the Apache
# jena-fuseki-docker entrypoint so behaviour matches the previous image.
set -e

mkdir -p "$FUSEKI_BASE"

# First boot (or a wiped volume): copy the template shiro.ini, which carries the
# ${ADMIN_PASSWORD} placeholder. On subsequent boots the persisted file is kept.
if [ ! -f "$FUSEKI_BASE/shiro.ini" ]; then
  cp "$FUSEKI_HOME/shiro.ini" "$FUSEKI_BASE/shiro.ini"
fi

# Substitute the admin password. No-op once already substituted (placeholder gone).
if [ -n "$ADMIN_PASSWORD" ]; then
  export ADMIN_PASSWORD
  envsubst '${ADMIN_PASSWORD}' < "$FUSEKI_BASE/shiro.ini" > "$FUSEKI_BASE/shiro.ini.tmp"
  mv "$FUSEKI_BASE/shiro.ini.tmp" "$FUSEKI_BASE/shiro.ini"
  unset ADMIN_PASSWORD
fi

exec "$@"
