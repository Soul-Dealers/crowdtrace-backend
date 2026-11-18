import { expect, test } from '@playwright/test';
import { logIn, users } from './users';

test('a moderator opens a single verification request from the table', async ({ page }) => {
  await logIn(page, users.moderator);
  await page.goto('/admin/verifications');
  await page.getByRole('radio', { name: 'Approved' }).click();
  const row = page.getByRole('row').nth(1);
  const user = (await row.getByRole('cell').first().innerText()).trim();
  await row.getByRole('button', { name: 'View' }).click();

  const detail = page.getByRole('region', { name: `Verification request from ${user}` });
  await expect(detail.getByText('Review notes')).toBeVisible();
  await expect(detail.getByRole('link', { name: /^https:\/\// })).toHaveAttribute(
    'target',
    '_blank',
  );
  await detail.getByRole('button', { name: 'Close' }).click();
  await expect(detail).toHaveCount(0);
});
