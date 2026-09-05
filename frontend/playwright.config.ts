import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  // Auth round-trips (register/login → dashboard) can exceed Playwright's
  // 5s expect default on a contended dev machine; the suite's own waits
  // already use 10s, so raise the default to match instead of racing.
  expect: { timeout: 10_000 },
  // The TOTP round-trip logout click is load-sensitive on GitHub runners: on
  // a contended first attempt the shell's re-render churn makes the click
  // land on <html> (the element detaches and re-resolves) for the whole
  // timeout. It passes locally and on every rerun - a slow-runner artifact,
  // not an app bug - so CI retries the test once instead of failing the job.
  retries: process.env.CI ? 1 : 0,
  // :3000 is the single canonical origin - it matches the backend's default
  // CORS allow-list (app.cors.allowed-origins) and the CI banking-e2e job.
  // Serving the e2e app from any other port gets 403s on proxied API calls.
  use: { baseURL: "http://localhost:3000" },
  webServer: {
    command: "npm run start -- --port 3000",
    port: 3000,
    reuseExistingServer: true
  }
});
