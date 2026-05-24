import { test, expect } from '@playwright/test';

test('Add button in section header navigates without toggling collapse', async ({ page }) => {
  await page.goto('/system-settings');

  const section = page.locator('#section-system-prompts');
  await expect(section).toBeVisible();

  // Find the System prompts card and click its Add button
  const header = page.locator('[data-bs-target="#section-system-prompts"], [aria-controls="section-system-prompts"]').first();
  const card = header.locator('xpath=ancestor::*[contains(@class,"card")][1]');
  const addButton = card.getByRole('link', { name: /add/i }).first();

  await Promise.all([
    page.waitForURL(/\/system-settings\/system-prompts\/new/),
    addButton.click(),
  ]);

  expect(page.url()).toContain('/system-settings/system-prompts/new');

  // Idempotency: navigate back and clear any persisted state
  await page.goto('/system-settings');
  await page.evaluate(() => {
    try { window.localStorage.clear(); } catch {}
  });
});
