import { test, expect } from '@playwright/test';

test('Collapsed state of wf-collapse-* persists after reload', async ({ page }) => {
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();
  await page.waitForLoadState('domcontentloaded');
  const workflowsUrl = page.url();

  const collapseBtn = page.locator('#collapseAllWorkflows');
  await collapseBtn.waitFor({ state: 'visible', timeout: 10000 });

  const collapseTargets = page.locator("[id^='wf-collapse-']");
  await expect.poll(async () => await collapseTargets.count(), { timeout: 10000 }).toBeGreaterThan(0);

  await collapseBtn.click();
  await page.waitForTimeout(800);

  // Reload the page
  await page.goto(workflowsUrl);
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);

  const targetsAfter = page.locator("[id^='wf-collapse-']");
  await expect.poll(async () => await targetsAfter.count(), { timeout: 10000 }).toBeGreaterThan(0);

  await expect.poll(async () => {
    return await targetsAfter.evaluateAll((els) =>
      els.every((el) => !el.classList.contains('show'))
    );
  }, { timeout: 5000 }).toBe(true);

  // Cleanup: re-expand so subsequent tests start expanded
  const expandBtn = page.locator('#expandAllWorkflows');
  if (await expandBtn.isVisible().catch(() => false)) {
    await expandBtn.click();
    await page.waitForTimeout(800);
  }
});
