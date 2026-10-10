# Build context is ./frontend (the whole npm workspace — see
# docker-compose.app.yml's `context: ./frontend, dockerfile: remotes/decision-log/Dockerfile`).
# The full workspace (shell + every remote + packages) is copied so npm's
# workspace resolution always sees a consistent tree, even though only this
# remote is actually built — root package.json's `workspaces` list includes
# the fixed path "shell", which must exist for `npm install` to resolve cleanly.
FROM node:20-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json* ./
COPY packages ./packages
COPY shell ./shell
COPY remotes ./remotes
RUN npm install
WORKDIR /workspace/remotes/decision-log
RUN npm run build

FROM nginx:alpine
COPY --from=build /workspace/remotes/decision-log/dist /usr/share/nginx/html
COPY remotes/decision-log/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
