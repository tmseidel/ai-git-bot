import { test, expect } from '@playwright/test';

test('Exactly one framework radio is checked and its value is "playwright"', async ({ page }) => {
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

  // Find a radio group that contains a "playwright" option — that is the framework group
  const playwrightRadio = page.locator('input[type="radio"][value="playwright"]').first();
  await expect(playwrightRadio).toBeVisible({ timeout: 10000 });

  const groupName = await playwrightRadio.getAttribute('name');
  expect(groupName, 'framework radio group name').toBeTruthy();

  const groupRadios = page.locator(`input[type="radio"][name="${groupName}"]`);
  const checkedValues = await groupRadios.evaluateAll((els) =>
    (els as HTMLInputElement[]).filter((e) => e.checked).map((e) => e.value)
  );

  expect(checkedValues).toHaveLength(1);
  expect(checkedValues[0]).toBe('playwright');
});
