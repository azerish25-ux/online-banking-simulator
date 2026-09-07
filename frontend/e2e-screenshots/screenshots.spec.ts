import { expect, test } from "@playwright/test";
import * as fs from "fs";
import * as path from "path";

/**
 * Captures the README screenshots from the real running product - the exact
 * stack that `start-all.ps1` + `seed-demo.ps1` produce. Nothing here is
 * mocked or staged: the flagged wire in the admin queue is a genuine
 * threshold-triggered hold created by the seed, and the receipt is a real
 * transfer posted through the UI.
 *
 * Run explicitly (kept out of the default suite and CI on purpose):
 *   npx playwright test --config=playwright.screenshots.config.ts
 */
const API = "http://localhost:8080/api/v1";
const OUT = path.join(__dirname, "..", "..", "docs", "screenshots");

async function shot(page: import("@playwright/test").Page, name: string, fullPage = false) {
  fs.mkdirSync(OUT, { recursive: true });
  await page.screenshot({ path: path.join(OUT, name), fullPage });
}

test("landing, dashboard, and transfer receipt", async ({ page }) => {
  // Resolve Bob's IBAN through the API so the demo transfer always has a
  // valid recipient regardless of which IBANs this database generated.
  const login = await page.request.post(API + "/auth/login", {
    data: { email: "bob@bank.local", password: "secret123" }
  });
  const bob = await login.json();
  const accsRes = await page.request.get(API + "/accounts", {
    headers: { Authorization: "Bearer " + bob.accessToken }
  });
  const bobIban = ((await accsRes.json()) as { iban: string }[])[0].iban;

  // 1 - Landing hero with live public stats (logged out).
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Online Banking Simulator" })).toBeVisible();
  await page.waitForTimeout(1200); // hero stats fetch
  await shot(page, "landing.png");

  // 2 - Customer dashboard for the seeded user.
  await page.goto("/login");
  await page.getByLabel("Email").fill("alice@bank.local");
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /^Log in$/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page.getByText("Available funds")).toBeVisible();
  await shot(page, "dashboard.png", true);

  // 3 - Transfer flow: post a real transfer, capture the receipt. Register
  // auto-opens exactly one checking account and the seed adds none, so the
  // source select holds one option - wait for it to exist rather than
  // asserting a magic count.
  await page.goto("/transfers");
  const from = page.getByLabel("From account");
  await expect.poll(() => from.locator("option").count(), { timeout: 15_000 }).toBeGreaterThan(0);
  await page.getByLabel("Recipient IBAN").fill(bobIban);
  await page.getByLabel("Amount (USD)").fill("250.00");
  await page.getByLabel("Memo (optional)").fill("September rent");
  await page.getByRole("button", { name: /Send transfer/ }).click();
  await expect(page.getByRole("heading", { name: "Transfer posted" })).toBeVisible();
  await shot(page, "transfer-receipt.png");
});

test("admin review queue", async ({ browser }) => {
  const page = await (await browser.newContext()).newPage();
  await page.goto("/login");
  await page.getByLabel("Email").fill("admin@bank.local");
  await page.getByLabel("Password", { exact: true }).fill("change-me-admin-123");
  await page.getByRole("button", { name: /^Log in$/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();
  // The seed's $12,500 wire crossed the 10,000 threshold and waits unreviewed.
  // The queue rows show counterpart IBANs + amount (not the memo), so anchor
  // on the amount.
  await expect(page.getByText("$12,500.00").first()).toBeVisible();
  await shot(page, "admin-review-queue.png", true);
});
