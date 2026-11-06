import { test, expect } from '@playwright/test';

/**
 * E2E coverage for the AI integration API key clear behavior introduced in PR-349.
 *
 * These specs verify that:
 * - Clicking "Clear" shows a visible "will be removed on save" warning.
 * - Typing a new API key after clicking Clear cancels the pending removal
 *   (the hidden clearApiKey flag is reset to false).
 * - The integration can be saved with a new API key after a clear-then-type cycle.
 *
 * The spec creates and deletes its own AI integration to stay idempotent.
 */

const LIST_URL = '/ai-integrations';

async function dismissToast(page: any) {
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
    await expect(dismissButton).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }
}

async function createAiIntegration(page: any, uniqueName: string) {
  await page.goto(`${LIST_URL}/new`);
  await page.waitForLoadState('networkidle');
  await dismissToast(page);

  await page.locator('#name').first().waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('#name').first().fill(uniqueName);

  const providerSelect = page.locator('#providerType').first();
  await providerSelect.waitFor({ state: 'visible', timeout: 30_000 });
  await providerSelect.selectOption('ANTHROPIC');

  const apiUrlInput = page.locator('#apiUrl').first();
  await apiUrlInput.waitFor({ state: 'visible', timeout: 30_000 });
  await apiUrlInput.fill('https://api.anthropic.com');

  const apiKeyInput = page.locator('#apiKey').first();
  await apiKeyInput.waitFor({ state: 'visible', timeout: 30_000 });
  await apiKeyInput.fill('initial-api-key');

  const modelInput = page.locator('#model').first();
  await modelInput.waitFor({ state: 'visible', timeout: 30_000 });
  await modelInput.fill('claude-sonnet-4');

  const submit = page.locator('button[type="submit"]:has-text("Save")').first();
  await submit.waitFor({ state: 'visible', timeout: 30_000 });
  await submit.click();

  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);
  expect(page.url()).not.toContain('/new');
}

async function deleteAiIntegration(page: any, uniqueName: string) {
  await page.goto(LIST_URL);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);

  const row = page.locator('tr').filter({ hasText: uniqueName }).first();
  if (await row.count() > 0) {
    const deleteButton = row.locator('button:has-text("Delete")').first();
    if (await deleteButton.count() > 0) {
      page.once('dialog', (dialog: any) => dialog.accept().catch(() => undefined));
      await deleteButton.click();
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(1_000);
    }
  }
}

test('clearing an API key shows a pending-removal warning and resets when typing', async ({ page }) => {
  const uniqueName = `E2E AI Clear ${Date.now()}`;

  try {
    await createAiIntegration(page, uniqueName);

    await page.goto(LIST_URL);
    await page.waitForLoadState('networkidle');
    const row = page.locator('tr').filter({ hasText: uniqueName }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.locator('a:has-text("Edit")').first().click();

    await page.waitForLoadState('networkidle');
    await dismissToast(page);

    const apiKeyInput = page.locator('#apiKey').first();
    const clearButton = page.locator('#clearApiKeyBtn').first();
    const clearPendingHint = page.locator('#apiKeyClearPendingHint').first();
    const keepHint = page.locator('#apiKeyKeepHint').first();
    const clearFlag = page.locator('#clearApiKey').first();

    await expect(apiKeyInput).toBeVisible({ timeout: 30_000 });
    await expect(clearButton).toBeVisible({ timeout: 30_000 });
    await expect(keepHint).toBeVisible();
    await expect(clearPendingHint).toBeHidden();
    await expect(clearFlag).toHaveValue('false');

    await clearButton.click();
    await expect(apiKeyInput).toHaveValue('');
    await expect(clearPendingHint).toBeVisible();
    await expect(keepHint).toBeHidden();
    await expect(clearFlag).toHaveValue('true');

    await apiKeyInput.fill('replacement-api-key');
    await expect(clearPendingHint).toBeHidden();
    await expect(keepHint).toBeVisible();
    await expect(clearFlag).toHaveValue('false');

    const submit = page.locator('button[type="submit"]:has-text("Save")').first();
    await submit.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1_000);

    expect(page.url()).toContain(LIST_URL);
    await expect(page.getByText(uniqueName, { exact: false }).first()).toBeVisible({ timeout: 10_000 });
  } finally {
    await deleteAiIntegration(page, uniqueName);
  }
});

test('saving after clear without typing removes the API key', async ({ page }) => {
  const uniqueName = `E2E AI Remove ${Date.now()}`;

  try {
    await createAiIntegration(page, uniqueName);

    await page.goto(LIST_URL);
    await page.waitForLoadState('networkidle');
    const row = page.locator('tr').filter({ hasText: uniqueName }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.locator('a:has-text("Edit")').first().click();

    await page.waitForLoadState('networkidle');
    await dismissToast(page);

    const apiKeyInput = page.locator('#apiKey').first();
    const clearButton = page.locator('#clearApiKeyBtn').first();
    const clearPendingHint = page.locator('#apiKeyClearPendingHint').first();
    const clearFlag = page.locator('#clearApiKey').first();

    await expect(apiKeyInput).toBeVisible({ timeout: 30_000 });

    await clearButton.click();
    await expect(clearPendingHint).toBeVisible();
    await expect(clearFlag).toHaveValue('true');

    const submit = page.locator('button[type="submit"]:has-text("Save")').first();
    await submit.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1_000);

    expect(page.url()).toContain(LIST_URL);
    await expect(page.getByText(uniqueName, { exact: false }).first()).toBeVisible({ timeout: 10_000 });
  } finally {
    await deleteAiIntegration(page, uniqueName);
  }
});
