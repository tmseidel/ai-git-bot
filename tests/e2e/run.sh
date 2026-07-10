#!/usr/bin/env bash
# Local E2E test runner for all Playwright specs under tests/e2e/.
#
# Usage:
#   ./run.sh                      # run against http://localhost:8080
#   BASE_URL=http://host:9000 ./run.sh
#   ./run.sh pr-254               # pass any args straight through to `playwright test`
#
# The app under test is expected to be already running (default localhost:8080).
set -euo pipefail

cd "$(dirname "$0")"

BASE_URL="${BASE_URL:-http://localhost:8080}"
export BASE_URL

echo "==> Target BASE_URL: ${BASE_URL}"

# Fail fast with a clear message if the app isn't reachable.
if ! curl -sfo /dev/null --max-time 5 "${BASE_URL}"; then
  echo "!! Cannot reach ${BASE_URL} — is the application running?" >&2
  echo "   Start it first (e.g. 'mvn spring-boot:run' or docker compose up) then re-run." >&2
  exit 1
fi

# Install JS deps on first run (or after they were cleaned).
if [ ! -d node_modules/@playwright/test ]; then
  echo "==> Installing npm dependencies..."
  npm install
fi

# Ensure the Playwright browser is present (idempotent). Skipped automatically
# if chromium is already installed. Add --with-deps by hand if OS libs are
# missing (that needs root, so it's left out of the default path).
echo "==> Ensuring Playwright chromium is installed..."
npx playwright install chromium

echo "==> Running E2E specs under tests/e2e/ ..."
exec npx playwright test "$@"
