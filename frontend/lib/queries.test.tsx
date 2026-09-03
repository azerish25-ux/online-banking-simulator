import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { queryKeys, useAccounts, useUnreadCount } from "./queries";
import { setToken } from "./api";

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
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

afterEach(() => {
  vi.clearAllMocks();
  document.cookie = "bank_token=; max-age=0";
});

function Probe({ hook }: { hook: () => { data?: unknown; isLoading: boolean; error?: unknown } }) {
  const result = hook();
  if (result.isLoading) return <p>loading</p>;
  if (result.error) return <p role="alert">error</p>;
  return <pre>{JSON.stringify(result.data)}</pre>;
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
    expect(queryKeys.transactions("a1", 0, "2026-01-01")).toEqual(["transactions", "a1", 0, "2026-01-01", ""]);
    expect(queryKeys.summary("a1", 6)).toEqual(["summary", "a1", 6]);
  });
});
