# Tiny image for the M6 sample CI-Action dispatch+poll server.
# Mirrors the M5 sample-mcp-deploy-server layout.
FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY server.js ./

EXPOSE 8091

HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
  CMD wget -qO- http://localhost:8091/healthz || exit 1

CMD ["node", "server.js"]

