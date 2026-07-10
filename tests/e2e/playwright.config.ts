import { defineConfig } from '@playwright/test';

// Locally the app is expected on http://localhost:8080. Override with BASE_URL
// to point the same specs at a different deployment (e.g. a preview URL).
const baseURL = process.env.BASE_URL ?? 'http://localhost:8080';

// Mirrors the workspace config the bot scaffolds for PR runs:
//   * workers: 1 + fullyParallel: false — deterministic order against a
//     single shared backend, no races on the shared database.
//   * retries: 0 — flakiness must surface as a real failure.
//   * storageState: undefined — each context starts with no cookies /
//     localStorage so auth/wizard progress cannot leak between tests.
export default defineConfig({
  testDir: '.',
  // Artefacts land under the project's root target/ dir (already git-ignored).
  outputDir: '../../target/test-results',
  reporter: [['json', { outputFile: '../../target/playwright-report/report.json' }],
             ['list']],
  fullyParallel: false,
  workers: 1,
  retries: 0,
  use: {
    baseURL,
    storageState: undefined,
    ignoreHTTPSErrors: true,
  },
});
