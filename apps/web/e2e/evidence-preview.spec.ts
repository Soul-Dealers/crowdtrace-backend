import { expect, test } from '@playwright/test';
import { logIn, users } from './users';

/** A seeded case with a private PDF report. */
const CASE = 1000001;
const SIGNED = 'https://evidence.test/report.pdf';

test('a moderator previews case evidence on the page', async ({ page }) => {
  // The E2E backend keeps files in memory and cannot sign links, so only storage is stood in for.
  await page.route(`**/api/v1/admin/review/cases/${CASE}`, async (route) => {
    const json = await (await route.fetch()).json();
    json.data.evidence = json.data.evidence.map((e: object) => ({ ...e, accessAvailable: true }));
    await route.fulfill({ json });
  });
  const linkRequests: URL[] = [];
  await page.route(`**/api/v1/admin/review/cases/${CASE}/files/*/url*`, (route) => {
    linkRequests.push(new URL(route.request().url()));
    return route.fulfill({
      json: { success: true, data: { url: SIGNED, expiresAt: '2099-01-01T00:00:00Z' } },
    });
  });
  await page.route('https://evidence.test/**', (route) =>
    route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4' }),
  );

  await logIn(page, users.moderator);
  await page.goto(`/admin/cases/${CASE}`);
  await page.getByRole('button', { name: 'Preview' }).first().click();

  const viewer = page.getByRole('dialog', { name: /^Preview: / });
  await expect(viewer.locator(`iframe[src="${SIGNED}"]`)).toBeVisible();
  expect(linkRequests.map((u) => u.searchParams.get('inline'))).toEqual(['true']);

  await page.keyboard.press('Escape');
  await expect(viewer).toHaveCount(0);
});

test('a failed download explains itself inside the viewer', async ({ page }) => {
  await page.route(`**/api/v1/admin/review/cases/${CASE}`, async (route) => {
    const json = await (await route.fetch()).json();
    json.data.evidence = json.data.evidence.map((e: object) => ({ ...e, accessAvailable: true }));
    await route.fulfill({ json });
  });
  await page.route(`**/api/v1/admin/review/cases/${CASE}/files/*/url*`, (route) =>
    new URL(route.request().url()).searchParams.get('inline') === 'true'
      ? route.fulfill({
          json: { success: true, data: { url: SIGNED, expiresAt: '2099-01-01T00:00:00Z' } },
        })
      : route.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          json: { status: 503, detail: 'File downloads are not available' },
        }),
  );
  await page.route('https://evidence.test/**', (route) =>
    route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4' }),
  );

  await logIn(page, users.moderator);
  await page.goto(`/admin/cases/${CASE}`);
  await page.getByRole('button', { name: 'Preview' }).first().click();
  const viewer = page.getByRole('dialog', { name: /^Preview: / });
  await viewer.getByRole('button', { name: 'Download' }).click();
  await expect(viewer.getByText('File downloads are not available')).toBeVisible();
});
