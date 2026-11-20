import { expect, test } from '@playwright/test';
import { logIn, users } from './users';

// Seeded cases reported by the moderator account.
const APPROVED = 1000043;
const IN_REVIEW = 1000044;

test('a reporter goes from their approved case to its public discussion', async ({ page }) => {
  await logIn(page, users.moderator);
  await page.goto(`/my/cases/${APPROVED}`);
  await page.getByRole('link', { name: 'Go to case page' }).click();

  await expect(page).toHaveURL(`/cases/${APPROVED}?tab=discussion`);
  await expect(page.getByRole('tab', { name: /Discussion/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('Share what you saw')).toBeVisible();
});

test('a case that is not public yet has no case page link', async ({ page }) => {
  await logIn(page, users.moderator);
  await page.goto(`/my/cases/${IN_REVIEW}`);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Go to case page' })).toHaveCount(0);
});
