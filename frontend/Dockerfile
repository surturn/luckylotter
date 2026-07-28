# Multi-stage — no local Node required, everything runs in Docker.
FROM node:22-alpine AS build
WORKDIR /app
# Dependencies first so a source-only change doesn't reinstall them.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
