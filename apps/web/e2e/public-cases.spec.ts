import { expect, test } from '@playwright/test';

test('a visitor finds a missing person and opens their case', async ({ page }) => {
  await page.goto('/cases');
  const firstCase = page.locator('a[href^="/cases/"]').first();
  await expect(firstCase).toBeVisible();
  const href = await firstCase.getAttribute('href');

  await firstCase.click();

  await expect(page).toHaveURL(href!);
  await expect(page.getByRole('heading', { level: 1 })).not.toBeEmpty();
});

test('the home page lists trending cases from the API', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Top Trending Cases' })).toBeVisible();
  await expect(page.locator('a[href^="/cases/"]').first()).toBeVisible();
});
