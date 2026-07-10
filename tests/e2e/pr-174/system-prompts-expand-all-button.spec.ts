import { test, expect } from '@playwright/test';

test('Expand all button opens every prompt section', async ({ page }) => {
  await page.goto('/system-settings/system-prompts/1/edit');

  await page.locator('#expandAllPromptsBtn').click();

  // Wait briefly for the bootstrap collapse animations
  await page.waitForTimeout(600);

  const collapses = page.locator('#systemPromptsAccordion .accordion-collapse');
  const count = await collapses.count();
  expect(count).toBe(8);

  for (let i = 0; i < count; i++) {
    await expect(collapses.nth(i)).toHaveClass(/\bshow\b/, { timeout: 5000 });
  }

  // Cleanup so subsequent tests start fresh
  await page.locator('#collapseAllPromptsBtn').click().catch(() => {});
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
});
