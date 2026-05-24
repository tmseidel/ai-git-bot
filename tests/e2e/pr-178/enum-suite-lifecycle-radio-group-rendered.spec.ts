import { test, expect } from '@playwright/test';

test('Suite lifecycle field renders the four expected radios', async ({ page }) => {
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();
  await page.waitForLoadState('domcontentloaded');

  const expandBtn = page.locator('#expandAllWorkflows');
  if (await expandBtn.isVisible().catch(() => false)) {
    await expandBtn.click();
    await page.waitForTimeout(800);
  }

  for (const label of ['Ephemeral', 'Offer as PR', 'Promote on merge', 'Commit to PR']) {
    const radioByLabel = page.locator(`label:has-text("${label}")`).first();
    await expect(radioByLabel).toBeVisible({ timeout: 10000 });
  }
});
