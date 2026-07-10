import { test, expect } from '@playwright/test';

test('collapsed section state persists across page reloads', async ({ page }) => {
  await page.goto('/system-settings');

  const section = page.locator('#section-bot-tools');
  const header = page.locator('[data-bs-target="#section-bot-tools"], [aria-controls="section-bot-tools"]').first();

  await expect(section).toBeVisible();

  // Collapse the Tool configurations / bot-tools section
  await header.click();
  await expect(section).toBeHidden();

  // Reload and confirm it remains hidden
  await page.reload();
  await expect(page.locator('#section-bot-tools')).toBeHidden();

  // Restore: expand it again and clear storage for idempotency
  const headerAfter = page.locator('[data-bs-target="#section-bot-tools"], [aria-controls="section-bot-tools"]').first();
  await headerAfter.click();
  await expect(page.locator('#section-bot-tools')).toBeVisible();
  await page.evaluate(() => {
    try { window.localStorage.clear(); } catch {}
  });
});
