import { test, expect } from '@playwright/test';

test('Clicking Expand all adds show class to all wf-collapse-* elements', async ({ page }) => {
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();
  await page.waitForLoadState('domcontentloaded');

  const collapseBtn = page.locator('#collapseAllWorkflows');
  const expandBtn = page.locator('#expandAllWorkflows');
  await collapseBtn.waitFor({ state: 'visible', timeout: 10000 });
  await expandBtn.waitFor({ state: 'visible', timeout: 10000 });

  const collapseTargets = page.locator("[id^='wf-collapse-']");
  await expect.poll(async () => await collapseTargets.count(), { timeout: 10000 }).toBeGreaterThan(0);

  // Known state: collapsed
  await collapseBtn.click();
  await page.waitForTimeout(800);

  // Expand all
  await expandBtn.click();
  await page.waitForTimeout(800);

  await expect.poll(async () => {
    return await collapseTargets.evaluateAll((els) =>
      els.every((el) => el.classList.contains('show'))
    );
  }, { timeout: 5000 }).toBe(true);
});
