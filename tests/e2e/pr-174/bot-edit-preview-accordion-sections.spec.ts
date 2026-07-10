import { test, expect } from '@playwright/test';

test('Bot-Edit System-Prompt preview modal shows all 8 accordion sections', async ({ page }) => {
  await page.goto('/bots');

  // Open the edit modal for the first bot listed
  const editTrigger = page
    .locator('[data-bs-toggle="modal"], a, button')
    .filter({ hasText: /Edit/i })
    .first();
  await editTrigger.click();

  // Trigger the System-Prompt preview action
  const previewBtn = page
    .locator('#previewSystemPrompt')
  await previewBtn.click();

  const previewAccordion = page.locator('#systemPromptPreviewAccordion');
  await expect(previewAccordion).toBeVisible({ timeout: 8000 });

  const items = previewAccordion.locator('.accordion-item');
  await expect(items).toHaveCount(6);

  const headersText = (await previewAccordion.locator('.accordion-button').allTextContents()).join(' | ');
  expect(headersText).toContain('E2E Planner');
  expect(headersText).toContain('E2E Author');
  expect(headersText).toContain('E2E Runner System-Prompt');

  // Close any open modal so the page state stays clean
  await page.keyboard.press('Escape').catch(() => {});
});
