import { test, expect } from '@playwright/test';

test("header chevron reflects collapsed state via 'collapsed' class", async ({ page }) => {
  await page.goto('/system-settings');

  const section = page.locator('#section-deployment-targets');
  const header = page.locator('[data-bs-target="#section-deployment-targets"], [aria-controls="section-deployment-targets"]').first();

  await expect(section).toBeVisible();
  await expect(header).toHaveAttribute('aria-expanded', 'true');

  await header.click();
  await expect(section).toBeHidden();

  await expect(header).toHaveClass(/collapsed/);
  await expect(header).toHaveAttribute('aria-expanded', 'false');

  // Restore state for idempotency
  await header.click();
  await expect(section).toBeVisible();
  await page.evaluate(() => {
    try { window.localStorage.clear(); } catch {}
  });
});
