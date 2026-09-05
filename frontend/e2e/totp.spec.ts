import { expect, test, type Page } from "@playwright/test";
import { totpCodesNearNow } from "./totp-code";

/**
 * The 2FA round trip, browser to browser: a fresh user enables TOTP in
 * Security (the secret is read from the live screen and real RFC 6238 codes
 * are generated in-test), signs out, and the next login must challenge with
 * a code before any session exists. Then the user disables 2FA and the next
 * login must NOT challenge. The only fixed identity is none - the user is
 * self-registered, like the rest of the suite.
 */

const tag = Date.now();

async function register(page: Page, email: string): Promise<void> {
  await page.goto("/register");
  await page.getByLabel("Full name").fill("Totp E2E User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /Create account/ }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.locator("main p.mono").first().waitFor({ timeout: 10_000 });
}

async function logout(page: Page): Promise<void> {
  await page.goto("/dashboard");
  await page.getByRole("button", { name: /Log out/ }).click();
  await expect(page).toHaveURL(/\/login/);
}

async function login(page: Page, email: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("secret123");
  await page.getByRole("button", { name: /^Log in$/ }).click();
}

/** Fill the visible "Authenticator code" field and try each nearby window's
 *  code until the success predicate passes (codes rotate every 30 s). The
 *  confirm action is a button click: none of the code inputs sit in a form
 *  (Enter would do nothing), and each surface names its own confirm button. */
async function submitCode(
  page: Page,
  secret: string,
  confirmName: string | RegExp,
  success: () => Promise<boolean>
): Promise<void> {
  for (const code of totpCodesNearNow(secret)) {
    const field = page.getByLabel("Authenticator code");
    await field.fill(code);
    await page.getByRole("button", { name: confirmName, exact: true }).click();
    await page.waitForTimeout(800);
    if (await success()) return;
  }
  throw new Error("No TOTP code window was accepted");
}

test("TOTP round trip: enable, challenge at next login, disable", async ({ page }) => {
  const email = `totp-${tag}@bank.local`;
  await register(page, email);

  // --- Enable: read the secret from the screen, confirm with a live code.
  await page.goto("/settings");
  await page.getByRole("heading", { name: "Security" }).waitFor();
  await page.getByRole("button", { name: /Set up authenticator/ }).click();
  const secret = page.locator("p.mono.break-all").first();
  await expect(secret).toBeVisible({ timeout: 10_000 }); // surface rendered, not empty
  await expect(page.getByAltText(/QR code to add Online Banking Simulator/)).toBeVisible();
  const secretValue = (await secret.innerText()).trim();
  expect(secretValue.length).toBeGreaterThanOrEqual(16); // real base32 secret shown

  await submitCode(page, secretValue, "Enable two-factor", async () =>
    (await page.locator('[role="status"]').allTextContents()).some((t) =>
      t.includes("Two-factor authentication is on")
    )
  );
  await expect(page.getByText("ON", { exact: true })).toBeVisible();
  await expect(page.getByText(/Active - a six-digit code is required/)).toBeVisible();

  // --- Sign out: the next login must challenge before any session exists.
  await logout(page);
  await login(page, email);
  await expect(page).toHaveURL(/\/login\/mfa/);
  await expect(page.getByRole("heading", { name: "Two-factor check" })).toBeVisible();
  await expect(page).not.toHaveURL(/\/dashboard/);

  await submitCode(page, secretValue, "Verify and continue", async () => page.url().includes("/dashboard"));
  await page.locator("main p.mono").first().waitFor({ timeout: 10_000 });
  await expect(page).toHaveURL(/\/dashboard/);

  // --- Disable: current code required again, then the next login is direct.
  await page.goto("/settings");
  await page.getByRole("button", { name: /Turn off two-factor/ }).click();
  await expect(page.getByRole("heading", { name: "Turn off two-factor?" })).toBeVisible();
  await submitCode(page, secretValue, "Turn off", async () =>
    (await page.locator('[role="status"]').allTextContents()).some((t) =>
      t.includes("Two-factor authentication is off")
    )
  );
  await expect(page.getByText("OFF", { exact: true })).toBeVisible();

  await logout(page);
  await login(page, email);
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 });
  await expect(page.getByRole("heading", { name: "Two-factor check" })).toHaveCount(0);
});
