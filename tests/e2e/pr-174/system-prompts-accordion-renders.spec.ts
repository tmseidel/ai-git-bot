import { test, expect } from '@playwright/test';

test('System-Prompts edit page renders accordion with all 10 sections', async ({ page }) => {
  await page.goto('/system-settings/system-prompts/1/edit');

  const accordion = page.locator('#systemPromptsAccordion');
  await expect(accordion).toBeVisible();

  const items = accordion.locator('.accordion-item');
  await expect(items).toHaveCount(10);

  // Verify the expected prompt headers are present
  const expectedHeaders = [
    'Review System-Prompt',
    'Issue-Agent System-Prompt',
    'Writer-Agent System-Prompt',
    'E2E Planner',
    'E2E Author',
    'E2E Runner System-Prompt',
  ];

  const headerText = (await accordion.locator('.accordion-button').allTextContents()).join(' | ');
  for (const expected of expectedHeaders) {
    expect(headerText).toContain(expected);
  }
});
