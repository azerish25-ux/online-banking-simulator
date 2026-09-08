import { describe, expect, it } from "vitest";
import {
  accountLabel,
  decimalToCents,
  fmtDate,
  maskIban,
  signedUsd,
  toTenThousandths,
  totalUsd,
  usd,
  usdFromCents,
  usdReview
} from "./format";

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
  it("makes invalid input unavailable rather than zero", () => {
    // Malformed money must never read as a real $0.00 balance.
    expect(usd("nope")).toBe("-");
    expect(usd("")).toBe("-");
    expect(usd("1.23456")).toBe("-"); // beyond the ledger's 4-decimal scale
    expect(totalUsd(["100.00", "junk"])).toBe("-");
  });

  it("normalizes negative zero and half boundaries", () => {
    expect(usd("-0.0000")).toBe("$0.00");
    expect(usd("-0.0049")).toBe("$0.00"); // half below the cent rounds to zero
    expect(usd("-0.0050")).toBe("-$0.01"); // HALF_UP: away from zero
    expect(usd("0.0050")).toBe("$0.01");
  });
});

describe("exact sums and review precision", () => {
  it("sums at the ledger scale and rounds only the final result", () => {
    // 0.0049 + 0.0049 = 0.0098 - per-item rounding would give $0.00 + $0.00;
    // summing at ten-thousandths and rounding once shows the true $0.01.
    expect(totalUsd(["0.0049", "0.0049"])).toBe("$0.01");
    // The classic float trap stays exact: 0.1 + 0.2 + 0.3 = 0.6.
    expect(totalUsd(["0.10", "0.20", "0.30"])).toBe("$0.60");
    // Large values never overflow (BigInt units).
    expect(totalUsd(["9007199254740991.0001", "0.9999"])).toBe("$9,007,199,254,740,992.00");
  });

  it("never hides a nonzero sub-cent amount on a review", () => {
    expect(usdReview("0.0049")).toBe("$0.0049");
    expect(usdReview("-0.0049")).toBe("-$0.0049");
    expect(usdReview("1234.5678")).toBe("$1,234.5678");
    // No sub-cent part: review shows the normal two-decimal amount.
    expect(usdReview("5.00")).toBe("$5.00");
    expect(usdReview("-1000.00")).toBe("-$1,000.00");
    expect(usdReview("bad")).toBe("-");
  });

  it("exposes the exact ledger scale for sign checks", () => {
    expect(toTenThousandths("0.0049")).toBe(49n);
    expect(toTenThousandths("-0.0001")).toBe(-1n);
    expect(toTenThousandths("junk")).toBeNull();
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
