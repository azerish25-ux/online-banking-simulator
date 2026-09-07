import { expect, test, type Page } from "@playwright/test";

/**
 * The operator console, end to end: a customer submits a review-threshold
 * transfer (HELD - no money moves), an operator approves one and declines
 * another, and both outcomes are reflected where a human would look: the
 * review queue clears, the customer's feed shows POSTED / CANCELLED with the
 * balances moving only on approval, and the audit log records the operator's
 * decision. The only fixed identity is the seeded operator - customers are
 * self-registered fresh users, like the rest of the suite.
 *
 * The queue can hold rows from other activity (parallel specs, earlier runs),
 * so each row is selected by its masked sender→recipient tails plus the
 * amount, never by position or by assuming the queue is empty. Deposits use
 * distinct amounts so each success toast is uniquely matchable, and balance
 * assertions retry until the post-deposit refetch lands (a read right after
 * the toast can still see the pre-deposit balance).
 */

const ADMIN_EMAIL = process.env.APP_ADMIN_EMAIL ?? "admin@bank.local";
const ADMIN_PASSWORD = process.env.APP_ADMIN_PASSWORD ?? "change-me-admin-123";
const tag = Date.now();

const usdWhole = (n: number) => "$" + n.toLocaleString("en-US") + ".00";

async function register(page: Page, name: string, email: string): Promise<void> {
  await page.goto("/register");
  await page.getByLabel("Full name").fill(name);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.locator("main p.mono").first().waitFor({ timeout: 10_000 });
}

/** Deposit whole dollars into the default (checking) destination. The exact
 *  toast text pins the amount, so a stale toast from an earlier deposit can
 *  never satisfy the wait. */
async function deposit(page: Page, amount: number): Promise<void> {
  await page.getByRole("button", { name: /Deposit funds/ }).click();
  await page.getByLabel("Amount (USD)").fill(String(amount));
  await page.getByRole("button", { name: /^Deposit$/ }).click();
  await expect(page.getByText("Deposited " + usdWhole(amount) + " to CHECKING")).toBeVisible({
    timeout: 10_000
  });
}

async function logout(page: Page): Promise<void> {
  await page.goto("/dashboard");
  await page.getByRole("button", { name: /Log out/ }).click();
  await expect(page).toHaveURL(/\/login/);
}

async function login(page: Page, email: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: /^Log in$/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.locator("main p.mono").first().waitFor({ timeout: 10_000 });
}

/** The operator has no customer accounts, so the dashboard never renders
 *  account cards - go straight to the console and wait for its heading. */
async function loginOperator(page: Page): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(ADMIN_EMAIL);
  await page.getByLabel("Password", { exact: true }).fill(ADMIN_PASSWORD);
  await page.getByRole("button", { name: /^Log in$/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.goto("/admin");
  await page.getByRole("heading", { name: "Operations" }).waitFor({ timeout: 10_000 });
}

/** The dashboard's account-card balances: [0] is the hero net figure, then
 *  one entry per account card in creation order (checking, savings here). */
function balances(page: Page) {
  return page.locator("main p.tabular-nums");
}

/** The review-queue row for exactly one HELD transfer: both tails plus the
 *  operator buttons (audit-log rows share the page and never carry buttons). */
function queueRowFor(page: Page, fromTail: string, toTail: string) {
  return page
    .locator("li")
    .filter({ has: page.getByRole("button", { name: "Approve" }) })
    .filter({ hasText: "..." + fromTail })
    .filter({ hasText: "..." + toTail });
}

/** The audit-log row for exactly one transfer: both tails (badge action is
 *  already pinned by the server-side filter). */
function auditRowFor(page: Page, fromTail: string, toTail: string) {
  return page
    .locator("li")
    .filter({ hasText: "..." + fromTail })
    .filter({ hasText: "..." + toTail });
}

/** Place a HELD transfer from the default (checking) account to savings. */
async function placeHeldTransfer(page: Page, toIban: string, memo: string): Promise<void> {
  await page.goto("/transfers");
  await page.getByRole("heading", { name: "Send money" }).waitFor();
  await page.getByLabel("Recipient IBAN").fill(toIban);
  await page.getByLabel("Amount (USD)").fill("10000");
  await page.getByLabel("Memo (optional)").fill(memo);
  // F11: submit opens REVIEW; Confirm & send actually submits.
  await page.getByRole("button", { name: /Review transfer/ }).click();
  await expect(page.getByRole("region", { name: "Review your transfer" })).toBeVisible();
  await page.getByRole("button", { name: "Confirm & send" }).click();
  await expect(page.getByRole("heading", { name: "Transfer submitted for review" })).toBeVisible({
    timeout: 10_000
  });
}

test("operator approve and decline of a HELD transfer is reflected everywhere", async ({ page }) => {
  const customer = `op-${tag}@bank.local`;

  // --- Customer leg 1: fund in sub-threshold installments (no deposit-flag
  // rows), open savings, hold a $10,000 transfer. Nothing moves yet.
  await register(page, "Operator E2E Customer", customer);
  await page.getByRole("button", { name: "Open account" }).click();
  await page.getByLabel("Account type").selectOption("SAVINGS");
  await page.getByRole("button", { name: /^Open$/ }).click();
  await expect(page.getByText("Account opened.")).toBeVisible();
  const ibans = page.locator("main p.mono");
  await expect(ibans).toHaveCount(2); // both cards rendered, order stable
  const checkingIban = (await ibans.allTextContents())[0].trim();
  const checkingTail = checkingIban.slice(-6);
  const savingsIban = (await ibans.allTextContents())[1].trim();
  const savingsTail = savingsIban.slice(-6);

  await deposit(page, 7000);
  await deposit(page, 5000);
  await expect(balances(page).nth(1)).toHaveText("$12,000.00"); // checking funded
  await expect(balances(page).nth(2)).toHaveText("$0.00"); // savings untouched while HELD

  await placeHeldTransfer(page, savingsIban, `op-approve-${tag}`);
  await page.goto("/dashboard");
  const heldRow = page.locator("tbody tr").filter({ hasText: `op-approve-${tag}` });
  await expect(heldRow).toContainText("HELD · REVIEW", { timeout: 10_000 });
  await logout(page);

  // --- Operator approves leg 1: queue row clears, audit records it.
  await loginOperator(page);
  const queueRow = queueRowFor(page, checkingTail, savingsTail);
  await expect(queueRow).toContainText("$10,000.00", { timeout: 10_000 });
  await queueRow.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("Approved. Transfer settled.")).toBeVisible();
  await expect(queueRow).toHaveCount(0, { timeout: 10_000 }); // queue empties for this transfer

  // The operator's decision lands in the audit log with the moved funds.
  await page.getByLabel("Filter by action").fill("TRANSFER_APPROVED");
  await page.getByRole("button", { name: "Filter" }).click();
  const auditRow = auditRowFor(page, checkingTail, savingsTail)
    .filter({ hasText: "$10,000.00" })
    .first();
  await expect(auditRow).toContainText("TRANSFER_APPROVED", { timeout: 10_000 });
  await logout(page);

  // --- Customer sees leg 1 settled: money moved exactly once.
  await login(page, customer, "secret123");
  await expect(balances(page).nth(1)).toHaveText("$2,000.00"); // checking debited
  await expect(balances(page).nth(2)).toHaveText("$10,000.00"); // savings credited on approval
  const approvedFeed = page.locator("tbody tr").filter({ hasText: `op-approve-${tag}` });
  await expect(approvedFeed).toContainText("POSTED");

  // --- Customer leg 2: fund again (sub-threshold), hold a second transfer.
  await deposit(page, 4500);
  await deposit(page, 3500);
  await expect(balances(page).nth(1)).toHaveText("$10,000.00"); // checking funded again
  await placeHeldTransfer(page, savingsIban, `op-decline-${tag}`);
  await logout(page);

  // --- Operator declines leg 2: queue clears, audit records the refusal.
  await loginOperator(page);
  const declinedQueueRow = queueRowFor(page, checkingTail, savingsTail);
  await expect(declinedQueueRow).toContainText("$10,000.00", { timeout: 10_000 });
  // F11: Decline needs an explicit confirmation before the decision lands.
  await declinedQueueRow.getByRole("button", { name: "Decline" }).click();
  await expect(page.getByRole("heading", { name: "Decline this transfer?" })).toBeVisible();
  await page.getByRole("button", { name: "Decline transfer" }).click();
  await expect(page.getByText("Declined. No money moved.")).toBeVisible();
  await expect(declinedQueueRow).toHaveCount(0, { timeout: 10_000 });

  await page.getByLabel("Filter by action").fill("TRANSFER_DECLINED");
  await page.getByRole("button", { name: "Filter" }).click();
  const declinedAudit = auditRowFor(page, checkingTail, savingsTail)
    .filter({ hasText: "$10,000.00" })
    .first();
  await expect(declinedAudit).toContainText("TRANSFER_DECLINED", { timeout: 10_000 });
  await logout(page);

  // --- Customer sees leg 2 refused: balances never moved, row is CANCELLED.
  await login(page, customer, "secret123");
  await expect(balances(page).nth(1)).toHaveText("$10,000.00"); // checking never debited
  await expect(balances(page).nth(2)).toHaveText("$10,000.00"); // savings never credited again
  const declinedFeed = page.locator("tbody tr").filter({ hasText: `op-decline-${tag}` });
  await expect(declinedFeed).toContainText("CANCELLED");
});
