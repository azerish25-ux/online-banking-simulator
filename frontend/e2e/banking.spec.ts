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
const emailHeld = `e2e-held-${Date.now()}@bank.local`;

test("review-threshold transfer is held for review, never reported as posted", async ({ page }) => {
  // Register → dashboard.
  await page.goto("/register");
  await page.getByLabel("Full name").fill("E2E Held User");
  await page.getByLabel("Email").fill(emailHeld);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);

  // Open a savings account as the recipient, then fund checking past the
  // $10,000 review threshold.
  await page.getByRole("button", { name: "Open account" }).click();
  await page.getByLabel("Account type").selectOption("SAVINGS");
  await page.getByRole("button", { name: /^Open$/, exact: true }).click();
  await expect(page.getByText("Account opened.")).toBeVisible();
  // The toast can land before the second account card re-renders - wait for
  // both cards, then read the savings IBAN (same discipline as the money-loop
  // test below; reading p.mono[1] too early crashes on undefined).
  const ibans = page.locator("p.mono");
  await expect(ibans).toHaveCount(2);
  const savingsIban = (await ibans.allTextContents())[1].trim();

  await page.getByRole("button", { name: /Deposit funds/ }).click();
  await page.getByLabel("Amount (USD)").fill("10000");
  await page.getByRole("button", { name: /^Deposit$/ }).click();
  await expect(page.getByText("Deposited $10,000.00")).toBeVisible();

  // A $10,000+ transfer must NOT toast "Transfer posted" - it is held until
  // an operator approves it (the regression: a bare `status` reference
  // resolved to window.status and always reported the transfer as posted).
  await page.goto("/transfers");
  await page.getByLabel("Recipient IBAN").fill(savingsIban);
  await page.getByLabel("Amount (USD)").fill("10000.00");
  // F11: submit opens REVIEW; Confirm & send actually submits.
  await page.getByRole("button", { name: /Review transfer/ }).click();
  await expect(page.getByRole("region", { name: "Review your transfer" })).toBeVisible();
  await page.getByRole("button", { name: "Confirm & send" }).click();

  await expect(page.getByRole("heading", { name: "Transfer submitted for review" })).toBeVisible();
  await expect(page.getByText(/sent once an operator approves it/)).toBeVisible();
  await expect(page.getByText("$10,000.00 →")).toBeVisible();

  // The durable receipt route shows the authoritative HELD state.
  await page.getByRole("link", { name: /Open permanent receipt/ }).click();
  await expect(page).toHaveURL(/\/transfers\/receipt\//);
  await expect(page.getByRole("heading", { name: "Transfer receipt" })).toBeVisible();
  await expect(page.getByText(/awaiting operator review/)).toBeVisible();
  await expect(page.getByText(/Not yet - no money has moved/)).toBeVisible();
});

test("full money loop in the browser", async ({ page }) => {
  // Register → dashboard.
  await page.goto("/register");
  await page.getByLabel("Full name").fill("E2E User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);

  // Fund the checking account through the deposit dialog.
  await page.getByRole("button", { name: /Deposit funds/ }).click();
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
  // F11: submit opens REVIEW; Confirm & send actually submits.
  await page.getByRole("button", { name: /Review transfer/ }).click();
  await expect(page.getByRole("region", { name: "Review your transfer" })).toBeVisible();
  await page.getByRole("button", { name: "Confirm & send" }).click();

  await expect(page.getByRole("heading", { name: "Transfer posted" })).toBeVisible();
  await expect(page.getByText("$120.00 →")).toBeVisible();
  const receiptHref = await page
    .getByRole("link", { name: /Open permanent receipt/ })
    .getAttribute("href");
  expect(receiptHref).toMatch(/\/transfers\/receipt\//);

  // The transfer lands on the dashboard feed with its memo.
  await page.goto("/dashboard");
  await expect(page.getByText("e2e rent")).toBeVisible();

  // The durable receipt route answers later (bookmark reload) with the
  // authoritative POSTED state and posting time - never component state.
  await page.goto(receiptHref as string);
  await expect(page.getByRole("heading", { name: "Transfer receipt" })).toBeVisible();
  await expect(page.getByText("Settled - the money moved")).toBeVisible();
  // The status badge (uppercase-styled span next to the title) - the first
  // POSTED match; the receipt's "Posted" field label also renders uppercase.
  await expect(page.locator("main").getByText("POSTED", { exact: true }).first()).toBeVisible();
});

test("an over-cap deposit rejection renders inline in the dialog, not as a corner toast", async ({ page }) => {
  // Regression for the error-consistency pass: a server rejection used to
  // surface only as a corner toast while the dialog stayed open with the bad
  // amount still in the field. A bank keeps the rejection beside the field.
  await page.goto("/register");
  await page.getByLabel("Full name").fill("E2E Cap User");
  await page.getByLabel("Email").fill(`e2e-cap-${Date.now()}@bank.local`);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);

  // Ask for more than the $100,000 per-deposit cap.
  await page.getByRole("button", { name: /Deposit funds/ }).click();
  await page.getByLabel("Amount (USD)").fill("100000.01");
  await page.getByRole("button", { name: /^Deposit$/ }).click();

  // The rejection appears under the amount field (role=alert), the dialog is
  // still open, and nothing of it leaks into a corner toast.
  await expect(page.getByRole("dialog").getByRole("alert")).toHaveText(
    "Deposit exceeds the per-transaction limit"
  );
  await expect(page.getByRole("heading", { name: "Deposit funds" })).toBeVisible();
  await expect(
    page.getByRole("status").filter({ hasText: "Deposit exceeds the per-transaction limit" })
  ).toHaveCount(0);

  // Fixing the amount recovers: the inline error clears and the deposit posts.
  await page.getByLabel("Amount (USD)").fill("50");
  await page.getByRole("button", { name: /^Deposit$/ }).click();
  await expect(page.getByText("Deposited $50.00 to CHECKING")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Deposit funds" })).toHaveCount(0);
});

test("statement exports download through the proxy", async ({ page }) => {
  // Regression: statement URLs carried the /backend prefix AND downloadAuthed
  // prepended it again, so every CSV/PDF export hit /backend/backend/... and
  // 404'd. This walks the real rewrite proxy with a real account.
  await page.goto("/register");
  await page.getByLabel("Full name").fill("E2E Export User");
  await page.getByLabel("Email").fill(`e2e-export-${Date.now()}@bank.local`);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);

  // One deposit so the statement has a row.
  await page.getByRole("button", { name: /Deposit funds/ }).click();
  await page.getByLabel("Amount (USD)").fill("75");
  await page.getByRole("button", { name: /^Deposit$/ }).click();
  await expect(page.getByText(/Deposited \$75\.00/)).toBeVisible();

  // Activity defaults to the first account; exports enable once it loads.
  await page.goto("/activity");
  const csvBtn = page.getByRole("button", { name: "CSV" });
  await expect(csvBtn).toBeEnabled({ timeout: 10_000 });
  const csvDownload = page.waitForEvent("download");
  await csvBtn.click();
  expect((await csvDownload).suggestedFilename()).toMatch(/\.csv$/);

  const pdfBtn = page.getByRole("button", { name: "PDF" });
  const pdfDownload = page.waitForEvent("download");
  await pdfBtn.click();
  expect((await pdfDownload).suggestedFilename()).toMatch(/\.pdf$/);
});
