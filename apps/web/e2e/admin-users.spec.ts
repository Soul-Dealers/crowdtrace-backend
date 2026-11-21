import { expect, test, type Browser, type Page } from '@playwright/test';
import { logIn, users } from './users';

/** ACTIVE registered users from the dev seed that no other spec signs in as. */
const PROMOTED = { email: 'hettiefritsch5218_1@example.com', name: 'Hettie Fritsch' };
const DEACTIVATED = { email: 'vernerdubuque2528_3@example.com', name: 'Verner DuBuque' };

async function signedIn(browser: Browser, email: string) {
  const page = await (await browser.newContext()).newPage();
  await logIn(page, email);
  return page;
}

async function act(admin: Page, who: { email: string; name: string }, action: string) {
  await admin.goto('/admin/users');
  await admin.getByLabel('Search users').fill(who.email);
  const row = admin.getByRole('row').filter({ hasText: who.email });
  await row.getByRole('button', { name: action }).click();
  await row.getByRole('button', { name: 'Confirm' }).click();
  return row;
}

test('a super admin promotes a user, who then sees Moderation without logging in again', async ({
  browser,
}) => {
  const user = await signedIn(browser, PROMOTED.email);
  await expect(user.getByRole('link', { name: 'Review Queue' })).toHaveCount(0);

  const admin = await signedIn(browser, users.admin);
  const row = await act(admin, PROMOTED, 'Make moderator');
  await expect(row.getByText('Moderator', { exact: true })).toBeVisible();

  await user.reload();
  await expect(user.getByRole('link', { name: 'Review Queue' })).toBeVisible();
});

test('a deactivated user is signed out on their next request', async ({ browser }) => {
  const user = await signedIn(browser, DEACTIVATED.email);

  const admin = await signedIn(browser, users.admin);
  const row = await act(admin, DEACTIVATED, 'Deactivate');
  await expect(row.getByText('Deactivated', { exact: true })).toBeVisible();

  // The next API call gets a 401; the client drops the session and sends them to log in.
  await user.goto('/my/cases');
  await user.waitForURL('/login?expired=1');
  await expect(user.getByText('Your session expired. Please log in again.')).toBeVisible();
});

test("moderators can't open the Users page", async ({ browser }) => {
  const moderator = await signedIn(browser, users.moderator);
  await expect(moderator.getByRole('link', { name: 'Review Queue' })).toBeVisible();
  await expect(moderator.getByRole('link', { name: 'Users', exact: true })).toHaveCount(0);

  await moderator.goto('/admin/users');
  await expect(moderator.getByText('This page is for Super Admins only.')).toBeVisible();
});
