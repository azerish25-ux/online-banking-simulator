import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import DashboardPage from "../../app/dashboard/page";
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
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/"
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

const user = { id: "u1", email: "a@b.co", fullName: "Alice", role: "CUSTOMER", totpEnabled: false };

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderDashboard(accounts: unknown[]) {
  vi.mocked(api).mockImplementation(async (path: string) => {
    if (path.startsWith("/v1/auth/me")) return user;
    if (path.startsWith("/v1/notifications/unread-count")) return { unread: 0 };
    if (path.startsWith("/v1/transactions")) return { items: [], total: 0, nextCursor: null };
    if (path.includes("/summary")) return [];
    if (path.startsWith("/v1/accounts")) return accounts;
    return { id: "u1" };
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <DashboardPage />
      </ToastProvider>
    </QueryClientProvider>
  );
}

const checking = (id: string, iban: string, balance: string, status = "ACTIVE") => ({
  id,
  iban,
  type: "CHECKING",
  balance,
  status
});

const loan = (overrides: Record<string, unknown>) => ({
  id: "acc-2",
  iban: "DE00000000000000000002",
  type: "LOAN",
  balance: "0.0000",
  status: "ACTIVE",
  principalOwed: "0.0000",
  interestOwed: "0.0000",
  totalOwed: "0.0000",
  availableCredit: "1000.0000",
  ...overrides
});

describe("dashboard account list and summary strip", () => {
  it("separates available funds from loan debt and shows the policy-derived loan split", async () => {
    const accounts = [
      checking("acc-1", "DE00000000000000000001", "1250.0000"),
      loan({ balance: "-600.0000", principalOwed: "500.0000", interestOwed: "100.0000", totalOwed: "600.0000", availableCredit: "400.0000" })
    ];
    renderDashboard(accounts);

    // Desktop table + the narrow stacked list both render (CSS chooses by
    // viewport), so amount/name assertions are multiplicity-safe.
    const many = (text: string | RegExp) => expect(screen.getAllByText(text).length).toBeGreaterThan(0);

    // The summary strip names the scope: spendable funds and debt are two
    // separate figures: net position is never the headline.
    expect(await screen.findByText("Available funds")).toBeInTheDocument();
    many("$1,250.00");
    expect(screen.getByText("Loan debt")).toBeInTheDocument();
    many("$600.00");

    // The loan row shows every authoritative value: principal, interest,
    // total owed, available credit: with policy-derived figures, never a
    // bare "-$600.00" balance.
    many("Principal $500.00 · Interest $100.00 · Credit left $400.00");
    expect(screen.queryByText("-$600.00")).not.toBeInTheDocument();
    // The accounts table lists names and masked identifiers.
    many("Checking");
    many("Loan");
    many("...000001");
  });

  it("leaves an undrawn loan with nothing owed and its full credit available", async () => {
    const accounts = [
      checking("acc-1", "DE00000000000000000001", "50.0000"),
      loan({})
    ];
    renderDashboard(accounts);

    const many = (text: string | RegExp) => expect(screen.getAllByText(text).length).toBeGreaterThan(0);
    expect(await screen.findByText("Available funds")).toBeInTheDocument();
    // Undrawn loan: debt cell reports no debt and the row names the credit.
    expect(screen.getByText("No amount owed on your loans")).toBeInTheDocument();
    many("Nothing owed · Credit $1,000.00 available");
    expect(screen.queryByText(/Outstanding loan/)).not.toBeInTheDocument();
  });

  it("never counts a frozen account as freely available money", async () => {
    const accounts = [
      checking("acc-1", "DE00000000000000000001", "100.0000"),
      checking("acc-3", "DE00000000000000000003", "500.0000", "FROZEN")
    ];
    renderDashboard(accounts);

    const many = (text: string | RegExp) => expect(screen.getAllByText(text).length).toBeGreaterThan(0);
    // The frozen $500 is on deposit (booked) but NOT spendable: available
    // funds exclude it while the booked figure names the split.
    expect(await screen.findByText("Available funds")).toBeInTheDocument();
    many("$100.00");
    expect(screen.getByText("Booked deposits")).toBeInTheDocument();
    many("$600.00");
    expect(screen.getByText("1 frozen account is on deposit but not spendable")).toBeInTheDocument();
    many("Frozen: not spendable");
    many("Frozen");
  });

  it("treats a sub-cent obligation as debt and renders its exact amount", async () => {
    // A $0.0001 debt rounds to $0.00 in cents: the dashboard must still
    // detect it from the authoritative decimal and display the exact amount
    // instead of a zero-dollar figure.
    const accounts = [
      checking("acc-1", "DE00000000000000000001", "0.0000"),
      loan({ balance: "-0.0001", principalOwed: "0.0001", interestOwed: "0.0000", totalOwed: "0.0001", availableCredit: "999.9999" })
    ];
    renderDashboard(accounts);

    expect(await screen.findByText("Loan debt")).toBeInTheDocument();
    // The strip's caption proves the debt was detected at full precision.
    expect(screen.getByText("Principal and interest you owe")).toBeInTheDocument();
    // The row shows the exact four-decimal obligation.
    expect(screen.getAllByText("$0.0001").length).toBeGreaterThan(0);
  });
});
