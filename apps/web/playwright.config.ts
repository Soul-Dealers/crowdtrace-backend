import { defineConfig, devices } from '@playwright/test';

// Boots the real backend (embedded PostgreSQL + dev seed, no Docker) and the web app on their own ports, then
// drives Chromium. Dedicated ports and fresh servers mean E2E never tests a stale or unrelated server that happens
// to be on 8080 or 3000. E2E_REUSE=1 reuses servers already on these ports (only when you know they are current).
const API_PORT = 18080;
const WEB_PORT = 3100;
const reuse = !!process.env.E2E_REUSE;

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0, // A flaky E2E test gets fixed or deleted, never retried until green.
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: { baseURL: `http://localhost:${WEB_PORT}`, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command:
        './mvnw -q -B spring-boot:test-run' +
        ' -Dspring-boot.run.main-class=com.souldealers.crowdtracebackend.TestCrowdtraceBackendApplication' +
        ` -Dspring-boot.run.arguments="--server.port=${API_PORT} --cors.allowed-origins=http://localhost:${WEB_PORT}"`,
      cwd: '../..',
      url: `http://localhost:${API_PORT}/api/public/cases`,
      timeout: 240_000,
      reuseExistingServer: reuse,
    },
    {
      command: process.env.CI ? `pnpm build && pnpm start --port ${WEB_PORT}` : `pnpm dev --port ${WEB_PORT}`,
      env: { NEXT_PUBLIC_API_URL: `http://localhost:${API_PORT}` },
      url: `http://localhost:${WEB_PORT}`,
      timeout: 180_000,
      reuseExistingServer: reuse,
    },
  ],
});
