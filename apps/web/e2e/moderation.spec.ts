import { expect, test, type Browser, type Page } from '@playwright/test';
import { logIn, users } from './users';

/** An approved seeded case. */
const CASE = '/cases/1000004';

async function openDiscussion(page: Page) {
  await page.goto(CASE);
  await page.getByRole('tab', { name: 'Discussion' }).click();
}

async function signedIn(browser: Browser, email: string) {
  const page = await (await browser.newContext()).newPage();
  await logIn(page, email);
  return page;
}

test('a reported comment removed by a moderator shows as a placeholder and tells the reporter', async ({
  browser,
}) => {
  const text = `Rude remark ${Date.now()}`;

  const author = await signedIn(browser, users.admin);
  await openDiscussion(author);
  await author.getByLabel('Share what you saw').fill(text);
  await author.getByRole('button', { name: 'Post', exact: true }).click();
  await expect(author.getByText(text)).toBeVisible();

  const reporter = await signedIn(browser, users.user);
  await openDiscussion(reporter);
  const comment = reporter.getByRole('listitem').filter({ hasText: text });
  await comment.getByRole('button', { name: 'Report' }).click();
  await comment.getByLabel('Harassment').check();
  await comment.getByRole('button', { name: 'Submit report' }).click();
  await expect(comment.getByRole('status')).toHaveText(
    'Thanks. A moderator will review this comment.',
  );

  const moderator = await signedIn(browser, users.moderator);
  await moderator.goto('/admin/moderation/comments');
  const row = moderator.getByRole('listitem').filter({ hasText: text });
  await row.getByRole('button', { name: 'Review' }).click();
  await row.getByLabel('Notes').fill('Insults another user');
  await row.getByRole('button', { name: 'Remove comment' }).click();
  await expect(moderator.getByText(text)).toHaveCount(0);

  await openDiscussion(reporter);
  await expect(reporter.getByText('Removed by a moderator').first()).toBeVisible();
  await expect(reporter.getByText(text)).toHaveCount(0);

  await reporter.goto('/notifications');
  await expect(reporter.getByText('A comment you reported was removed').first()).toBeVisible();
});
