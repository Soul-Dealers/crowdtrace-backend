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
  const context = row.getByRole('link', { name: 'See it in the discussion' });
  await expect(context).toHaveAttribute('href', `${CASE}?tab=discussion`);
  await expect(context).toHaveAttribute('target', '_blank');
  await row.getByLabel('Notes').fill('Insults another user');
  await row.getByRole('button', { name: 'Remove comment' }).click();
  await expect(moderator.getByText(text)).toHaveCount(0);

  await openDiscussion(reporter);
  await expect(reporter.getByText('Removed by a moderator').first()).toBeVisible();
  await expect(reporter.getByText(text)).toHaveCount(0);

  await reporter.goto('/notifications');
  await expect(reporter.getByText('A comment you reported was removed').first()).toBeVisible();
});

test('a moderator whose comment was already handled sees why it vanished', async ({ browser }) => {
  const text = `Second remark ${Date.now()}`;
  const author = await signedIn(browser, users.user);
  await openDiscussion(author);
  await author.getByLabel('Share what you saw').fill(text);
  await author.getByRole('button', { name: 'Post', exact: true }).click();
  await expect(author.getByText(text)).toBeVisible();

  const reporter = await signedIn(browser, users.admin);
  await openDiscussion(reporter);
  const comment = reporter.getByRole('listitem').filter({ hasText: text });
  await comment.getByRole('button', { name: 'Report' }).click();
  await comment.getByLabel('Spam').check();
  await comment.getByRole('button', { name: 'Submit report' }).click();
  await expect(comment.getByRole('status')).toBeVisible();

  const slow = await signedIn(browser, users.moderator);
  await slow.goto('/admin/moderation/comments');
  const row = slow.getByRole('listitem').filter({ hasText: text });
  await row.getByRole('button', { name: 'Review' }).click();
  await expect(row.getByRole('button', { name: 'Keep comment' })).toBeVisible();

  // The super admin (also a moderator, and not the author) keeps it first.
  const fast = reporter;
  await fast.goto('/admin/moderation/comments');
  const fastRow = fast.getByRole('listitem').filter({ hasText: text });
  await fastRow.getByRole('button', { name: 'Review' }).click();
  await fastRow.getByRole('button', { name: 'Keep comment' }).click();
  await expect(fast.getByText(text)).toHaveCount(0);

  await row.getByRole('button', { name: 'Keep comment' }).click();
  await expect(slow.getByText('Already handled by another moderator')).toBeVisible();
  await expect(slow.getByText(text)).toHaveCount(0);
});
