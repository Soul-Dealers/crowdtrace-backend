import { expect, test } from '@playwright/test';
import { logIn, users } from './users';

test('a registered user logs in and sees their own cases', async ({ page }) => {
  await logIn(page, users.user);
  await page.goto('/my/cases');
  await expect(page.getByRole('heading', { name: 'My Cases' })).toBeVisible();
  // The heading renders before the request; a seeded case proves the API answered.
  await expect(page.locator('a[href^="/my/cases/"]').first()).toBeVisible();
});

test('a wrong password keeps the user on the login page with an error', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill(users.user);
  await page.getByLabel('Password', { exact: true }).fill('not-the-password');
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(/\/login/);
});

test('a moderator reaches the review queue', async ({ page }) => {
  await logIn(page, users.moderator);
  await page.goto('/admin/review');
  await expect(page).toHaveURL(/\/admin\/review/);
  // A 403 or 500 still shows the heading; a seeded waiting case proves the queue loaded.
  await expect(page.locator('a[href^="/admin/cases/"]').first()).toBeVisible();
});
