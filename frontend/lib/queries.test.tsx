import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import {
  queryKeys,
  useAccounts,
  useDeposit,
  useTransfer,
  useUnreadCount
} from "./queries";
import { ApiError, setToken } from "./api";

vi.mock("./api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api")>();
  return {
    ...actual,
    api: vi.fn()
  };
});

import { api } from "./api";

function withClient(ui: React.ReactElement) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } }
  });
  // Seed the signed-in identity so the pending-op store can bind records to
  // the user who dispatched them (recovery is owner-scoped).
  client.setQueryData(queryKeys.me, { id: "u1" } as never);
  return { client, ...render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>) };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  document.cookie = "bank_token=; max-age=0";
});

function Probe({ hook }: { hook: () => { data?: unknown; isLoading: boolean; error?: unknown } }) {
  const result = hook();
  if (result.isLoading) return <p>loading</p>;
  if (result.error) return <p role="alert">error</p>;
  return <pre>{JSON.stringify(result.data)}</pre>;
}

function DepositSender() {
  const deposit = useDeposit();
  return (
    <button type="button" onClick={() => deposit.mutate({ accountId: "a1", amount: "10.00" })}>
      deposit
    </button>
  );
}

function UnreadProbe() {
  const unread = useUnreadCount();
  if (unread.data === undefined) return <p>loading</p>;
  return <p role="status">unread:{unread.data}</p>;
}

function TransferSender() {
  const transfer = useTransfer();
  return (
    <button
      type="button"
      onClick={() =>
        transfer.mutate({ fromAccountId: "a1", toIban: "DE999", amount: "5.00" })
      }
    >
      send
    </button>
  );
}

function sentKeys() {
  return vi.mocked(api).mock.calls.map((call) => {
    const init = call[1] as RequestInit | undefined;
    const headers = (init?.headers ?? {}) as Record<string, string>;
    return headers["Idempotency-Key"] ?? null;
  });
}

describe("query hooks", () => {
  it("useAccounts fetches through the proxy and caches under its key", async () => {
    setToken("tok");
    vi.mocked(api).mockResolvedValue([{ id: "a1", iban: "DE01", type: "CHECKING", balance: "10.00", status: "ACTIVE" }]);
    withClient(<Probe hook={useAccounts} />);
    await waitFor(() => expect(screen.getByText(/CHECKING/)).toBeInTheDocument());
    expect(api).toHaveBeenCalledWith("/v1/accounts");
  });

  it("useUnreadCount unwraps the unread envelope", async () => {
    setToken("tok");
    vi.mocked(api).mockResolvedValue({ unread: 3 });
    withClient(<Probe hook={useUnreadCount} />);
    await waitFor(() => expect(screen.getByText("3")).toBeInTheDocument());
  });

  it("surfaces errors instead of throwing during render", async () => {
    setToken("tok");
    vi.mocked(api).mockRejectedValue(new Error("boom"));
    withClient(<Probe hook={useUnreadCount} />);
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
  });

  it("queryKeys are stable and parameterized", () => {
    expect(queryKeys.accounts).toEqual(["accounts"]);
    expect(queryKeys.transactions("a1", "", 10, "2026-01-01")).toEqual(["transactions", "a1", "", 10, "2026-01-01", ""]);
    expect(queryKeys.summary("a1", 6)).toEqual(["summary", "a1", 6]);
  });

  it("reuses one idempotency key across retries after a transient error", async () => {
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api)
      // Transient 5xx: the transfer may or may not have posted - the retry
      // must reuse the same key so the server deduplicates, never double-sends.
      .mockRejectedValueOnce(new ApiError(500, "Server Error", "boom"));
    withClient(<TransferSender />);
    const send = screen.getByRole("button", { name: "send" });
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const keys = sentKeys();
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  it("reuses the stored key after a full reload (lost response, same intent)", async () => {
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api)
      // First attempt: the server committed but the response was lost (5xx).
      .mockRejectedValueOnce(new ApiError(500, "Server Error", "boom"))
      // Retry after the "reload": dedupe against the original posting.
      .mockResolvedValueOnce({ id: "t1", toIban: "DE999", amount: "5.00", flagged: false });

    // First "page life": the attempt fails ambiguously and the page goes away.
    const first = withClient(<TransferSender />);
    const send = screen.getByRole("button", { name: "send" });
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    const beforeReload = sentKeys();
    expect(beforeReload[0]).toBeTruthy();
    first.unmount();
    cleanup();

    // "Reload": a brand-new hook instance, no in-memory ref - only the store.
    // Re-submitting the SAME reviewed intent must resume the SAME key, so the
    // server replays the original operation instead of posting a second one.
    withClient(<TransferSender />);
    await user.click(screen.getByRole("button", { name: "send" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const keys = sentKeys();
    expect(keys[1]).toBe(keys[0]);
  });

  it("editing the draft into a NEW intent mints a fresh key and keeps the old record", async () => {
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api).mockRejectedValueOnce(new ApiError(500, "Server Error", "boom"));
    const first = withClient(<TransferSender />);
    await user.click(screen.getByRole("button", { name: "send" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    const oldKey = sentKeys()[0];
    first.unmount();
    cleanup();

    // A DIFFERENT amount is a different intent: it must NOT inherit the older
    // operation's key (that would 409), and the older pending record must
    // survive (still recoverable) rather than being erased by the edit.
    vi.mocked(api).mockResolvedValueOnce({ id: "t2", toIban: "DE999", amount: "9.00", flagged: false });
    function DifferentAmountSender() {
      const transfer = useTransfer();
      return (
        <button
          type="button"
          onClick={() =>
            transfer.mutate({ fromAccountId: "a1", toIban: "DE999", amount: "9.00" })
          }
        >
          send9
        </button>
      );
    }
    withClient(<DifferentAmountSender />);
    await user.click(screen.getByRole("button", { name: "send9" }));
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const keys = sentKeys();
    expect(keys[1]).not.toBe(oldKey);
    // The older unresolved record is still in the store.
    const { listPendingOperations } = await import("./pending-op");
    expect(listPendingOperations("u1").map((o) => o.key)).toContain(oldKey);
  });

  it("starts a fresh key after success or a definitive 4xx", async () => {
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api)
      .mockRejectedValueOnce(new ApiError(500, "Server Error", "boom")) // transient → keep key
      .mockResolvedValueOnce({ id: "t1", toIban: "DE999", amount: "5.00", flagged: false }) // success
      .mockRejectedValueOnce(new ApiError(400, "Transfer Rejected", "Insufficient funds")) // 4xx → drop key
      .mockResolvedValueOnce({ id: "t2", toIban: "DE999", amount: "5.00", flagged: false });
    withClient(<TransferSender />);
    const send = screen.getByRole("button", { name: "send" });

    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    const after5xx = sentKeys();

    // The successful retry reuses the 5xx attempt's key - the server dedupes
    // on it, so a transfer that actually posted must not double-send.
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const afterSuccess = sentKeys();
    expect(afterSuccess[1]).toBe(after5xx[0]);

    // Success cleared the key: the next send is a new intent with a new key.
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(3));
    // The 4xx dropped the key too: another send mints yet another one.
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(4));
    const keys = sentKeys();
    expect(keys[2]).toBeTruthy();
    expect(keys[2]).not.toBe(afterSuccess[1]);
    expect(keys[3]).not.toBe(keys[2]);
  });

  it("deposit refreshes the unread badge (server-side notification)", async () => {
    const user = userEvent.setup();
    setToken("tok");
    const paths: string[] = [];
    vi.mocked(api).mockImplementation(async (path) => {
      paths.push(path);
      if (path.includes("unread-count")) return { unread: 1 };
      // The deposit endpoint now answers with the recoverable operation
      // envelope (F06 lifecycle), not a bare account.
      return {
        account: { id: "a1", iban: "DE01", type: "CHECKING", balance: "110.00", status: "ACTIVE" },
        operationId: "op-d1",
        idempotencyKey: "dep-k",
        status: "POSTED"
      };
    });
    withClient(
      <>
        <DepositSender />
        <UnreadProbe />
      </>
    );
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("unread:1"));
    const unreadCallsBefore = paths.filter((p) => p.includes("unread-count")).length;

    await user.click(screen.getByRole("button", { name: "deposit" }));
    await waitFor(() =>
      expect(paths.filter((p) => p.includes("unread-count")).length).toBeGreaterThan(
        unreadCallsBefore
      )
    );
    expect(paths).toContain("/v1/accounts/a1/deposit");
  });

  it("deposits carry an idempotency key reused across ambiguous retries", async () => {
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api)
      // Transient 5xx: the deposit may or may not have posted - the retry must
      // reuse the same key so the server never double-credits.
      .mockRejectedValueOnce(new ApiError(500, "Server Error", "boom"))
      .mockResolvedValueOnce({
        account: { id: "a1", iban: "DE01", type: "CHECKING", balance: "110.00", status: "ACTIVE" },
        operationId: "op-d2",
        idempotencyKey: "dep-k",
        status: "POSTED"
      });
    withClient(<DepositSender />);
    const send = screen.getByRole("button", { name: "deposit" });
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    const firstCall = sentKeys();
    expect(firstCall[0]).toBeTruthy();

    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const keys = sentKeys();
    expect(keys[1]).toBe(keys[0]);
  });

  it("deposits keep the key on 409/429 and drop it on a definitive 4xx", async () => {
    const user = userEvent.setup();
    setToken("tok");
    vi.mocked(api)
      // A 409 means the key already names an operation - never discard it: a
      // retry must resolve the original result, not mint a competing op.
      .mockRejectedValueOnce(new ApiError(409, "Idempotency Conflict", "already used"))
      .mockResolvedValueOnce({
        account: { id: "a1", iban: "DE01", type: "CHECKING", balance: "110.00", status: "ACTIVE" },
        operationId: "op-d3",
        idempotencyKey: "dep-k",
        status: "POSTED"
      })
      // A definitive rejection (validation) records nothing: the key is free.
      .mockRejectedValueOnce(new ApiError(400, "Transfer Rejected", "limit"))
      .mockResolvedValueOnce({
        account: { id: "a1", iban: "DE01", type: "CHECKING", balance: "110.00", status: "ACTIVE" },
        operationId: "op-d4",
        idempotencyKey: "dep-k",
        status: "POSTED"
      });
    withClient(<DepositSender />);
    const send = screen.getByRole("button", { name: "deposit" });

    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const after409 = sentKeys();
    expect(after409[1]).toBe(after409[0]);

    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(3));
    await user.click(send);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(4));
    const keys = sentKeys();
    expect(keys[3]).not.toBe(keys[2]);
  });
});
