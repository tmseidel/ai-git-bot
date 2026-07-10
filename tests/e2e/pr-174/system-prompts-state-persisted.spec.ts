import { test, expect } from '@playwright/test';

test('Expanded/collapsed state persists across page reloads via localStorage', async ({ page }) => {
  await page.goto('/system-settings/system-prompts/1/edit');
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
  await page.reload();

  // Open E2E Runner System-Prompt section
  const button = page.getByRole('button', { name: /E2E Runner System-Prompt/i });
  await button.click();

  const collapse = page.locator('#collapseE2ERunnerPrompt');
  await expect(collapse).toHaveClass(/\bshow\b/, { timeout: 5000 });

  await page.reload();

  // After reload the section should still be open
  await expect(page.locator('#collapseE2ERunnerPrompt')).toHaveClass(/\bshow\b/, { timeout: 5000 });

  const stored = await page.evaluate(() =>
    localStorage.getItem('systemPromptsEdit.collapsedSections'),
  );
  expect(stored).not.toBeNull();
  // Either records the open section explicitly, or marks others as closed
  expect(stored).toContain('E2ERunnerPrompt');

  // Cleanup so the test is idempotent
  await page.evaluate(() => localStorage.removeItem('systemPromptsEdit.collapsedSections'));
});
