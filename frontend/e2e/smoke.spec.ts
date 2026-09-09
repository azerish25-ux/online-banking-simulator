import { expect, test } from "@playwright/test";
import { BrandName } from "../lib/brand";
import { Routes } from "../lib/routes";

test("landing page loads with product messaging", async ({ page }) => {
  await page.goto(Routes.home);
  await expect(page.getByRole("heading", { name: BrandName })).toBeVisible();
  await expect(page.getByRole("link", { name: /Open an account|Create demo account/ })).toBeVisible();
});

test("login page validates before submitting", async ({ page }) => {
  await page.goto(Routes.login);
  await page.getByRole("button", { name: /Log in/ }).click();
  await expect(page.getByRole("alert").first()).toBeVisible();
});

test("dashboard without a session bounces to login", async ({ page }) => {
  await page.goto(Routes.dashboard);
  await expect(page).toHaveURL(/\/login/);
});

test("design gallery renders the system", async ({ page }) => {
  await page.goto(Routes.design);
  await expect(page.getByRole("heading", { name: /Design system/ })).toBeVisible();
});
