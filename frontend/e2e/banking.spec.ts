import { expect, test } from "@playwright/test";

/**
 * The one test that proves the whole machine: register through the browser,
 * fund via the simulated rail, move money, see it land - against a real
 * backend and a real PostgreSQL, never mocks. CI boots the stack for this.
 *
 * Flow relies on two deterministic facts: accounts list oldest-first
 * (CHECKING before SAVINGS), and the dashboard renders each account card's
 * full IBAN - the transfers form only ever shows truncated IBANs.
 */
const email = `e2e-${Date.now()}@bank.local`;

test("full money loop in the browser", async ({ page }) => {
  // Register → dashboard.
  await page.goto("/register");
  await page.getByLabel("Full name").fill("E2E User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);

  // Fund the checking account through the deposit dialog.
  await page.getByRole("button", { name: /Simulate deposit/ }).click();
  const amount = page.getByLabel("Amount (USD)");
  await expect(amount).toBeVisible();
  await amount.fill("500");
  await page.getByRole("button", { name: /^Deposit$/ }).click();
  await expect(page.getByText("Deposited $500.00")).toBeVisible();

  // Open a savings account; wait until both account cards are rendered.
  await page.getByRole("button", { name: "Open account" }).click();
  await page.getByLabel("Account type").selectOption("SAVINGS");
  await page.getByRole("button", { name: /^Open$/, exact: true }).click();
  await expect(page.getByText("Account opened.")).toBeVisible();
  const ibans = page.locator("p.mono");
  await expect(ibans).toHaveCount(2);
  const [, savingsIban] = await ibans.allTextContents();

  // Transfer checking → savings.
  await page.goto("/transfers");
  const from = page.getByLabel("From account");
  await expect(from.locator("option")).toHaveCount(2); // form data ready
  await page.getByLabel("Recipient IBAN").fill(savingsIban.trim());
  await page.getByLabel("Amount (USD)").fill("120.00");
  await page.getByLabel("Memo (optional)").fill("e2e rent");
  await page.getByRole("button", { name: /Send transfer/ }).click();

  await expect(page.getByRole("heading", { name: "Transfer posted" })).toBeVisible();
  await expect(page.getByText("$120.00 →")).toBeVisible();

  // The transfer lands on the dashboard feed with its memo.
  await page.goto("/dashboard");
  await expect(page.getByText("e2e rent")).toBeVisible();
});
