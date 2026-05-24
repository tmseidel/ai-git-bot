import { test, expect } from '@playwright/test';

test('System settings page responds OK and renders without errors', async ({ page }) => {
  // Navigate to root first
  const rootResponse = await page.goto('/');
  expect(rootResponse, 'root response defined').not.toBeNull();

  // Open the /system-settings page
  const response = await page.goto('/system-settings');
  expect(response, 'response defined').not.toBeNull();
  expect(response!.status(), 'HTTP status').toBeLessThan(400);

  // Ensure page content has loaded
  await page.waitForLoadState('domcontentloaded');

  const bodyText = (await page.locator('body').innerText()).toLowerCase();
  expect(bodyText).not.toContain('internal server error');
  expect(bodyText).not.toContain('traceback');
  expect(bodyText).not.toMatch(/\b500\b/);
});
