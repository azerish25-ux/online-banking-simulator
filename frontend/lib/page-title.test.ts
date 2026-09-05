import { describe, expect, it } from "vitest";
import { BrandName } from "./brand";
import { tabTitleFor } from "./page-title";

describe("tabTitleFor", () => {
  it("labels authed sections with the section name", () => {
    expect(tabTitleFor("/dashboard")).toBe(BrandName + " · Overview");
    expect(tabTitleFor("/transfers")).toBe(BrandName + " · Send money");
    expect(tabTitleFor("/settings")).toBe(BrandName + " · Security");
  });

  it("resolves account detail before any bare rule", () => {
    expect(tabTitleFor("/accounts/3b6a1d99-8a3e-4c44-a02d-6f3a14c78d9e")).toBe(
      BrandName + " · Account"
    );
  });

  it("labels auth pages and prefers the longer mfa prefix", () => {
    expect(tabTitleFor("/login")).toBe(BrandName + " · Log in");
    expect(tabTitleFor("/login/mfa")).toBe(BrandName + " · Two-factor check");
    expect(tabTitleFor("/register")).toBe(BrandName + " · Create your account");
  });

  it("falls back to the brand for unmatched routes", () => {
    expect(tabTitleFor("/")).toBe(BrandName);
    expect(tabTitleFor("/about")).toBe(BrandName);
  });
});
