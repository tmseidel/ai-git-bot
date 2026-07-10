import { test, expect } from '@playwright/test';

test('system settings page loads with all sections expanded by default', async ({ page }) => {
  await page.goto('/system-settings');

  const sectionIds = [
    '#section-system-prompts',
    '#section-mcp-configurations',
    '#section-bot-tools',
    '#section-workflow-configurations',
    '#section-deployment-targets',
  ];

  for (const id of sectionIds) {
    await expect(page.locator(id)).toBeVisible();
  }
});
