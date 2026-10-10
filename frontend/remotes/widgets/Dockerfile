# Build context is ./frontend (the whole npm workspace — see
# docker-compose.app.yml's `context: ./frontend, dockerfile: remotes/widgets/Dockerfile`).
FROM node:20-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json* ./
COPY packages ./packages
COPY shell ./shell
COPY remotes ./remotes
RUN npm install
WORKDIR /workspace/remotes/widgets
RUN npm run build

FROM nginx:alpine
COPY --from=build /workspace/remotes/widgets/dist /usr/share/nginx/html
COPY remotes/widgets/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80