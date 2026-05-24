import { test, expect } from '@playwright/test';

test('Expand-all and Collapse-all buttons are visible on workflows edit page', async ({ page }) => {
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();
  await page.waitForLoadState('domcontentloaded');

  await expect(page.locator('#expandAllWorkflows')).toBeVisible({ timeout: 10000 });
  await expect(page.locator('#collapseAllWorkflows')).toBeVisible({ timeout: 10000 });
});
