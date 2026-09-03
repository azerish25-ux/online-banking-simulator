import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
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
