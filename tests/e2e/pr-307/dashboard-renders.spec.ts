import { test, expect } from '@playwright/test';

test.describe('Dashboard renders after V37 migration', () => {
  test('dashboard page loads successfully without migration errors', async ({ page }) => {
    const pageErrors: Error[] = [];
    const consoleErrors: string[] = [];

    page.on('pageerror', (error) => {
      pageErrors.push(error);
    });

    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });

    // Step 1: Navigate to the preview URL root
    const response = await page.goto('/', { waitUntil: 'domcontentloaded' });

    // The server should have responded successfully (not a 5xx migration error)
    expect(response, 'navigation response should exist').not.toBeNull();
    if (response) {
      expect(
        response.status(),
        `expected a non-error HTTP status, received ${response.status()}`,
      ).toBeLessThan(400);
    }

    // Step 2: Wait for the dashboard page to finish loading
    await page.waitForLoadState('networkidle').catch(() => {
      // networkidle can occasionally hang on apps with long-poll connections; ignore.
    });

    // Give the DOM a beat to settle before asserting.
    await page.waitForSelector('body', { state: 'attached' });

    // Assertion: The dashboard page renders successfully without migration errors.
    // 1. The <body> should contain some rendered content (not an empty error shell).
    const bodyText = (await page.locator('body').innerText()).trim();
    expect(bodyText.length, 'dashboard body should contain rendered text').toBeGreaterThan(0);

    // 2. No obvious migration / server-error markers should appear on the page.
    const lowerBody = bodyText.toLowerCase();
    const errorMarkers = [
      'migration failed',
      'migration error',
      'internal server error',
      'application error',
      'this page isn’t working',
      "this page isn't working",
    ];
    for (const marker of errorMarkers) {
      expect(lowerBody, `dashboard should not display "${marker}"`).not.toContain(marker);
    }

    // 3. No uncaught page errors should have been thrown during load.
    expect(pageErrors, `unexpected page errors: ${pageErrors.map((e) => e.message).join(', ')}`).toEqual([]);

    // 4. The document should have a title (a rendered app sets one; a crashed migration usually does not).
    const title = await page.title();
    expect(title, 'dashboard document should have a non-empty <title>').not.toEqual('');
  });
});
