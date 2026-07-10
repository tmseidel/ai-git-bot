import { test, expect } from '@playwright/test';

test('expand all button shows every section after collapsing', async ({ page }) => {
  await page.goto('/system-settings');

  const sectionIds = [
    '#section-system-prompts',
    '#section-mcp-configurations',
    '#section-bot-tools',
    '#section-workflow-configurations',
    '#section-deployment-targets',
  ];

  await page.getByRole('button', { name: /collapse all/i }).click();
  for (const id of sectionIds) {
    await expect(page.locator(id)).toBeHidden();
  }

  await page.getByRole('button', { name: /expand all/i }).click();
  for (const id of sectionIds) {
    await expect(page.locator(id)).toBeVisible();
  }

  // Clean up persisted state
  await page.evaluate(() => {
    try { window.localStorage.clear(); } catch {}
  });
});
