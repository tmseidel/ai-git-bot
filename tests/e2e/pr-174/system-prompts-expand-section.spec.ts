import { test, expect } from '@playwright/test';

test('Clicking an accordion header expands a collapsed prompt section', async ({ page }) => {
  await page.goto('/system-settings/system-prompts/1/edit');
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
  await page.reload();

  const button = page.getByRole('button', { name: /Issue-Agent System-Prompt/i });
  await button.click();

  const collapse = page.locator('#collapseIssueAgentPrompt');
  // Wait for the collapse animation to finish
  await expect(collapse).toHaveClass(/\bshow\b/, { timeout: 5000 });

  const textarea = page.locator('#issueAgentSystemPrompt');
  await expect(textarea).toBeVisible();

  // Cleanup: collapse it back so subsequent tests start clean
  await button.click();
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
});
