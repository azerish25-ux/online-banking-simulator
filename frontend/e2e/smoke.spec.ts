import { expect, test } from "@playwright/test";

test("landing page loads with product messaging", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /Northbank/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /Create account/ })).toBeVisible();
});

test("login page validates before submitting", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("button", { name: /Log in/ }).click();
  await expect(page.getByRole("alert").first()).toBeVisible();
});

test("dashboard without a session bounces to login", async ({ page }) => {
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/login/);
});

test("design gallery renders the system", async ({ page }) => {
  await page.goto("/design");
  await expect(page.getByRole("heading", { name: /Design system/ })).toBeVisible();
});
