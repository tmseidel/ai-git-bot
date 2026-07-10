import { test, expect } from '@playwright/test';

test('clicking a section header collapses that section', async ({ page }) => {
  await page.goto('/system-settings');

  const section = page.locator('#section-system-prompts');
  await expect(section).toBeVisible();

  // Click the header that controls the system-prompts section
  const header = page.locator('[data-bs-target="#section-system-prompts"], [aria-controls="section-system-prompts"]').first();
  await header.click();

  await expect(section).toBeHidden();

  // Reset state: re-expand so subsequent tests start from default
  await header.click();
  await expect(section).toBeVisible();

  // Also clear any persisted collapse state
  await page.evaluate(() => {
    try { window.localStorage.clear(); } catch {}
  });
});
