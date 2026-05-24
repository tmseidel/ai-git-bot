import { test, expect } from '@playwright/test';

test('Selecting "Offer as PR" persists across save and reload', async ({ page }) => {
  // Navigate to workflows edit page
  await page.goto('/system-settings');
  await page.waitForLoadState('domcontentloaded');

  const workflowsLink = page.locator('a[href*="/workflows"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 10000 });
  await workflowsLink.click();
  await page.waitForLoadState('domcontentloaded');
  const workflowsUrl = page.url();

  // Make sure sections are expanded
  const expandBtn = page.locator('#expandAllWorkflows');
  if (await expandBtn.isVisible().catch(() => false)) {
    await expandBtn.click();
    await page.waitForTimeout(800);
  }

  // Locate the suite lifecycle radio group via the "Offer as PR" option
  const offerAsPrInput = page.locator('input[type="radio"][value="offer_as_pr"]')
    .or(page.locator('label:has-text("Offer as PR")').locator('input[type="radio"]'))
    .first();
  await expect(offerAsPrInput).toBeVisible({ timeout: 10000 });

  const groupName = await offerAsPrInput.getAttribute('name');
  expect(groupName).toBeTruthy();

  // Capture original checked value to restore later (idempotency)
  const originalValue = await page.locator(`input[type="radio"][name="${groupName}"]`).evaluateAll(
    (els) => (els as HTMLInputElement[]).find((e) => e.checked)?.value ?? null
  );

  // Try to enable E2E workflow checkbox if present and not yet enabled.
  // Heuristic: find a checkbox in the same collapsible card as the radio group.
  const e2eCheckbox = page.locator('input[type="checkbox"][name*="e2e" i], input[type="checkbox"][id*="e2e" i]').first();
  const hadE2eCheckbox = await e2eCheckbox.count() > 0;
  let e2eWasChecked = true;
  if (hadE2eCheckbox) {
    e2eWasChecked = await e2eCheckbox.isChecked().catch(() => true);
    if (!e2eWasChecked) {
      await e2eCheckbox.check().catch(() => {});
    }
  }

  // Click "Offer as PR"
  await offerAsPrInput.check({ force: true });
  await expect(offerAsPrInput).toBeChecked();

  // Submit the form
  const submitBtn = page.locator('button[type="submit"], input[type="submit"]').first();
  await submitBtn.waitFor({ state: 'visible', timeout: 10000 });
  await Promise.all([
    page.waitForLoadState('domcontentloaded'),
    submitBtn.click(),
  ]);
  await page.waitForTimeout(500);

  // Reload workflows edit page
  await page.goto(workflowsUrl);
  await page.waitForLoadState('domcontentloaded');

  const expandBtn2 = page.locator('#expandAllWorkflows');
  if (await expandBtn2.isVisible().catch(() => false)) {
    await expandBtn2.click();
    await page.waitForTimeout(800);
  }

  const offerAfter = page.locator(`input[type="radio"][name="${groupName}"][value="offer_as_pr"]`)
    .or(page.locator('label:has-text("Offer as PR")').locator('input[type="radio"]'))
    .first();
  await expect(offerAfter).toBeChecked({ timeout: 10000 });

  // --- Cleanup: restore original state for idempotency ---
  if (originalValue && originalValue !== 'offer_as_pr') {
    const original = page.locator(`input[type="radio"][name="${groupName}"][value="${originalValue}"]`).first();
    if (await original.count() > 0) {
      await original.check({ force: true }).catch(() => {});
    }
  }
  if (hadE2eCheckbox && !e2eWasChecked) {
    const cb = page.locator('input[type="checkbox"][name*="e2e" i], input[type="checkbox"][id*="e2e" i]').first();
    if (await cb.isChecked().catch(() => false)) {
      await cb.uncheck().catch(() => {});
    }
  }
  const submitBtn2 = page.locator('button[type="submit"], input[type="submit"]').first();
  if (await submitBtn2.isVisible().catch(() => false)) {
    await submitBtn2.click().catch(() => {});
    await page.waitForLoadState('domcontentloaded').catch(() => {});
  }
});
