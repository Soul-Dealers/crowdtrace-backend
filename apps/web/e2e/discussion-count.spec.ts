import { expect, test } from '@playwright/test';
import { logIn, users } from './users';

// A case no other spec comments on: moderation.spec posts on 1000004 in a parallel worker, which would bump the count.
const CASE = '/cases/1000005';

test('the Discussion tab shows how many top-level comments the case has', async ({ page }) => {
  await logIn(page, users.user);
  await page.goto(CASE);
  const badge = page.getByRole('tab', { name: /^Discussion/ }).getByTestId('comment-count');
  await expect(badge).toHaveText(/^\d+$/);
  const before = Number(await badge.innerText());

  await page.getByRole('tab', { name: /^Discussion/ }).click();
  await page.getByLabel('Share what you saw').fill(`Count check ${Date.now()}`);
  await page.getByRole('button', { name: 'Post', exact: true }).click();

  await expect(badge).toHaveText(String(before + 1));
  await expect(badge).toHaveAccessibleName(`${before + 1} comments`);
});
