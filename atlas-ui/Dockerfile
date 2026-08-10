# Build the static SPA.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Serve the built assets with nginx, which also reverse-proxies /api to atlas-core so the
# whole app is one same-origin ingress. See nginx.conf and docker/docker-compose.prod.yml.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
