# syntax=docker/dockerfile:1.7
# Build context is ./frontend (the whole npm workspace — see
# docker-compose.app.yml's `context: ./frontend, dockerfile: shell/Dockerfile`).
# npm workspaces need the root package.json + lockfile alongside every
# workspace package's package.json to resolve correctly, so a single-package
# build context (just `shell/`) isn't enough.
FROM node:20-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json* ./
COPY packages ./packages
COPY shell ./shell
COPY remotes ./remotes
# BuildKit cache mount: the npm download cache survives across builds, so a
# lockfile change re-installs only the changed packages instead of everything.
RUN --mount=type=cache,target=/root/.npm npm install
WORKDIR /workspace/shell

# Build-time env vars — passed from docker-compose build.args
# Vite bakes VITE_* variables into the bundle at build time.
# VITE_KEYCLOAK_REALM is gone: the realm is resolved at runtime from the
# browser's hostname via platform-core-service's tenant lookup.
ARG VITE_KEYCLOAK_URL=http://localhost:8443
ARG VITE_KEYCLOAK_CLIENT=orphos-spa
ARG VITE_ADMIN_API_BASE=
ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL
ENV VITE_KEYCLOAK_CLIENT=$VITE_KEYCLOAK_CLIENT
ENV VITE_ADMIN_API_BASE=$VITE_ADMIN_API_BASE

# BuildKit cache mount: Vite's transform cache persists across builds, so an
# unchanged dependency graph is re-used instead of re-transformed from scratch.
RUN --mount=type=cache,target=/workspace/shell/node_modules/.vite npm run build

FROM nginx:alpine
COPY --from=build /workspace/shell/dist /usr/share/nginx/html
COPY shell/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
