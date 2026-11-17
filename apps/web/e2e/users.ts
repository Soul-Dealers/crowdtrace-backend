import type { Page } from '@playwright/test';

/** Seeded by db/dev-seed/R__dev_seed.sql; every password is Password123!. */
export const users = {
  user: 'krystalorn7363_0@example.com',
  moderator: 'mayraschinner6147_21@example.com',
  admin: 'dewaynebosco0200_102@example.com',
} as const;

export async function logIn(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('Password123!');
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.waitForURL('/');
}
