import { test, expect } from '@playwright/test';

test('Multiple prompt sections can be open simultaneously', async ({ page }) => {
  await page.goto('/system-settings/system-prompts/1/edit');
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
  await page.reload();

  await page.getByRole('button', { name: /Issue-Agent System-Prompt/i }).click();
  await expect(page.locator('#collapseIssueAgentPrompt')).toHaveClass(/\bshow\b/, { timeout: 5000 });

  await page.getByRole('button', { name: /Writer-Agent System-Prompt/i }).click();
  await expect(page.locator('#collapseWriterAgentPrompt')).toHaveClass(/\bshow\b/, { timeout: 5000 });

  // Issue-Agent should still be open
  await expect(page.locator('#collapseIssueAgentPrompt')).toHaveClass(/\bshow\b/);

  // Cleanup
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
});
