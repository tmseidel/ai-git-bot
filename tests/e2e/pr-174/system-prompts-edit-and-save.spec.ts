import { test, expect } from '@playwright/test';

test('User can edit a prompt inside an accordion section and save successfully', async ({ page }) => {
  await page.goto('/system-settings/system-prompts/1/edit');

  // Ensure the Review section is open
  const reviewCollapse = page.locator('#collapseReviewPrompt');
  if (!(await reviewCollapse.evaluate((el) => el.classList.contains('show')).catch(() => false))) {
    await page.getByRole('button', { name: /Review System-Prompt/i }).click();
    await expect(reviewCollapse).toHaveClass(/\bshow\b/, { timeout: 5000 });
  }

  const textarea = page.locator('#reviewSystemPrompt');
  await expect(textarea).toBeVisible();

  const original = (await textarea.inputValue()) ?? '';
  const marker = `\n<!-- e2e-marker-${Date.now()} -->`;
  await textarea.fill(original + marker);

  // Submit the form containing this textarea
  await textarea.evaluate((el: HTMLTextAreaElement) => el.form?.requestSubmit());

  // Wait for navigation / save to settle
  await page.waitForLoadState('networkidle').catch(() => {});

  // Navigate back to the edit page
  await page.goto('/system-settings/system-prompts/1/edit');

  // Ensure the section is open to read the textarea value
  const reviewCollapse2 = page.locator('#collapseReviewPrompt');
  if (!(await reviewCollapse2.evaluate((el) => el.classList.contains('show')).catch(() => false))) {
    await page.getByRole('button', { name: /Review System-Prompt/i }).click();
    await expect(reviewCollapse2).toHaveClass(/\bshow\b/, { timeout: 5000 });
  }

  const reloadedValue = await page.locator('#reviewSystemPrompt').inputValue();
  expect(reloadedValue).toContain(marker.trim());

  // Cleanup: restore the original value to keep the test idempotent
  await page.locator('#reviewSystemPrompt').fill(original);
  await page
    .locator('#reviewSystemPrompt')
    .evaluate((el: HTMLTextAreaElement) => el.form?.requestSubmit());
  await page.waitForLoadState('networkidle').catch(() => {});
});
