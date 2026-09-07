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
  useRouter: () => ({ push: vi.fn() }),
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
    if (path.startsWith("/v1/accounts/") && path.includes("/summary")) return [];
    if (path.startsWith("/v1/transactions")) return { content: [], totalPages: 0, number: 0 };
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

describe("dashboard loan card", () => {
  it("presents a drawn loan as an Outstanding-loan liability, not a bare negative", async () => {
    const checking = { id: "acc-1", iban: "DE00000000000000000001", type: "CHECKING", balance: "1250.0000", status: "ACTIVE" };
    const loan = { id: "acc-2", iban: "DE00000000000000000002", type: "LOAN", balance: "-600.0000", status: "ACTIVE" };
    renderDashboard([checking, loan]);

    // The debt reads as a labeled rose magnitude - never a bare "-$600.00".
    expect(await screen.findByText("$600.00")).toBeInTheDocument();
    expect(screen.getByText("Outstanding loan (amount you owe)")).toBeInTheDocument();
    expect(screen.queryByText("-$600.00")).not.toBeInTheDocument();

    // Semantics intact: the net-position figure still subtracts the debt
    // (1,250 - 600 = 650) rather than treating it as a credit, and the label
    // names the scope and the loan treatment.
    expect(
      screen.getByText("Net position across all your accounts. Loans count as debt.")
    ).toBeInTheDocument();
    expect(screen.getByText("$650.00")).toBeInTheDocument();
  });

  it("leaves an undrawn loan on the plain balance treatment", async () => {
    const checking = { id: "acc-1", iban: "DE00000000000000000001", type: "CHECKING", balance: "50.0000", status: "ACTIVE" };
    const loan = { id: "acc-2", iban: "DE00000000000000000002", type: "LOAN", balance: "0.0000", status: "ACTIVE" };
    renderDashboard([checking, loan]);

    expect(await screen.findByText("$0.00")).toBeInTheDocument();
    expect(screen.getByText("DE00000000000000000002")).toBeInTheDocument();
    expect(screen.queryByText(/Outstanding loan/)).not.toBeInTheDocument();
  });
});
