import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  // Identity preflight (see ./e2e/global-setup.ts): when the suite runs
  // against an E2E_BASE_URL it verifies the served app really is this
  // checkout's build (health through the rewrite + the nonce CSP the current
  // code enforces), and refuses to run assertions against a stale or
  // unrelated application.
  globalSetup: "./e2e/global-setup.ts",
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
  // The canonical origin is :3000 (matches the backend's default CORS
  // allow-list and the CI banking-e2e job). For an EPHEMERAL sweep, point
  // E2E_BASE_URL at a second frontend (BACKEND_URL + --port on a free port)
  // and this config follows it; the browser stays same-origin with that
  // frontend, so its proxy keeps every API call same-origin and the backend
  // CORS allow-list never comes into play. When E2E_BASE_URL is set the
  // webServer is skipped - the caller already runs the app there.
  use: { baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000" },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: "npm run start -- --port 3000",
        port: 3000,
        // Deliberately false: the local run must boot the build from THIS
        // checkout and fail loudly if :3000 is already taken (e.g. by an old
        // start-all.ps1 session serving a pre-remediation build) - never
        // silently run the suite against a stale application (section 11.3).
        reuseExistingServer: false
      }
});
