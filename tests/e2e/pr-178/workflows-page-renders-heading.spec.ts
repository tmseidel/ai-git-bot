import { test, expect } from '@playwright/test';

test('Workflows edit page shows "Workflows for" heading', async ({ page }) => {
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  // Find a link leading to a workflows edit page
  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();

  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText(/Workflows for/i).first()).toBeVisible({ timeout: 10000 });
});
