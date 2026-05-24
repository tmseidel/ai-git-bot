import { test, expect } from '@playwright/test';

test('Clicking Collapse all removes show class from wf-collapse-* elements', async ({ page }) => {
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();
  await page.waitForLoadState('domcontentloaded');

  const collapseBtn = page.locator('#collapseAllWorkflows');
  await collapseBtn.waitFor({ state: 'visible', timeout: 10000 });

  // Ensure at least one collapse target exists
  const collapseTargets = page.locator("[id^='wf-collapse-']");
  await expect.poll(async () => await collapseTargets.count(), { timeout: 10000 }).toBeGreaterThan(0);

  await collapseBtn.click();

  // Wait for Bootstrap collapse animation
  await page.waitForTimeout(800);

  // Verify none have the 'show' class
  await expect.poll(async () => {
    return await collapseTargets.evaluateAll((els) =>
      els.every((el) => !el.classList.contains('show'))
    );
  }, { timeout: 5000 }).toBe(true);

  // Cleanup: restore expanded state for idempotency
  const expandBtn = page.locator('#expandAllWorkflows');
  if (await expandBtn.isVisible().catch(() => false)) {
    await expandBtn.click();
    await page.waitForTimeout(800);
  }
});
