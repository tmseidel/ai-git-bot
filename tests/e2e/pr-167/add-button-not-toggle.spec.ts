import { test, expect } from '@playwright/test';

test.describe('System settings — Add button navigates and does not toggle', () => {
  test.beforeEach(async ({ context }) => {
    await context.clearCookies();
    await context.addInitScript(() => {
      try {
        window.localStorage.clear();
        window.sessionStorage.clear();
      } catch {
        /* ignore */
      }
    });
  });

  test.afterEach(async ({ page }) => {
    await page.evaluate(() => {
      try {
        window.localStorage.clear();
        window.sessionStorage.clear();
      } catch {
        /* ignore */
      }
    });
  });

  test('clicking Add in System prompts navigates to /system-settings/system-prompts/new', async ({ page }) => {
    await page.goto('/system-settings');
    await page.waitForLoadState('networkidle');

    const systemPrompts = page.locator('#section-system-prompts');
    await expect(systemPrompts).toBeVisible();

    // Find the System prompts card and click its Add button.
    const card = page.locator('.card', { has: systemPrompts });

    let addButton = card.getByRole('link', { name: /^add$/i }).first();
    if ((await addButton.count()) === 0) {
      addButton = card.getByRole('button', { name: /^add$/i }).first();
    }
    if ((await addButton.count()) === 0) {
      // Last-resort fallback: any element labelled Add inside the card.
      addButton = card.getByText(/^add$/i).first();
    }

    await expect(addButton).toBeVisible();
    await addButton.click();

    await page.waitForURL(/\/system-settings\/system-prompts\/new/, { timeout: 5000 });
    expect(page.url()).toContain('/system-settings/system-prompts/new');
  });
});
