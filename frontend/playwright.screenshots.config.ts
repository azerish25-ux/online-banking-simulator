import { defineConfig } from "@playwright/test";

/** Config for the README screenshot capture (run explicitly; not part of the
 *  default suite or CI):
 *    npx playwright test --config=playwright.screenshots.config.ts
 *  Requires the live stack from start-all.ps1 + seed-demo.ps1. */
export default defineConfig({
  testDir: "./e2e-screenshots",
  timeout: 30_000,
  use: { baseURL: "http://localhost:3000" },
  webServer: {
    command: "npm run start -- --port 3000",
    port: 3000,
    reuseExistingServer: true
  }
});
