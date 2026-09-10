import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import { ActivitySection } from "../../app/admin/activity-section";
import { ToastProvider } from "../feedback/toast";
import type { Tx } from "../../lib/api-types";

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    api: vi.fn()
  };
});

import { api } from "../../lib/api";

function tx(id: string, overrides: Partial<Tx> = {}): Tx {
  return {
    id,
    fromIban: "DE11111111111111111111",
    toIban: "DE99999999999999999999",
    amount: "90.00",
    currency: "USD",
    memo: null,
    kind: "TRANSFER",
    status: "POSTED",
    createdAt: "2026-06-15T10:00:00Z",
    postedAt: "2026-06-15T10:00:00Z",
    flagged: false,
    reviewed: false,
    reversesTransactionId: null,
    reversalReason: null,
    reversalId: null,
    idempotencyKey: null,
    ...overrides
  };
}

function renderSection(rows: Tx[]) {
  vi.mocked(api).mockImplementation(async (path: string) => {
    if (path.startsWith("/v1/admin/transactions")) {
      return { content: rows, totalPages: 1, totalElements: rows.length, number: 0 };
    }
    return {};
  });
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } }
  });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <ActivitySection />
      </ToastProvider>
    </QueryClientProvider>
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("ActivitySection: the console reversal workbench", () => {
  it("marks a reversal row with its parent and reason, and its original as Reversed", async () => {
    renderSection([
      tx("r1", {
        kind: "REVERSAL",
        fromIban: "DE99999999999999999999",
        toIban: "DE11111111111111111111",
        reversesTransactionId: "t1",
        reversalReason: "Customer confirmed double charge"
      }),
      tx("t1", { reversalId: "r1" })
    ]);

    // The reversal row is labelled, linked to its parent, and quotes the reason.
    expect(await screen.findByText("Reversal")).toBeInTheDocument();
    expect(screen.getByText(/Reverses t1: “Customer confirmed double charge”/)).toBeInTheDocument();
    // The original row is untouched history but visibly marked as reversed.
    expect(screen.getByText("Reversed")).toBeInTheDocument();
    // Neither row offers a second reversal.
    expect(screen.queryByRole("button", { name: "Reverse" })).not.toBeInTheDocument();
  });

  it("offers Reverse only on a posted, not-yet-reversed transfer or deposit", async () => {
    renderSection([
      tx("ok", {}),
      tx("held", { status: "HELD", postedAt: null }),
      tx("interest", { kind: "INTEREST", toIban: null, postedAt: "2026-06-15T10:00:00Z" })
    ]);

    expect(await screen.findByRole("button", { name: "Reverse" })).toBeInTheDocument();
    // Exactly one eligible row → exactly one Reverse action.
    expect(screen.getAllByRole("button", { name: "Reverse" })).toHaveLength(1);
  });
});
