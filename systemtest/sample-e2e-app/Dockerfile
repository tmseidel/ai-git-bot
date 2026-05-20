# Tiny single-file Node demo used by the AI-Git-Bot M4 wave-2 E2E test
# recipe (doc/PR_WORKFLOWS_E2E.md). The application is intentionally
# trivial: a single login form whose only purpose is to give the
# Playwright-based runner something deterministic to assert against.
#
# The image is rebuilt every time the compose stack starts so any change
# operators make under sample-e2e-app/ is picked up without an extra
# build step.
FROM node:20-alpine
WORKDIR /app
COPY package.json .
COPY server.js .
EXPOSE 3000
HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
    CMD wget -qO- http://localhost:3000/healthz || exit 1
CMD ["node", "server.js"]

