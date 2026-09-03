import { expect, test } from "@playwright/test";

/**
 * Accessibility proofs against the real product.
 *
 * These tests are hermetic: they seed their own data through the backend API
 * before running, so they pass on a fresh CI database (where the seeded demo
 * user has no accounts yet) and stay idempotent against a long-lived local
 * demo database. No mocks anywhere - same real stack as production.
 */
const BASE = process.env.API_BASE ?? "http://localhost:8080/api";
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

  await fetch(BASE + "/v1/accounts/" + accounts[0].id + "/deposit", {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ amount: "250" })
  });
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
  await page.getByRole("button", { name: /Simulate deposit/ }).click();
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
  await expect(page).toHaveURL(/\/dashboard/);
  await page.goto("/transfers");
  await page.getByLabel("Recipient IBAN").focus();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Amount (USD)")).toBeFocused();
});
