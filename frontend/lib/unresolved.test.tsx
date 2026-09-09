import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import {
  clearAllPendingOperations,
  listPendingOperations,
  upsertPendingOperation
} from "./pending-op";
import { queryKeys, resolveUnresolvedOperation, useTransfer } from "./queries";
import { ApiError, setToken } from "./api";

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return {
    ...actual,
    api: vi.fn()
  };
});

import { api } from "./api";

function client() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  qc.setQueryData(queryKeys.me, { id: "u1" } as never);
  return qc;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  clearAllPendingOperations();
  document.cookie = "bank_token=; max-age=0";
});

function TransferSender({ amount, memo }: { amount: string; memo?: string }) {
  const transfer = useTransfer();
  return (
    <button
      type="button"
      onClick={() =>
        transfer.mutate({ fromAccountId: "a1", toIban: "DE999", amount, memo })
      }
    >
      send {amount} {memo ?? ""}
    </button>
  );
}

function keysSent() {
  return vi.mocked(api).mock.calls.map((call) => {
    const init = call[1] as RequestInit | undefined;
    const headers = (init?.headers ?? {}) as Record<string, string>;
    return headers["Idempotency-Key"] ?? null;
  });
}

describe("unresolved-operation resolution", () => {
  it("a changed memo is a NEW intent: it mints a fresh key and keeps the old record", async () => {
    // Guard for the recovery premise: a replay must be byte-identical, so a
    // transfer edited to a different memo must never inherit the old key (the
    // server would answer 409): and the older record must stay recoverable.
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api).mockRejectedValueOnce(new ApiError(500, "Server Error", "boom"));
    const qc = client();
    const first = render(
      <QueryClientProvider client={qc}>
        <TransferSender amount="5.00" memo="Rent" />
      </QueryClientProvider>
    );
    await user.click(screen.getByRole("button", { name: "send 5.00 Rent" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    const oldKey = keysSent()[0];
    first.unmount();
    cleanup();

    vi.mocked(api).mockResolvedValueOnce({ id: "t2", status: "POSTED", flagged: false });
    const qc2 = client();
    render(
      <QueryClientProvider client={qc2}>
        <TransferSender amount="5.00" memo="Updated memo" />
      </QueryClientProvider>
    );
    await user.click(screen.getByRole("button", { name: "send 5.00 Updated memo" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const keys = keysSent();
    expect(keys[1]).not.toBe(oldKey);
    // The old intent's record is untouched: still resolvable by its key.
    expect(listPendingOperations("u1").map((o) => o.key)).toContain(oldKey);
  });

  it("a same-memo retry after a reload resumes the stored key (identical replay)", async () => {
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api)
      .mockRejectedValueOnce(new ApiError(500, "Server Error", "boom"))
      .mockResolvedValueOnce({ id: "t1", status: "POSTED", flagged: false });
    const qc = client();
    const first = render(
      <QueryClientProvider client={qc}>
        <TransferSender amount="5.00" memo="Rent" />
      </QueryClientProvider>
    );
    await user.click(screen.getByRole("button", { name: "send 5.00 Rent" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    const beforeReload = keysSent()[0];
    first.unmount();
    cleanup();

    const qc2 = client();
    render(
      <QueryClientProvider client={qc2}>
        <TransferSender amount="5.00" memo="Rent" />
      </QueryClientProvider>
    );
    await user.click(screen.getByRole("button", { name: "send 5.00 Rent" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    expect(keysSent()[1]).toBe(beforeReload);
  });

  it("resolves a saved deposit replay: clears the record and reports POSTED", async () => {
    setToken("tok");
    upsertPendingOperation({
      userId: "u1", kind: "deposit", key: "kd1", accountId: "a1",
      amount: "10.00", createdAt: Date.now() - 1000
    });
    vi.mocked(api).mockResolvedValue({
      account: { id: "a1", iban: "DE01", type: "CHECKING", balance: "60.00", status: "ACTIVE" },
      operationId: "op-1", idempotencyKey: "kd1", status: "POSTED"
    });
    const qc = client();
    const outcome = await resolveUnresolvedOperation(qc, listPendingOperations("u1")[0]);
    expect(outcome.kind).toBe("resolved");
    expect(outcome).toMatchObject({ status: "POSTED" });
    expect(listPendingOperations("u1")).toEqual([]);
    const call = vi.mocked(api).mock.calls[0];
    expect(call[0]).toBe("/v1/accounts/a1/deposit");
    expect((call[1]?.headers as Record<string, string>)["Idempotency-Key"]).toBe("kd1");
  });

  it("a definitive rejection clears the record and surfaces the server's words", async () => {
    setToken("tok");
    upsertPendingOperation({
      userId: "u1", kind: "transfer", key: "kt1", accountId: "a1",
      amount: "5.00", toIban: "DE999", createdAt: Date.now() - 1000
    });
    vi.mocked(api).mockRejectedValue(new ApiError(400, "Transfer Rejected", "Insufficient funds"));
    const outcome = await resolveUnresolvedOperation(client(), listPendingOperations("u1")[0]);
    expect(outcome.kind).toBe("rejected");
    expect(outcome).toMatchObject({ message: "Insufficient funds" });
    expect(listPendingOperations("u1")).toEqual([]);
  });

  it("an ambiguous 5xx KEEPS the record and reports the outcome as unknown", async () => {
    setToken("tok");
    upsertPendingOperation({
      userId: "u1", kind: "transfer", key: "kt2", accountId: "a1",
      amount: "5.00", toIban: "DE999", createdAt: Date.now() - 1000
    });
    vi.mocked(api).mockRejectedValue(new ApiError(503, "Server Error", "boom"));
    const outcome = await resolveUnresolvedOperation(client(), listPendingOperations("u1")[0]);
    expect(outcome.kind).toBe("unknown");
    expect((outcome as { message: string }).message).toContain(
      "The server could not confirm whether your transfer went through"
    );
    expect(listPendingOperations("u1")).toHaveLength(1);
  });

  it("a 401 during recovery is AMBIGUOUS: the original dispatch may have committed", async () => {
    // The first attempt could have posted with a token that expired after
    // commit; this replay is refused by the auth filter before the server can
    // say what happened. The record must survive so the user can check again
    // after re-authentication: never silently erase it as a
    // definitive rejection.
    setToken("tok");
    upsertPendingOperation({
      userId: "u1", kind: "transfer", key: "kt401", accountId: "a1",
      amount: "5.00", toIban: "DE999", createdAt: Date.now() - 1000
    });
    vi.mocked(api).mockRejectedValue(new ApiError(401, "Unauthorized", "Session expired"));
    const outcome = await resolveUnresolvedOperation(client(), listPendingOperations("u1")[0]);
    expect(outcome.kind).toBe("unknown");
    expect((outcome as { message: string }).message).toContain("session expired");
    expect(listPendingOperations("u1")).toHaveLength(1);
  });

  it("a 409 resolves through the recorded truth lookup instead of guessing", async () => {
    setToken("tok");
    upsertPendingOperation({
      userId: "u1", kind: "transfer", key: "kt3", accountId: "a1",
      amount: "5.00", toIban: "DE999", createdAt: Date.now() - 1000
    });
    vi.mocked(api)
      .mockRejectedValueOnce(new ApiError(409, "Idempotency Conflict", "key names another operation"))
      .mockResolvedValueOnce({ id: "op-x", status: "POSTED", flagged: false });
    const outcome = await resolveUnresolvedOperation(client(), listPendingOperations("u1")[0]);
    expect(outcome.kind).toBe("resolved");
    expect(outcome).toMatchObject({ status: "POSTED" });
    expect(listPendingOperations("u1")).toEqual([]);
    expect(vi.mocked(api).mock.calls[1][0]).toBe("/v1/operations?key=kt3");
  });
});
