import { expect, test, type Page } from "@playwright/test";
import { auditTextContrast } from "./contrast";

/**
 * Accessibility proofs against the real product.
 *
 * These tests are hermetic: they seed their own data through the backend API
 * before running, so they pass on a fresh CI database (where the seeded demo
 * user has no accounts yet) and stay idempotent against a long-lived local
 * demo database. No mocks anywhere: same real stack as production.
 */
/**
 * Seed target (N02 identity rule): the seed must write where the BROWSER
 * actually talks: the same frontend proxy the specs exercise: never a
 * hard-coded backend that can drift from the app under test. The proxy path
 * /backend/* rewrites to the backend's /api/*, so an ephemeral sweep
 * (E2E_BASE_URL set) seeds its own ephemeral backend and a default run seeds
 * whatever the local server proxies to. API_BASE stays honored for callers
 * that genuinely need a direct backend.
 */
const BASE =
  process.env.API_BASE ?? (process.env.E2E_BASE_URL ?? "http://localhost:3000") + "/backend";
const ALICE = "alice@bank.local";

/** Login as the demo user; register on first contact with a fresh DB. */
async function loginToken(email: string): Promise<string> {
  const login = await fetch(BASE + "/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password: "secret123" })
  });
  if (login.ok) return (await login.json()).accessToken;

  const register = await fetch(BASE + "/v1/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password: "secret123", fullName: "A11y Seed" })
  });
  if (!register.ok) {
    throw new Error(`could not login or register ${email}: ${login.status}/${register.status}`);
  }
  return (await register.json()).accessToken;
}

/** Ensure the user has a funded account so tables and dialogs render. */
async function seedAccountActivity(token: string): Promise<void> {
  const jsonHeaders = {
    Authorization: "Bearer " + token,
    "Content-Type": "application/json"
  };
  let accounts = (await (
    await fetch(BASE + "/v1/accounts", { headers: { Authorization: "Bearer " + token } })
  ).json()) as { id: string }[];

  if (accounts.length === 0) {
    await fetch(BASE + "/v1/accounts", {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify({ type: "CHECKING" })
    });
    accounts = (await (
      await fetch(BASE + "/v1/accounts", { headers: { Authorization: "Bearer " + token } })
    ).json()) as { id: string }[];
  }

  // Deposits require an Idempotency-Key header: without it the seed
  // would 400 and the dashboard feed would stay empty for the whole file.
  const deposit = await fetch(BASE + "/v1/accounts/" + accounts[0].id + "/deposit", {
    method: "POST",
    headers: { ...jsonHeaders, "Idempotency-Key": crypto.randomUUID() },
    body: JSON.stringify({ amount: "250" })
  });
  if (!deposit.ok) {
    throw new Error(`seed deposit failed: ${deposit.status} ${await deposit.text()}`);
  }
}

test.beforeAll(async () => {
  await seedAccountActivity(await loginToken(ALICE));
});

test("dashboard tables use column scopes", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(ALICE);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /^Log in$/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  const headers = page.locator("table th[scope='col']");
  await expect(headers.first()).toBeVisible();
});

test("escape closes the deposit dialog", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(ALICE);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /^Log in$/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.getByRole("button", { name: /Deposit funds/ }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();
});

test("transfer form is keyboard reachable with labelled fields", async ({ page }) => {
  await page.goto("/transfers");
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel("Email").fill("bob@bank.local");
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /^Log in$/ }).click();
  // Sign-in continues to the guarded destination the proxy carried in
  // ?next= - Bob lands on the transfers form itself, not the dashboard.
  await expect(page).toHaveURL(/\/transfers/);
  await page.goto("/transfers");
  await page.getByLabel("Recipient IBAN").focus();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Amount (USD)")).toBeFocused();
});

async function loginAsAlice(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(ALICE);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /^Log in$/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.locator("main .nums").first().waitFor({ timeout: 10_000 });
}

/**
 * WCAG 2.2 AA text contrast from live computed styles (see e2e/contrast.ts):
 * every visible text node in a surface is measured at its rendered color
 * against its effective background, so the design claim has an automated
 * witness instead of a screenshot.
 */
test("public pages meet WCAG 2.2 AA text contrast", async ({ page }) => {
  const failures: string[] = [];
  const counts: string[] = [];
  const surfaces: Array<[label: string, path: string, minNodes: number]> = [
    ["landing", "/", 4],
    ["login", "/login", 4],
    ["about", "/about", 5]
  ];
  for (const [label, path, minNodes] of surfaces) {
    await page.goto(path);
    await page.getByRole("heading", { level: 1 }).first().waitFor({ timeout: 10_000 });
    await page.waitForTimeout(300); // entrance/hero animations settle
    const r = await auditTextContrast(page);
    counts.push(label + ": " + r.sampled + " text nodes");
    if (r.sampled < minNodes) {
      failures.push(label + ": only " + r.sampled + " text nodes sampled (needs >= " + minNodes + "): did the page render?");
    }
    failures.push(...r.failures.map((f) => "[" + label + "] " + f));
  }
  expect(
    failures,
    counts.join("\n") + (failures.length ? "\n\n" + failures.join("\n") : "")
  ).toEqual([]);
});

test("authed surfaces and overlays meet WCAG 2.2 AA text contrast", async ({ page }) => {
  const failures: string[] = [];
  const counts: string[] = [];
  await loginAsAlice(page);

  async function audit(label: string, scope?: string, minNodes = 1) {
    const r = await auditTextContrast(page, scope);
    counts.push(label + ": " + r.sampled + " text nodes");
    if (r.sampled < minNodes) {
      failures.push(label + ": only " + r.sampled + " text nodes sampled (needs >= " + minNodes + "): did the surface render?");
    }
    failures.push(...r.failures.map((f) => "[" + label + "] " + f));
  }

  // Dashboard with the feed rendered (the seeded deposits guarantee rows).
  await page.locator("main table tbody tr").first().waitFor({ timeout: 10_000 });
  await audit("dashboard", undefined, 10);

  // Deposit dialog, audited in isolation from the page behind the scrim.
  await page.getByRole("button", { name: /Deposit funds/ }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.waitForTimeout(300);
  await audit("deposit dialog", '[role="dialog"]', 3);
  await page.keyboard.press("Escape");

  // Account detail (first account card from the dashboard).
  const href = await page.locator('main a[href*="/accounts/"]').first().getAttribute("href");
  await page.goto(href as string);
  await page.locator("main table tbody tr").first().waitFor({ timeout: 10_000 });
  await audit("account detail", undefined, 6);

  await page.goto("/transfers");
  await page.getByRole("heading", { name: "Send money" }).waitFor();
  await page.waitForTimeout(400);
  await audit("transfers", undefined, 6);

  await page.goto("/activity");
  await page.locator("main table tbody tr").first().waitFor({ timeout: 10_000 });
  await audit("activity", undefined, 6);

  // A live toast: deposit once and audit the status region while visible.
  await page.goto("/dashboard");
  await page.getByRole("button", { name: /Deposit funds/ }).click();
  await page.getByLabel("Amount (USD)").fill("1");
  await page.getByRole("button", { name: /^Deposit$/ }).click();
  await expect(page.getByRole("status").first()).toBeVisible({ timeout: 8_000 });
  await page.waitForTimeout(400); // slide-in settles before measuring
  await audit("toast", '[role="status"]', 1);

  expect(
    failures,
    counts.join("\n") + (failures.length ? "\n\n" + failures.join("\n") : "")
  ).toEqual([]);
});

test("mobile nav at 375px meets WCAG 2.2 AA text contrast", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 720 });
  await loginAsAlice(page);
  // Below the md breakpoint the desktop rail is display:none and navigation
  // lives in the burger drawer: open it before auditing its links.
  const menu = page.getByRole("button", { name: "Open navigation menu" });
  await expect(menu).toBeVisible();
  await menu.click();
  const drawer = page.getByRole("dialog", { name: "Navigation menu" });
  await expect(drawer).toBeVisible();
  const nav = drawer.getByRole("navigation", { name: "Primary tasks" });
  await nav.locator("a").first().waitFor();
  const r = await auditTextContrast(page, '[role="dialog"][aria-label="Navigation menu"]');
  expect(
    r.failures,
    "mobile nav (" + r.sampled + " text nodes)" + (r.failures.length ? "\n" + r.failures.join("\n") : "")
  ).toEqual([]);
  expect(r.sampled).toBeGreaterThanOrEqual(3);
});
