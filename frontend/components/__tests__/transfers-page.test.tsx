import { cleanup, render, screen, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import TransfersPage from "../../app/transfers/page";
import { ToastProvider } from "../feedback/toast";

vi.mock("../../lib/api", () => {
  class ApiError extends Error {
    status: number;
    title: string;
    constructor(status: number, title: string, detail: string) {
      super(detail || title || "failed");
      this.name = "ApiError";
      this.status = status;
      this.title = title;
    }
  }
  return {
    ApiError,
    api: vi.fn(),
    getToken: () => null,
    setToken: vi.fn(),
    clearToken: vi.fn(),
    expireSession: vi.fn()
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => "/transfers"
}));

vi.mock("next/link", () => {
  return {
    __esModule: true,
    default: ({
      children,
      href,
      ...props
    }: {
      children: React.ReactNode;
      href: string;
      [k: string]: unknown;
    }) => (
      <a href={typeof href === "string" ? href : ""} {...props}>
        {children}
      </a>
    )
  };
});

import { api } from "../../lib/api";

const checking = { id: "acc-checking", iban: "DE00000000000000000001", type: "CHECKING", balance: "50.00", status: "ACTIVE" };
const savings = { id: "acc-savings", iban: "DE00000000000000000002", type: "SAVINGS", balance: "200.00", status: "ACTIVE" };
const user = { id: "u1", email: "a@b.co", fullName: "Alice", role: "CUSTOMER", totpEnabled: false };

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderPage(client: QueryClient) {
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <TransfersPage />
      </ToastProvider>
    </QueryClientProvider>
  );
}

describe("transfers page HELD outcome", () => {
  it("says a review-threshold transfer is held, never that it posted", async () => {
    vi.mocked(api).mockImplementation(async (path: string, options?: RequestInit) => {
      if (path.startsWith("/v1/auth/me")) return user;
      if (path.startsWith("/v1/notifications/unread-count")) return { unread: 0 };
      if (path.startsWith("/v1/beneficiaries")) return [];
      if (path.startsWith("/v1/accounts") && !path.includes("deposit")) return [checking, savings];
      if (path === "/v1/transfers" && options?.method === "POST") {
        return {
          id: "tx-held",
          fromIban: checking.iban,
          toIban: savings.iban,
          amount: "10000.00",
          currency: "USD",
          memo: "big wire",
          status: "HELD",
          createdAt: new Date().toISOString(),
          flagged: true
        };
      }
      return { id: "u1" };
    });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPage(client);

    // Wait for the form to be ready (source account defaults once accounts load).
    const select = (await screen.findByLabelText("From account")) as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBe(2));

    await userEvent.type(await screen.findByLabelText("Recipient IBAN"), savings.iban);
    await userEvent.type(screen.getByLabelText("Amount (USD)"), "10000.00");
    await userEvent.type(screen.getByLabelText("Memo (optional)"), "big wire");
    // the first click opens REVIEW: nothing is sent yet.
    await userEvent.click(screen.getByRole("button", { name: /Review transfer/ }));

    // The review shows the exact frozen payload, and the submit button is gone
    // until confirmed (editing would exit review back to draft).
    expect(await screen.findByRole("region", { name: "Review your transfer" })).toBeInTheDocument();
    expect(screen.getByText("$10,000.00")).toBeInTheDocument();
    expect(screen.getByText(savings.iban)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Review transfer/ })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Confirm & send" }));

    // The receipt card must read HELD, and the toast must say the transfer is
    // awaiting review: the regression where a bare `status` reference resolved
    // to the legacy window.status global and toasted "Transfer posted.".
    await screen.findByRole("heading", { name: "Transfer submitted for review" });
    await waitFor(() =>
      expect(screen.getByText(/It is sent once an operator approves it/)).toBeTruthy()
    );
    expect(screen.queryByText("Transfer posted.")).toBeNull();

    // The durable receipt is reachable from the inline receipt.
    expect(screen.getByRole("link", { name: /Open permanent receipt/ })).toHaveAttribute(
      "href",
      "/transfers/receipt/tx-held"
    );
  });
});

describe("transfers page source account", () => {
  it("keeps the user's chosen source when accounts refetch", async () => {
    const paths: string[] = [];
    vi.mocked(api).mockImplementation(async (path: string) => {
      paths.push(path);
      if (path.startsWith("/v1/auth/me")) return user;
      if (path.startsWith("/v1/notifications/unread-count")) return { unread: 0 };
      if (path.startsWith("/v1/beneficiaries")) return [];
      if (path.startsWith("/v1/accounts") && !path.includes("deposit")) return [checking, savings];
      return { id: "u1" };
    });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPage(client);

    const select = (await screen.findByLabelText("From account")) as HTMLSelectElement;
    // Defaults to the first account on first load.
    await waitFor(() => expect(select.value).toBe(checking.id));

    // The user picks a different source account.
    await userEvent.selectOptions(select, savings.id);
    await waitFor(() => expect(select.value).toBe(savings.id));

    // An accounts refetch (e.g. after a deposit/transfer invalidation) must
    // NOT snap the selection back to the first account.
    await act(async () => {
      void client.invalidateQueries({ queryKey: ["accounts"] });
    });
    await waitFor(() =>
      expect(paths.filter((p) => p.startsWith("/v1/accounts") && !p.includes("deposit")).length).toBeGreaterThan(1)
    );
    expect(select.value).toBe(savings.id);
  });
});
