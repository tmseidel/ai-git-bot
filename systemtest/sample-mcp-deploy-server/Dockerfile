# Tiny image for the M5 sample MCP deploy server.
# Node 20 LTS is small enough and ships npm out of the box.
FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY server.js ./

EXPOSE 8090

HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
  CMD wget -qO- http://localhost:8090/healthz || exit 1

CMD ["node", "server.js"]

