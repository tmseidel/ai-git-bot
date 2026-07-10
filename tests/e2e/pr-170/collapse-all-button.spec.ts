import { test, expect } from '@playwright/test';

test('collapse all button hides every section', async ({ page }) => {
  await page.goto('/system-settings');

  const sectionIds = [
    '#section-system-prompts',
    '#section-mcp-configurations',
    '#section-bot-tools',
    '#section-workflow-configurations',
    '#section-deployment-targets',
  ];

  // Ensure starting visible
  for (const id of sectionIds) {
    await expect(page.locator(id)).toBeVisible();
  }

  await page.getByRole('button', { name: /collapse all/i }).click();

  for (const id of sectionIds) {
    await expect(page.locator(id)).toBeHidden();
  }

  // Restore state for idempotency
  await page.getByRole('button', { name: /expand all/i }).click();
  for (const id of sectionIds) {
    await expect(page.locator(id)).toBeVisible();
  }
  await page.evaluate(() => {
    try { window.localStorage.clear(); } catch {}
  });
});
