import { describe, expect, it } from "vitest";
import {
  accountListSchema,
  accountSchema,
  historyPageSchema,
  monthPointListSchema,
  operationListSchema,
  requireShape,
  transactionSchema
} from "./guards";

const account = {
  id: "a1",
  iban: "DE00000000000000000001",
  type: "CHECKING",
  balance: "1250.0000",
  status: "ACTIVE",
  principalOwed: null,
  interestOwed: null,
  totalOwed: null,
  availableCredit: null
};

const tx = {
  id: "t1",
  status: "POSTED",
  amount: "10.0000",
  currency: "USD",
  createdAt: "2026-09-01T12:00:00Z",
  postedAt: "2026-09-01T12:00:00Z",
  memo: "Rent",
  fromIban: "DE00000000000000000001",
  toIban: "DE00000000000000000002",
  kind: "TRANSFER",
  flagged: false,
  reviewed: true
};

describe("runtime financial guards", () => {
  it("accepts a well-formed account and tolerates additive fields", () => {
    const parsed = requireShape(accountSchema, { ...account, extraField: "ignored" }, "account");
    expect(parsed.balance).toBe("1250.0000");
  });

  it("rejects an account whose balance is not an exact ledger decimal", () => {
    // A JSON number or a float string must never be displayed as money.
    expect(() =>
      requireShape(accountSchema, { ...account, balance: 1250 }, "account")
    ).toThrow(/Unrecognized account response/);
    expect(() =>
      requireShape(accountSchema, { ...account, balance: "12.345678" }, "account")
    ).toThrow(/Unrecognized account response/);
    expect(() =>
      requireShape(accountSchema, { ...account, balance: "abc" }, "account")
    ).toThrow(/Unrecognized account response/);
  });

  it("rejects unsupported account type/status instead of guessing", () => {
    expect(() =>
      requireShape(accountSchema, { ...account, type: "SAFE" }, "account")
    ).toThrow(/Unrecognized account response/);
    expect(() =>
      requireShape(accountSchema, { ...account, status: "OPEN" }, "account")
    ).toThrow(/Unrecognized account response/);
  });

  it("validates loan fields only when present (null tolerated)", () => {
    const loan = {
      ...account,
      type: "LOAN",
      balance: "-600.0000",
      principalOwed: "500.0000",
      interestOwed: "100.0000",
      totalOwed: "600.0000",
      availableCredit: "400.0000"
    };
    expect(requireShape(accountSchema, loan, "account").type).toBe("LOAN");
    expect(() =>
      requireShape(accountSchema, { ...loan, interestOwed: "1e2" }, "account")
    ).toThrow(/Unrecognized account response/);
  });

  it("accepts a full transaction history page with a null cursor", () => {
    const page = {
      items: [tx, { ...tx, id: "t2", status: "HELD", postedAt: null }],
      total: 2,
      nextCursor: null
    };
    expect(requireShape(historyPageSchema, page, "history").items).toHaveLength(2);
    expect(requireShape(transactionSchema, tx, "receipt").amount).toBe("10.0000");
  });

  it("rejects transactions with malformed money, currency, or state", () => {
    expect(() =>
      requireShape(transactionSchema, { ...tx, amount: "10.00000" }, "receipt")
    ).toThrow(/Unrecognized receipt response/);
    expect(() =>
      requireShape(transactionSchema, { ...tx, amount: "ten" }, "receipt")
    ).toThrow(/Unrecognized receipt response/);
    expect(() =>
      requireShape(transactionSchema, { ...tx, amount: "-0.0001" }, "receipt")
    ).not.toThrow();
    expect(() =>
      requireShape(transactionSchema, { ...tx, currency: "usd" }, "receipt")
    ).toThrow(/Unrecognized receipt response/);
    expect(() =>
      requireShape(transactionSchema, { ...tx, status: "POSTED!" }, "receipt")
    ).toThrow(/Unrecognized receipt response/);
    expect(() =>
      requireShape(transactionSchema, { ...tx, createdAt: "yesterday" }, "receipt")
    ).toThrow(/Unrecognized receipt response/);
  });

  it("enforces exact money on summaries and recent operations", () => {
    expect(
      requireShape(
        monthPointListSchema,
        [{ month: "2026-08", inflow: "5.2603", outflow: "0.0000" }],
        "summary"
      )
    ).toHaveLength(1);
    expect(() =>
      requireShape(monthPointListSchema, [{ month: "2026-08", inflow: "0.5", outflow: "x" }], "summary")
    ).toThrow(/Unrecognized summary response/);

    const op = {
      id: "o1",
      idempotencyKey: "key-1",
      kind: "TRANSFER",
      amount: "5.0000",
      currency: "USD",
      createdAt: "2026-09-01T12:00:00Z",
      postedAt: null,
      memo: null,
      fromIban: "DE00000000000000000001",
      toIban: "DE00000000000000000002",
      status: "POSTED"
    };
    expect(requireShape(operationListSchema, { items: [op] }, "operation list").items).toHaveLength(1);
    expect(() =>
      requireShape(operationListSchema, { items: [{ ...op, amount: 5 }] }, "operation list")
    ).toThrow(/Unrecognized operation list response/);
  });

  it("account lists reject a single malformed member (no partial trust)", () => {
    expect(() =>
      requireShape(accountListSchema, [account, { ...account, balance: "NaN" }], "account")
    ).toThrow(/Unrecognized account response/);
  });
});
