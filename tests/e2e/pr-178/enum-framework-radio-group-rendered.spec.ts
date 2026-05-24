import { test, expect } from '@playwright/test';

test('Test framework field renders Playwright/pytest/k6/Cypress radios', async ({ page }) => {
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();
  await page.waitForLoadState('domcontentloaded');

  // Ensure things are expanded so radios are visible
  const expandBtn = page.locator('#expandAllWorkflows');
  if (await expandBtn.isVisible().catch(() => false)) {
    await expandBtn.click();
    await page.waitForTimeout(800);
  }

  for (const label of ['Playwright', 'pytest', 'k6', 'Cypress']) {
    const radio = page.locator(`label:has-text("${label}")`).locator('input[type="radio"]')
      .or(page.locator(`input[type="radio"][value="${label.toLowerCase()}"]`));
    await expect(radio.first()).toBeVisible({ timeout: 10000 });
  }
});
