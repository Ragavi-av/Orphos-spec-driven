import { test, expect } from '@playwright/test';

const baseURL = process.env.BASE_URL ?? 'http://localhost:3000';

test.describe('TASK_KEY - Playwright coverage', () => {
  test('Sample scenario title', async ({ page, request }) => {
    // covers: AC1
    await page.goto(baseURL + '/');
    await expect(page).toBeVisible();
  });
});
