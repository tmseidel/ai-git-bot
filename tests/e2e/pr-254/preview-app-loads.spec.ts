import { test, expect } from '@playwright/test';

test.describe('Preview deployment serves the app root', () => {
  test('root path loads without a fatal error', async ({ page }) => {
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => {
      pageErrors.push(err);
    });

    // Step: Navigate to the preview URL root path
    const response = await page.goto('/', { waitUntil: 'domcontentloaded' });

    // Assertion: The response status is 2xx or 3xx
    expect(response, 'navigation should produce a response').not.toBeNull();
    const status = response!.status();
    expect(status, `unexpected HTTP status ${status}`).toBeGreaterThanOrEqual(200);
    expect(status, `unexpected HTTP status ${status}`).toBeLessThan(400);

    // Wait for the DOM to settle a bit so late-rendering frameworks have a chance.
    await page.waitForLoadState('load').catch(() => {});

    // The page should render some content in <body> without a fatal script error.
    await expect(page.locator('body')).toBeVisible();

    const bodyText = (await page.locator('body').innerText().catch(() => '')) ?? '';
    // Give client-side apps a brief moment to hydrate if the body was initially empty.
    if (bodyText.trim().length === 0) {
      await page.waitForTimeout(1000);
    }

    // No uncaught page errors should have been raised during load.
    expect(
      pageErrors,
      `page raised errors: ${pageErrors.map((e) => e.message).join(' | ')}`,
    ).toEqual([]);

    // Sanity check: the document has an <html> element (i.e. it's not a blank/aborted response).
    await expect(page.locator('html')).toHaveCount(1);
  });
});
