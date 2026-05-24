import { test, expect } from '@playwright/test';

const OTHER_SECTION_IDS = [
  '#section-mcp-configurations',
  '#section-bot-tools',
  '#section-workflow-configurations',
  '#section-deployment-targets',
];

test.describe('System settings — collapse a single section', () => {
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

  test('clicking the System prompts header hides only that section', async ({ page }) => {
    await page.goto('/system-settings');
    await page.waitForLoadState('networkidle');

    const target = page.locator('#section-system-prompts');
    await expect(target).toBeVisible();

    // Click the System prompts card header (but not the Add button inside it).
    const header = page
      .locator('.card', { has: page.locator('#section-system-prompts') })
      .locator('.card-header, [class*="card-header"], header')
      .first();

    // Fallback: if the structured locator above isn't present, click on the heading text.
    if ((await header.count()) === 0) {
      await page.getByRole('button', { name: /system prompts/i }).first().click();
    } else {
      await header.click();
    }

    // Wait for the collapse transition.
    await expect(target).toBeHidden({ timeout: 5000 });

    for (const id of OTHER_SECTION_IDS) {
      await expect(page.locator(id), `Expected ${id} to remain visible`).toBeVisible();
    }
  });
});
