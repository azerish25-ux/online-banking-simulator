import { describe, expect, it } from "vitest";
import { accountLabel, decimalToCents, fmtDate, maskIban, signedUsd, usd, usdFromCents } from "./format";

describe("usd", () => {
  it("formats string balances with two decimals", () => {
    expect(usd("500.0000")).toBe("$500.00");
  });
  it("rounds exactly without passing through a float", () => {
    // parseFloat("2.6750") is 2.6749999... and used to display $2.67; the
    // exact cents path rounds half away from zero like every other money
    // display in the app.
    expect(usd("2.6750")).toBe("$2.68");
    expect(usd("1234.5678")).toBe("$1,234.57");
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

describe("maskIban", () => {
  it("shows only the last six digits", () => {
    expect(maskIban("DE07532735619885017984")).toBe("...017984");
  });
  it("returns null for absent values so callers pick the label", () => {
    expect(maskIban(null)).toBeNull();
    expect(maskIban(undefined)).toBeNull();
    expect(maskIban("")).toBeNull();
  });
});

describe("accountLabel", () => {
  const account = { type: "CHECKING", iban: "DE07532735619885017984" };

  it("names an account as type + masked IBAN", () => {
    expect(accountLabel(account)).toBe("CHECKING ...017984");
  });

  it("appends the balance only when the chooser shows funds", () => {
    expect(accountLabel(account, "1234.50")).toBe("CHECKING ...017984 · $1,234.50");
    expect(accountLabel(account, "75")).toBe("CHECKING ...017984 · $75.00");
    // A drawn loan's balance is negative debt and must read as such.
    expect(accountLabel({ type: "LOAN", iban: account.iban }, "-100.00")).toBe(
      "LOAN ...017984 · -$100.00"
    );
  });

  it("survives absent optional fields", () => {
    expect(accountLabel({ iban: "" })).toBe("");
    expect(accountLabel({ type: "SAVINGS" })).toBe("SAVINGS");
  });
});
