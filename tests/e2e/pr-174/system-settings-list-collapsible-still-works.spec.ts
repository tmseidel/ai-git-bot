import { test, expect } from '@playwright/test';

test('System settings list still toggles sections after refactor to shared helper', async ({ page }) => {
  await page.goto('/system-settings');

  await page.locator('#collapseAllSections').click();

  // Wait for collapse animations to settle
  await page.waitForTimeout(600);

  await page.reload();

  // Wait for sections to be present
  await page.waitForSelector('[id^=section-].collapse', { state: 'attached' });

  const sections = page.locator('[id^=section-].collapse');
  const count = await sections.count();
  expect(count).toBeGreaterThan(0);

  for (let i = 0; i < count; i++) {
    await expect(sections.nth(i)).not.toHaveClass(/\bshow\b/);
  }

  // Cleanup: re-expand sections by clicking expand-all if it exists, otherwise clear key
  const expandBtn = page.locator('#expandAllSections');
  if (await expandBtn.count() > 0) {
    await expandBtn.click();
    await page.waitForTimeout(600);
  }
});
