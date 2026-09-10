import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearAllPendingOperations,
  listPendingOperations,
  removePendingOperation,
  upsertPendingOperation,
  type PendingOperation
} from "./pending-op";

function record(overrides: Partial<PendingOperation> = {}): PendingOperation {
  return {
    userId: "u1",
    kind: "transfer",
    key: "k-" + Math.random(),
    accountId: "a1",
    amount: "10.00",
    createdAt: Date.now(),
    ...overrides
  };
}

afterEach(() => {
  clearAllPendingOperations();
  vi.restoreAllMocks();
});

describe("pending-op store (operation-keyed)", () => {
  it("keeps two operations of the same kind side by side", () => {
    const one = record({ key: "k-1", amount: "10.00" });
    const two = record({ key: "k-2", amount: "25.00" });
    upsertPendingOperation(one);
    upsertPendingOperation(two);

    const list = listPendingOperations("u1");
    expect(list).toHaveLength(2);
    expect(list.map((o) => o.key).sort()).toEqual(["k-1", "k-2"]);
  });

  it("keeps a deposit and a transfer in the same store", () => {
    upsertPendingOperation(record({ kind: "transfer", key: "kt" }));
    upsertPendingOperation(record({ kind: "deposit", key: "kd", accountId: "a9" }));

    expect(listPendingOperations("u1")).toHaveLength(2);
  });

  it("keeps the SAME key on two accounts and on two kinds side by side", () => {
    // The server key namespace is the originating account: one key may
    // legitimately exist on two accounts, or as a deposit AND a transfer on
    // one account. Records must never collide.
    upsertPendingOperation(record({ key: "kx", accountId: "a1" }));
    upsertPendingOperation(record({ key: "kx", accountId: "a2", amount: "7.00" }));
    upsertPendingOperation(record({ key: "kx", accountId: "a1", kind: "deposit" }));
    expect(listPendingOperations("u1")).toHaveLength(3);
  });

  it("removing ONE operation never erases another user's, kind's or account's", () => {
    upsertPendingOperation(record({ userId: "u1", kind: "transfer", key: "kt", accountId: "a1" }));
    upsertPendingOperation(record({ userId: "u1", kind: "deposit", key: "kd", accountId: "a1" }));
    upsertPendingOperation(record({ userId: "u1", kind: "transfer", key: "kt", accountId: "a2" }));
    upsertPendingOperation(record({ userId: "u2", kind: "transfer", key: "kt2", accountId: "a1" }));

    removePendingOperation("u1", "transfer", "a1", "kt");

    const left = listPendingOperations("u1");
    expect(left).toHaveLength(2);
    expect(left.map((o) => o.kind + ":" + o.accountId).sort())
        .toEqual(["deposit:a1", "transfer:a2"]);
    expect(listPendingOperations("u2")).toHaveLength(1);
  });

  it("re-writing a record refreshes it instead of duplicating", () => {
    const op = record({ key: "k-1", amount: "10.00" });
    upsertPendingOperation(op);
    upsertPendingOperation({ ...op, amount: "10.00" });
    expect(listPendingOperations("u1")).toHaveLength(1);
  });

  it("discards corrupted records instead of trusting them as recovery identity", () => {
    // Directly plant a malformed bucket the way a stale/foreign writer would.
    const storageKey = "bank.pending-ops.v2";
    sessionStorage.setItem(
      storageKey,
      JSON.stringify({ u1: { broken: { userId: "u1", kind: "transfer" } } })
    );
    expect(listPendingOperations("u1")).toEqual([]);
  });

  it("discards records without their originating account: they cannot be replayed safely", () => {
    const storageKey = "bank.pending-ops.v2";
    sessionStorage.setItem(
      storageKey,
      JSON.stringify({
        u1: {
          noAccount: { userId: "u1", kind: "transfer", key: "kx", createdAt: Date.now() }
        }
      })
    );
    expect(listPendingOperations("u1")).toEqual([]);
  });

  it("clears everything at the session boundary", () => {
    upsertPendingOperation(record({ kind: "transfer", key: "kt" }));
    upsertPendingOperation(record({ kind: "deposit", key: "kd" }));
    clearAllPendingOperations();
    expect(listPendingOperations("u1")).toEqual([]);
  });
});
