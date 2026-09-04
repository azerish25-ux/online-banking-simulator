import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import AccountDetailPage from "../../app/accounts/[id]/page";
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
  usePathname: () => "/accounts/acc-1"
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

// eslint-disable-next-line import/order
import { api } from "../../lib/api";

const VIEWED = "DE00000000000000000001";
const OTHER = "DE00000000000000000002";

const user = { id: "u1", email: "a@b.co", fullName: "Alice", role: "CUSTOMER", totpEnabled: false };
const account = { id: "acc-1", iban: VIEWED, type: "CHECKING", balance: "50.0000", status: "ACTIVE" };

const page = {
  content: [
    {
      id: "t-in",
      fromIban: OTHER,
      toIban: VIEWED,
      amount: "500.0000",
      currency: "USD",
      memo: "Salary",
      kind: "TRANSFER",
      status: "POSTED",
      createdAt: "2026-09-01T12:00:00Z",
      flagged: false,
      reviewed: true
    },
    {
      id: "t-out",
      fromIban: VIEWED,
      toIban: OTHER,
      amount: "600.0000",
      currency: "USD",
      memo: "Rent",
      kind: "TRANSFER",
      status: "POSTED",
      createdAt: "2026-09-02T12:00:00Z",
      flagged: false,
      reviewed: true
    }
  ],
  totalPages: 1,
  number: 0
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("account recent-activity amounts", () => {
  it("signs each row by its direction from the viewed account's ledger", async () => {
    vi.mocked(api).mockImplementation(async (path: string) => {
      if (path.startsWith("/v1/auth/me")) return user;
      if (path.startsWith("/v1/notifications/unread-count")) return { unread: 0 };
      if (path.startsWith("/v1/transactions")) return page;
      if (path.endsWith("/cards")) return [];
      if (path.startsWith("/v1/accounts/acc-1")) return account;
      return { id: "u1" };
    });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <ToastProvider>
          <AccountDetailPage params={{ id: "acc-1" }} />
        </ToastProvider>
      </QueryClientProvider>
    );

    // Inbound (the viewed account is the destination) reads as a credit;
    // outbound (the viewed account is the sender) reads as a debit - the
    // direction is unambiguous even with no From/To columns on this page.
    expect(await screen.findByText("+$500.00")).toBeInTheDocument();
    expect(screen.getByText("-$600.00")).toBeInTheDocument();
    // The memo column alone no longer has to carry the direction.
    expect(screen.getByText("Salary")).toBeInTheDocument();
    expect(screen.getByText("Rent")).toBeInTheDocument();
  });
});
