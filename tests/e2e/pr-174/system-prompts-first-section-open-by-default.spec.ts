import { test, expect } from '@playwright/test';

test('Review System-Prompt section is expanded by default on first visit', async ({ browser }) => {
  // Use a fresh context with cleared storage so this is the first visit
  const context = await browser.newContext();
  const page = await context.newPage();

  // Ensure localStorage is clean before navigation
  await page.goto('/system-settings/system-prompts/1/edit');
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
  await page.reload();

  const reviewCollapse = page.locator('#collapseReviewPrompt');
  await expect(reviewCollapse).toHaveClass(/\bshow\b/);

  const reviewTextarea = page.locator('#reviewSystemPrompt');
  await expect(reviewTextarea).toBeVisible();

  // Other sections should not have the show class
  const otherCollapseIds = [
    '#collapseIssueAgentPrompt',
    '#collapseWriterAgentPrompt',
    '#collapseE2EPlannerPrompt',
    '#collapseE2EAuthorPrompt',
    '#collapseE2ERunnerPrompt',
  ];

  for (const sel of otherCollapseIds) {
    const el = page.locator(sel);
    if (await el.count() > 0) {
      await expect(el).not.toHaveClass(/\bshow\b/);
    }
  }

  await context.close();
});
