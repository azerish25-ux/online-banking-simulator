import { describe, expect, it } from "vitest";
import { fmtDate, shortId, usd } from "./format";

describe("usd", () => {
  it("formats string balances with two decimals", () => {
    expect(usd("500.0000")).toBe("$500.00");
  });
  it("formats numbers", () => {
    expect(usd(1234.5)).toBe("$1,234.50");
  });
  it("falls back for garbage input", () => {
    expect(usd("nope")).toBe("$0.00");
  });
});

describe("fmtDate", () => {
  it("renders a readable date", () => {
    expect(fmtDate("2026-09-02T17:00:00Z")).toContain("Sep");
  });
  it("passes through invalid input", () => {
    expect(fmtDate("garbage")).toBe("garbage");
  });
});

describe("shortId", () => {
  it("truncates long ids", () => {
    expect(shortId("239d94d0-0bac")).toBe("239d94d0...");
  });
  it("leaves short ids alone", () => {
    expect(shortId("abc")).toBe("abc");
  });
});
