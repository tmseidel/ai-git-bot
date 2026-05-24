import { test, expect } from '@playwright/test';

const SECTION_IDS = [
  '#section-system-prompts',
  '#section-mcp-configurations',
  '#section-bot-tools',
  '#section-workflow-configurations',
  '#section-deployment-targets',
];

test.describe('System settings — Collapse all button', () => {
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

  test('clicking Collapse all hides every section', async ({ page }) => {
    await page.goto('/system-settings');
    await page.waitForLoadState('networkidle');

    // Sanity: sections start visible.
    for (const id of SECTION_IDS) {
      await expect(page.locator(id)).toBeVisible();
    }

    const collapseAll = page.getByRole('button', { name: /collapse all/i });
    await expect(collapseAll).toBeVisible();
    await collapseAll.click();

    for (const id of SECTION_IDS) {
      await expect(page.locator(id), `Expected ${id} to be hidden after Collapse all`).toBeHidden({ timeout: 5000 });
    }
  });
});
