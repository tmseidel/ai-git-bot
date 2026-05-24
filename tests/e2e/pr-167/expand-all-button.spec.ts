import { test, expect } from '@playwright/test';

const SECTION_IDS = [
  '#section-system-prompts',
  '#section-mcp-configurations',
  '#section-bot-tools',
  '#section-workflow-configurations',
  '#section-deployment-targets',
];

test.describe('System settings — Expand all button', () => {
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

  test('clicking Expand all reveals every section', async ({ page }) => {
    await page.goto('/system-settings');
    await page.waitForLoadState('networkidle');

    // Collapse all first.
    const collapseAll = page.getByRole('button', { name: /collapse all/i });
    await expect(collapseAll).toBeVisible();
    await collapseAll.click();

    // Wait for at least one section to be hidden before we re-expand.
    await expect(page.locator(SECTION_IDS[0])).toBeHidden({ timeout: 5000 });

    const expandAll = page.getByRole('button', { name: /expand all/i });
    await expect(expandAll).toBeVisible();
    await expandAll.click();

    for (const id of SECTION_IDS) {
      await expect(page.locator(id), `Expected ${id} to be visible after Expand all`).toBeVisible({ timeout: 5000 });
    }
  });
});
