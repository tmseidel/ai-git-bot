import { test, expect } from '@playwright/test';

test('Collapse all button closes every prompt section', async ({ page }) => {
  await page.goto('/system-settings/system-prompts/1/edit');

  await page.locator('#expandAllPromptsBtn').click();
  await page.waitForTimeout(600);

  await page.locator('#collapseAllPromptsBtn').click();
  await page.waitForTimeout(600);

  const collapses = page.locator('#systemPromptsAccordion .accordion-collapse');
  const count = await collapses.count();
  expect(count).toBeGreaterThan(0);

  for (let i = 0; i < count; i++) {
    await expect(collapses.nth(i)).not.toHaveClass(/\bshow\b/, { timeout: 5000 });
  }

  // Cleanup the localStorage so the default state resumes for other tests
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
});
