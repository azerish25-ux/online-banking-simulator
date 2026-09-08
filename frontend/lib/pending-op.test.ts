import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearAllPendingOperations,
  findPendingOperation,
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

    expect(findPendingOperation("u1", "transfer", "kt")).not.toBeNull();
    expect(findPendingOperation("u1", "deposit", "kd")).not.toBeNull();
    expect(listPendingOperations("u1")).toHaveLength(2);
  });

  it("removing ONE operation never erases another user's or kind's", () => {
    upsertPendingOperation(record({ userId: "u1", kind: "transfer", key: "kt" }));
    upsertPendingOperation(record({ userId: "u1", kind: "deposit", key: "kd" }));
    upsertPendingOperation(record({ userId: "u2", kind: "transfer", key: "kt2" }));

    removePendingOperation("u1", "transfer", "kt");

    expect(findPendingOperation("u1", "transfer", "kt")).toBeNull();
    expect(findPendingOperation("u1", "deposit", "kd")).not.toBeNull();
    expect(findPendingOperation("u2", "transfer", "kt2")).not.toBeNull();
    expect(listPendingOperations("u1")).toHaveLength(1);
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

  it("clears everything at the session boundary", () => {
    upsertPendingOperation(record({ kind: "transfer", key: "kt" }));
    upsertPendingOperation(record({ kind: "deposit", key: "kd" }));
    clearAllPendingOperations();
    expect(listPendingOperations("u1")).toEqual([]);
  });
});
