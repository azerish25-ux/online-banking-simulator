import { expect, test, type Page } from "@playwright/test";

/**
 * Real-tab witness. The unit suite fakes the BroadcastChannel bus; this
 * spec proves the Web Lock refresh + `simulator:auth` peer broadcast with
 * TWO real tabs in one browser context (shared origin :3111 behind the
 * ephemeral stack, same partition):
 *
 *   1. Two tabs hold the same session (register in tab A, open tab B).
 *   2. Idle past the access-token lifetime (backend boots with
 *      APP_JWT_ACCESS_SECONDS=3; E2E_ACCESS_TTL_SECONDS mirrors it).
 *   3. A full reload in EACH tab must land authed: the expired token is
 *      repaired under the Web Lock exactly once, never a redirect to login
 *      and never an infinite refresh loop.
 *   4. Logging out in tab A must evict tab B too: the logout broadcast
 *      routes the peer to /login without the peer touching anything.
 *
 * Like session.spec.ts, this needs a short-TTL backend and skips otherwise.
 */
const ttlSeconds = Number(process.env.E2E_ACCESS_TTL_SECONDS || 0);

async function register(page: Page, email: string): Promise<void> {
  await page.goto("/register");
  await page.getByLabel("Full name").fill("Two Tab E2E User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.locator("main p.mono").first().waitFor({ timeout: 10_000 });
}

test("two real tabs share one session: concurrent refresh after expiry, peer logout eviction", async ({
  context,
  page
}) => {
  test.skip(ttlSeconds <= 0, "requires a short-TTL backend (APP_JWT_ACCESS_SECONDS + E2E_ACCESS_TTL_SECONDS)");

  const email = `two-tab-${Date.now()}@bank.local`;
  await register(page, email);

  // Tab B joins the same session from the same context.
  const tabB = await context.newPage();
  await tabB.goto("/dashboard");
  await expect(tabB).toHaveURL(/\/dashboard/);
  await tabB.locator("main p.mono").first().waitFor({ timeout: 10_000 });

  // Idle past the access-token lifetime in both tabs.
  await page.waitForTimeout((ttlSeconds + 1.5) * 1000);

  // Tab B: a full page load must be repaired by the silent refresh path: // no redirect to login (the exact regression this guarded against).
  await tabB.goto("/transfers");
  await expect(tabB.getByRole("heading", { name: "Send money" })).toBeVisible({ timeout: 15_000 });
  await expect(tabB).not.toHaveURL(/\/login/);

  // Tab A: reload its own data concurrently: both tabs live past the expiry.
  await page.goto("/dashboard");
  await page.locator("main p.mono").first().waitFor({ timeout: 15_000 });
  await expect(page).not.toHaveURL(/\/login/);

  // Log out in tab A: the logout broadcast must route tab B to login without
  // tab B doing anything (peer eviction, not just cookie expiry).
  await page.getByRole("button", { name: /Log out/ }).click();
  await expect(page).toHaveURL(/\/login/);
  await expect(tabB).toHaveURL(/\/login/, { timeout: 15_000 });
});
