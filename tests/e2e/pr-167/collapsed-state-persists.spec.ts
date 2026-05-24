import { test, expect } from '@playwright/test';

const OTHER_SECTION_IDS = [
  '#section-system-prompts',
  '#section-bot-tools',
  '#section-workflow-configurations',
  '#section-deployment-targets',
];

test.describe('System settings — collapsed state persists', () => {
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

  test('MCP configurations stays collapsed after reload', async ({ page }) => {
    await page.goto('/system-settings');
    await page.waitForLoadState('networkidle');

    const mcp = page.locator('#section-mcp-configurations');
    await expect(mcp).toBeVisible();

    // Click on the MCP configurations card header.
    const headerCandidate = page
      .locator('.card', { has: mcp })
      .locator('.card-header, [class*="card-header"], header')
      .first();

    if ((await headerCandidate.count()) === 0) {
      await page.getByRole('button', { name: /mcp configurations/i }).first().click();
    } else {
      await headerCandidate.click();
    }

    await expect(mcp).toBeHidden({ timeout: 5000 });

    // Reload and ensure state persists.
    await page.reload();
    await page.waitForLoadState('networkidle');

    await expect(page.locator('#section-mcp-configurations')).toBeHidden({ timeout: 5000 });

    for (const id of OTHER_SECTION_IDS) {
      await expect(page.locator(id), `Expected ${id} to remain visible after reload`).toBeVisible();
    }
  });
});
