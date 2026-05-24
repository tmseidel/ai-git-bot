import { test, expect } from '@playwright/test';

test('Collapsible sections helper script is served', async ({ page, request }) => {
  // Step 1: Navigate to the preview root URL
  await page.goto('/');
  await page.waitForLoadState('domcontentloaded');

  // Step 2: Request /js/collapsible-sections.js
  const response = await request.get('/js/collapsible-sections.js');

  // Assertion: HTTP 200 and contains the initCollapsibleSections function
  expect(response.status()).toBe(200);
  const body = await response.text();
  expect(body).toContain('initCollapsibleSections');
});
