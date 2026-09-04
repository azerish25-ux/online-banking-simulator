import { expect, test } from "@playwright/test";

/**
 * Proves the session survives a real access-token expiry through the silent
 * refresh path - the regression the refresh-cookie `Path` bug caused (cookie
 * scoped to `/api/v1/auth` while the browser only calls `/backend/v1/auth/*`,
 * so every session died at the first expiry).
 *
 * The backend must boot with a seconds-scale TTL or the test would wait
 * 15 minutes. CI's banking-e2e job runs the jar with APP_JWT_ACCESS_SECONDS=3
 * and sets E2E_ACCESS_TTL_SECONDS to match; anywhere else this spec skips.
 */
const ttlSeconds = Number(process.env.E2E_ACCESS_TTL_SECONDS || 0);
const email = `session-${Date.now()}@bank.local`;

test("silent refresh keeps the session alive past access-token expiry", async ({ page }) => {
  test.skip(ttlSeconds <= 0, "requires a short-TTL backend (APP_JWT_ACCESS_SECONDS + E2E_ACCESS_TTL_SECONDS)");

  // Register → dashboard, and wait until the first authed data has loaded so
  // the idle wait below starts from a settled session.
  await page.goto("/register");
  await page.getByLabel("Full name").fill("Session E2E");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page.getByText(/across accounts|Overview|Simulate deposit/).first()).toBeVisible();

  // Idle past the token lifetime: the next authed request is guaranteed to
  // arrive with an expired access token and must be repaired by one silent
  // refresh + retry instead of a redirect to login.
  await page.waitForTimeout((ttlSeconds + 1.5) * 1000);

  // Full page load = fresh JS = no stale query cache: the transfers page has
  // to fetch accounts/beneficiaries over the network with the expired token.
  await page.goto("/transfers");
  await expect(page.getByRole("heading", { name: "Send money" })).toBeVisible({ timeout: 15_000 });
  await expect(page).not.toHaveURL(/\/login/);
});
