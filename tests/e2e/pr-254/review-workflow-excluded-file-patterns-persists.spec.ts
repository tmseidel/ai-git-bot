 import { test, expect, Page } from '@playwright/test';

 const WORKFLOWS_URL = '/system-settings/workflow-configurations/1/workflows';
 const PATTERN_VALUE = '*.yaml';

 async function expandReviewSection(page: Page): Promise<void> {
       // Try to find a header/button/row that references the 'review' workflow.
       // We attempt several resilient locators to handle a variety of UI shells.
       const candidates = [
             page.getByRole('button', { name: /^review$/i }),
             page.getByRole('button', { name: /review/i }),
             page.getByRole('tab', { name: /^review$/i }),
             page.locator('[data-workflow="review"]'),
             page.locator('text=/^\\s*review\\s*$/i').first(),
           ];
    
       for (const candidate of candidates) {
             try {
                   if (await candidate.count() > 0) {
                         const first = candidate.first();
                         await first.waitFor({ state: 'visible', timeout: 2000 });
                         await first.click();
                         // Give the section time to expand.
                         await page.waitForTimeout(300);
                         return;
                       }
                 } catch {
                   // Try next candidate.
                 }
           }
    
       throw new Error("Could not locate the 'review' workflow section to expand.");
     }

 async function getExcludedFilePatternsInput(page: Page) {
       // Wait for the input to become available inside the expanded review section.
       const byLabel = page.getByLabel(/excludedFilePatterns/i);
       const byPlaceholder = page.getByPlaceholder(/excludedFilePatterns/i);
       const byName = page.locator('input[id="param-review-excludedFilePatterns"], textarea[name="params.review.excludedFilePatterns"]');
       const byId = page.locator('#param-review-excludedFilePatterns');
    
       const locators = [byName, byLabel, byPlaceholder, byId];
       for (const loc of locators) {
             try {
                   if (await loc.count() > 0) {
                         const first = loc.first();
                         await first.waitFor({ state: 'visible', timeout: 3000 });
                         return first;
                       }
                 } catch {
                   // continue
                 }
           }
    throw new Error("Could not locate the 'excludedFilePatterns' input field.");
    }

 test.describe('Review workflow excluded file patterns persistence', () => {
       test("excludedFilePatterns value persists after saving and reloading", async ({ page }) => {
             let originalValue = '';
        
             try {
                   // 1. Navigate to workflows configuration page.
                   await page.goto(WORKFLOWS_URL);
                   await page.waitForLoadState('networkidle');
            
                   // 2. Expand the 'review' workflow section.
                   await expandReviewSection(page);
            
                   // 3. Locate the excludedFilePatterns input.
                   let input = await getExcludedFilePatternsInput(page);
            
                   // Capture original value so we can restore it.
                   originalValue = (await input.inputValue().catch(() => '')) ?? '';
            
                   // 4. Clear the field and enter the new value.
                   await input.click();
                   await input.fill('');
                   await input.fill(PATTERN_VALUE);
                   await expect(input).toHaveValue(PATTERN_VALUE);
            
                   // 5. Click the 'Save selection' button.
                   const saveButton = page.getByRole('button', { name: /save selection/i }).first();
                   await saveButton.waitFor({ state: 'visible', timeout: 5000 });
                   await saveButton.click();
            
                   // 6. Wait for the save to complete (network activity settles).
                   await page.waitForLoadState('networkidle');
                   await page.waitForTimeout(500);
            
                   // 7. Reload by navigating again.
                   await page.goto(WORKFLOWS_URL);
                   await page.waitForLoadState('networkidle');
            
                   // 8. Re-expand the review section.
                   await expandReviewSection(page);
            
                   // Assertion: value still contains '*.yaml'.
                   input = await getExcludedFilePatternsInput(page);
                   await expect(input).toHaveValue(PATTERN_VALUE);
                 } finally {
                   // Cleanup: restore the original value so this test is idempotent.
                   try {
                         await page.goto(WORKFLOWS_URL);
                         await page.waitForLoadState('networkidle');
                         await expandReviewSection(page);
                         const input = await getExcludedFilePatternsInput(page);
                         await input.click();
                         await input.fill('');
                         if (originalValue.length > 0) {
                               await input.fill(originalValue);
                             }
                         const saveButton = page.getByRole('button', { name: /save selection/i }).first();
                         await saveButton.waitFor({ state: 'visible', timeout: 5000 });
                         await saveButton.click();
                         await page.waitForLoadState('networkidle');
                         await page.waitForTimeout(500);
                       } catch {
                         // Best-effort cleanup; do not mask original test failure.
                       }
                 }
           });
     });
 