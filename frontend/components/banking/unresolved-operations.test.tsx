import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import { UnresolvedOperations } from "./unresolved-operations";
import { ToastProvider } from "../feedback/toast";
import { clearAllPendingOperations, listPendingOperations, upsertPendingOperation } from "../../lib/pending-op";
import { queryKeys } from "../../lib/queries";
import { ApiError } from "../../lib/api";

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    api: vi.fn()
  };
});

import { api } from "../../lib/api";

const user = { id: "u1", email: "a@b.co", fullName: "Alice", role: "CUSTOMER", totpEnabled: false };

function seed(overrides: Partial<Parameters<typeof upsertPendingOperation>[0]> = {}) {
  upsertPendingOperation({
    userId: "u1",
    kind: "transfer",
    key: "k1",
    accountId: "a1",
    amount: "5.00",
    toIban: "DE99999999",
    memo: "Rent",
    createdAt: Date.now() - 60_000,
    ...overrides
  });
}

function postedTx(key = "k1") {
  return {
    id: "t1",
    fromIban: "DE11111111",
    toIban: "DE99999999",
    amount: "5.00",
    currency: "USD",
    memo: "Rent",
    status: "POSTED",
    kind: "TRANSFER",
    createdAt: new Date().toISOString(),
    flagged: false,
    reviewed: false,
    idempotencyKey: key
  };
}

function renderCard(recentItems: unknown[] = []) {
  vi.mocked(api).mockImplementation(async (path: string, options?: RequestInit) => {
    if (path.startsWith("/v1/operations/recent")) return { items: recentItems };
    if (path === "/v1/transfers" && options?.method === "POST") {
      throw new Error("replay not stubbed for this test");
    }
    return {};
  });
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  qc.setQueryData(queryKeys.me, user);
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <UnresolvedOperations />
      </ToastProvider>
    </QueryClientProvider>
  );
}

/** The single POSTed money request (replay): path, key header, parsed body. */
function lastReplay() {
  const call = vi.mocked(api).mock.calls.find((c) => c[0] === "/v1/transfers" && (c[1]?.method ?? "") === "POST");
  expect(call).toBeTruthy();
  const init = call![1] as RequestInit;
  return {
    path: call![0],
    key: (init.headers as Record<string, string>)["Idempotency-Key"],
    body: JSON.parse(String(init.body))
  };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  clearAllPendingOperations();
});

describe("UnresolvedOperations recovery surface", () => {
  beforeEach(() => {
    // Fresh storage per test: the store is per-session and tests are isolated.
    clearAllPendingOperations();
  });

  it("renders nothing when this session has no saved operations", async () => {
    renderCard([]);
    await waitFor(() => expect(api).toHaveBeenCalled());
    expect(screen.queryByText("Unresolved operations")).not.toBeInTheDocument();
  });

  it("lists a saved operation and resolves it by re-sending the identical keyed request", async () => {
    seed();
    // renderCard installs a default stub: install the replay stub AFTER it so
    // the click sees this one (renderCard must not clobber per-test behavior).
    renderCard([]);
    vi.mocked(api).mockImplementation(async (path: string, options?: RequestInit) => {
      if (path.startsWith("/v1/operations/recent")) return { items: [] };
      if (path === "/v1/transfers" && options?.method === "POST") return postedTx();
      return {};
    });

    // The unresolved row explains the state and names the reviewed intent.
    expect(await screen.findByText(/Transfer · \$5\.00 to ...999999/)).toBeInTheDocument();
    expect(screen.getByText(/the answer never arrived/)).toBeInTheDocument();
    expect(screen.getByText(/Money can never move twice/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /Check again/ }));

    // The resolve action re-sent the IDENTICAL request: same key, same memo.
    await waitFor(() => expect(listPendingOperations("u1")).toEqual([]));
    const replay = lastReplay();
    expect(replay.key).toBe("k1");
    expect(replay.body).toEqual({
      fromAccountId: "a1",
      toIban: "DE99999999",
      amount: "5.00",
      memo: "Rent"
    });
    // The outcome is heard and the row is gone.
    expect(await screen.findByText("Transfer posted. $5.00 to ...999999.")).toBeInTheDocument();
    expect(screen.queryByText("Unresolved operations")).not.toBeInTheDocument();
  });

  it("clears an operation the server already recorded (the server list ends the unknown state)", async () => {
    seed();
    // The server recorded this keyed operation: its outcome was never really
    // unknown, so it must not be offered for a retry: the record is cleared
    // and nothing renders.
    renderCard([{ ...postedTx("k1"), originatingAccountId: "a1" }]);
    await waitFor(() => expect(listPendingOperations("u1")).toEqual([]));
    await waitFor(() => expect(screen.queryByText("Unresolved operations")).not.toBeInTheDocument());
    // No replay was sent.
    expect(vi.mocked(api).mock.calls.some((c) => c[0] === "/v1/transfers")).toBe(false);
  });

  it("keeps an operation listed when the retry is still ambiguous, with truthful copy", async () => {
    seed();
    renderCard([]);
    vi.mocked(api).mockImplementation(async (path: string, options?: RequestInit) => {
      if (path.startsWith("/v1/operations/recent")) return { items: [] };
      if (path === "/v1/transfers" && options?.method === "POST") {
        throw new ApiError(503, "Server Error", "boom");
      }
      return {};
    });
    await screen.findByText(/Transfer · \$5\.00 to ...999999/);

    await userEvent.click(screen.getByRole("button", { name: /Check again/ }));

    // Still unknown: the row stays, the record stays, and the copy says the
    // outcome is unknown and the retry can only post once.
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        /The server could not confirm whether your transfer went through/
      )
    );
    expect(listPendingOperations("u1")).toHaveLength(1);
    expect(screen.getByText(/Transfer · \$5\.00 to ...999999/)).toBeInTheDocument();
  });

  it("clears the record and surfaces the server's words on a definitive rejection", async () => {
    seed();
    renderCard([]);
    vi.mocked(api).mockImplementation(async (path: string, options?: RequestInit) => {
      if (path.startsWith("/v1/operations/recent")) return { items: [] };
      if (path === "/v1/transfers" && options?.method === "POST") {
        throw new ApiError(422, "Insufficient Funds", "Insufficient funds");
      }
      return {};
    });
    await screen.findByText(/Transfer · \$5\.00 to ...999999/);

    await userEvent.click(screen.getByRole("button", { name: /Check again/ }));

    await waitFor(() => expect(listPendingOperations("u1")).toEqual([]));
    // The rejection is heard (the row disappears with the record).
    expect(await screen.findByText("Insufficient funds")).toBeInTheDocument();
    expect(screen.queryByText("Unresolved operations")).not.toBeInTheDocument();
  });
});
