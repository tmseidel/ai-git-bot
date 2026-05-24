import { test, expect } from '@playwright/test';

const SECTION_IDS = [
  '#section-system-prompts',
  '#section-mcp-configurations',
  '#section-bot-tools',
  '#section-workflow-configurations',
  '#section-deployment-targets',
];

test.describe('System settings — sections visible by default', () => {
  test.beforeEach(async ({ context }) => {
    // Clear any persisted collapse state before navigating.
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
    // Reset state so subsequent tests start fresh.
    await page.evaluate(() => {
      try {
        window.localStorage.clear();
        window.sessionStorage.clear();
      } catch {
        /* ignore */
      }
    });
  });

  test('all five section containers are visible on first visit', async ({ page }) => {
    await page.goto('/system-settings');
    await page.waitForLoadState('networkidle');

    for (const id of SECTION_IDS) {
      const section = page.locator(id);
      await expect(section, `Expected ${id} to be visible by default`).toBeVisible();
    }
  });
});
