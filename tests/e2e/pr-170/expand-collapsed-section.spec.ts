import { test, expect } from '@playwright/test';

test('clicking a collapsed section header expands it again', async ({ page }) => {
  await page.goto('/system-settings');

  const section = page.locator('#section-mcp-configurations');
  const header = page.locator('[data-bs-target="#section-mcp-configurations"], [aria-controls="section-mcp-configurations"]').first();

  await expect(section).toBeVisible();

  // Collapse
  await header.click();
  await expect(section).toBeHidden();

  // Expand again
  await header.click();
  await expect(section).toBeVisible();

  // Clean up persisted state
  await page.evaluate(() => {
    try { window.localStorage.clear(); } catch {}
  });
});
