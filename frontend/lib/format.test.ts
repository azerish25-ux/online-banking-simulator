import { describe, expect, it } from "vitest";
import { decimalToCents, fmtDate, shortId, signedUsd, usd, usdFromCents } from "./format";

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

describe("exact money math (cents)", () => {
  it("converts decimal strings to integer cents", () => {
    expect(decimalToCents("500.0000")).toBe(50000n);
    expect(decimalToCents("0.10")).toBe(10n);
    expect(decimalToCents("0.005")).toBe(1n);
    expect(decimalToCents("1234.5678")).toBe(123457n);
    expect(decimalToCents("-1000.00")).toBe(-100000n);
  });

  it("sums without float drift", () => {
    const balances = ["0.10", "0.20", "0.30"];
    const total = balances.reduce((sum, b) => sum + decimalToCents(b), 0n);
    // The classic 0.1 + 0.2 + 0.3 float trap stays exact in integer cents.
    expect(total).toBe(60n);
    expect(usdFromCents(total)).toBe("$0.60");
  });

  it("formats cents with grouping and negatives", () => {
    expect(usdFromCents(123456n)).toBe("$1,234.56");
    expect(usdFromCents(-100000n)).toBe("-$1,000.00");
    expect(usdFromCents(0n)).toBe("$0.00");
  });
});

describe("signedUsd", () => {
  const viewed = "DE00000000000000000001";
  const other = "DE00000000000000000002";

  it("marks inbound rows (viewed account is the destination) as credits", () => {
    expect(signedUsd("500.0000", other, viewed, viewed)).toBe("+$500.00");
    // Deposits have no sender.
    expect(signedUsd("100.00", null, viewed, viewed)).toBe("+$100.00");
  });

  it("marks outbound rows (viewed account is the sender) as debits", () => {
    expect(signedUsd("600.0000", viewed, other, viewed)).toBe("-$600.00");
  });

  it("keeps rows that do not involve the viewed account unsigned", () => {
    expect(signedUsd("9.00", other, "DE00000000000000000003", viewed)).toBe("$9.00");
  });
});

describe("fmtDate", () => {
  it("renders a readable date", () => {
    expect(fmtDate("2026-09-02T17:00:00Z")).toContain("Sep");
  });
  it("adds the year only for dates in a different year", () => {
    const thisYear = new Date().getFullYear();
    expect(fmtDate(thisYear + "-09-02T17:00:00Z")).not.toContain(", " + thisYear);
    expect(fmtDate("1999-12-31T23:59:00Z")).toContain(", 1999");
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
