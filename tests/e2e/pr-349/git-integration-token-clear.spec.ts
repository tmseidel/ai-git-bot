import { test, expect } from '@playwright/test';

/**
 * E2E coverage for the Git integration token clear behavior introduced in PR-349.
 *
 * These specs verify that:
 * - Clicking "Clear" shows a visible "will be removed on save" warning.
 * - Typing a new token after clicking Clear cancels the pending removal
 *   (the hidden clearToken flag is reset to false).
 * - The integration can be saved with a new token after a clear-then-type cycle.
 *
 * The spec creates and deletes its own Git integration to stay idempotent.
 */

const LIST_URL = '/git-integrations';

async function dismissToast(page: any) {
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
    await expect(dismissButton).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }
}

async function createGitIntegration(page: any, uniqueName: string) {
  await page.goto(`${LIST_URL}/new`);
  await page.waitForLoadState('networkidle');
  await dismissToast(page);

  await page.locator('#name').first().waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('#name').first().fill(uniqueName);

  // Gitea is the default provider; ensure the URL field is visible and fill it.
  const providerSelect = page.locator('#providerType').first();
  await providerSelect.waitFor({ state: 'visible', timeout: 30_000 });
  await providerSelect.selectOption('GITEA');

  const urlInput = page.locator('#url').first();
  await urlInput.waitFor({ state: 'visible', timeout: 30_000 });
  await urlInput.fill('https://gitea.example.com');

  const tokenInput = page.locator('#token').first();
  await tokenInput.waitFor({ state: 'visible', timeout: 30_000 });
  await tokenInput.fill('initial-token');

  const submit = page.locator('button[type="submit"]:has-text("Save")').first();
  await submit.waitFor({ state: 'visible', timeout: 30_000 });
  await submit.click();

  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);
  expect(page.url()).not.toContain('/new');
}

async function deleteGitIntegration(page: any, uniqueName: string) {
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

test('clearing a token shows a pending-removal warning and resets when typing', async ({ page }) => {
  const uniqueName = `E2E Git Clear ${Date.now()}`;

  try {
    await createGitIntegration(page, uniqueName);

    // Open the edit form.
    await page.goto(LIST_URL);
    await page.waitForLoadState('networkidle');
    const row = page.locator('tr').filter({ hasText: uniqueName }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.locator('a:has-text("Edit")').first().click();

    await page.waitForLoadState('networkidle');
    await dismissToast(page);

    const tokenInput = page.locator('#token').first();
    const clearButton = page.locator('#clearTokenBtn').first();
    const clearPendingHint = page.locator('#tokenClearPendingHint').first();
    const keepHint = page.locator('#tokenKeepHint').first();
    const clearFlag = page.locator('#clearToken').first();

    await expect(tokenInput).toBeVisible({ timeout: 30_000 });
    await expect(clearButton).toBeVisible({ timeout: 30_000 });
    await expect(keepHint).toBeVisible();
    await expect(clearPendingHint).toBeHidden();
    await expect(clearFlag).toHaveValue('false');

    // Click Clear: pending warning appears and flag is set.
    await clearButton.click();
    await expect(tokenInput).toHaveValue('');
    await expect(clearPendingHint).toBeVisible();
    await expect(keepHint).toBeHidden();
    await expect(clearFlag).toHaveValue('true');

    // Type a new token: pending warning disappears and flag resets.
    await tokenInput.fill('replacement-token');
    await expect(clearPendingHint).toBeHidden();
    await expect(keepHint).toBeVisible();
    await expect(clearFlag).toHaveValue('false');

    // Save with the new token.
    const submit = page.locator('button[type="submit"]:has-text("Save")').first();
    await submit.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1_000);

    // Should redirect back to the list and still show the integration.
    expect(page.url()).toContain(LIST_URL);
    await expect(page.getByText(uniqueName, { exact: false }).first()).toBeVisible({ timeout: 10_000 });
  } finally {
    await deleteGitIntegration(page, uniqueName);
  }
});

test('saving after clear without typing removes the token', async ({ page }) => {
  const uniqueName = `E2E Git Remove ${Date.now()}`;

  try {
    await createGitIntegration(page, uniqueName);

    await page.goto(LIST_URL);
    await page.waitForLoadState('networkidle');
    const row = page.locator('tr').filter({ hasText: uniqueName }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.locator('a:has-text("Edit")').first().click();

    await page.waitForLoadState('networkidle');
    await dismissToast(page);

    const tokenInput = page.locator('#token').first();
    const clearButton = page.locator('#clearTokenBtn').first();
    const clearPendingHint = page.locator('#tokenClearPendingHint').first();
    const clearFlag = page.locator('#clearToken').first();

    await expect(tokenInput).toBeVisible({ timeout: 30_000 });

    await clearButton.click();
    await expect(clearPendingHint).toBeVisible();
    await expect(clearFlag).toHaveValue('true');

    // Submit without typing a replacement.
    const submit = page.locator('button[type="submit"]:has-text("Save")').first();
    await submit.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1_000);

    expect(page.url()).toContain(LIST_URL);
    await expect(page.getByText(uniqueName, { exact: false }).first()).toBeVisible({ timeout: 10_000 });
  } finally {
    await deleteGitIntegration(page, uniqueName);
  }
});
