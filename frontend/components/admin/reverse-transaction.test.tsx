import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import { canReverse, ReversalAction, ReversalMark } from "./reverse-transaction";
import { ToastProvider } from "../feedback/toast";
import { ApiError } from "../../lib/api";
import type { Tx } from "../../lib/api-types";

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    api: vi.fn()
  };
});

import { api } from "../../lib/api";

function tx(overrides: Partial<Tx> = {}): Tx {
  return {
    id: "t1",
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
    ...overrides
  };
}

function renderAction(row: Tx) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } }
  });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <ReversalAction tx={row} />
      </ToastProvider>
    </QueryClientProvider>
  );
}

function renderMark(row: Tx) {
  return render(<ReversalMark tx={row} />);
}

/** The single POSTed reverse request: path and parsed body. */
function lastReverseCall() {
  const call = vi.mocked(api).mock.calls.find((c) =>
    typeof c[0] === "string" && c[0].startsWith("/v1/admin/transactions/") && (c[1]?.method ?? "") === "POST");
  expect(call).toBeTruthy();
  const init = call![1] as RequestInit;
  return { path: call![0] as string, body: JSON.parse(String(init.body)) };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("canReverse - server eligibility mirrored for the console", () => {
  it("offers a posted TRANSFER/DEPOSIT that has not already been reversed", () => {
    expect(canReverse(tx())).toBe(true);
    expect(canReverse(tx({ kind: "DEPOSIT", fromIban: null, toIban: "DE11111111111111111111" }))).toBe(true);
  });

  it("refuses intents that never moved money, engine rows, reversals and repeats", () => {
    expect(canReverse(tx({ status: "HELD" }))).toBe(false);
    expect(canReverse(tx({ status: "CANCELLED" }))).toBe(false);
    expect(canReverse(tx({ kind: "INTEREST" }))).toBe(false);
    expect(canReverse(tx({ kind: "REVERSAL", reversesTransactionId: "t0", reversalReason: "ops" }))).toBe(false);
    expect(canReverse(tx({ reversalId: "r1" }))).toBe(false);
  });
});

describe("ReversalMark - reversed state is visible in the list", () => {
  it("labels a REVERSAL row and marks its (untouched) original as Reversed", () => {
    renderMark(tx({ kind: "REVERSAL", reversesTransactionId: "t0", reversalReason: "Wrong amount" }));
    expect(screen.getByText("Reversal")).toBeInTheDocument();

    cleanup();
    renderMark(tx({ reversalId: "r1" }));
    expect(screen.getByText("Reversed")).toBeInTheDocument();

    cleanup();
    renderMark(tx());
    expect(screen.queryByText("Reversal")).not.toBeInTheDocument();
    expect(screen.queryByText("Reversed")).not.toBeInTheDocument();
  });
});

describe("ReversalAction dialog", () => {
  it("renders nothing when the server would refuse the reversal", () => {
    renderAction(tx({ status: "HELD" }));
    expect(screen.queryByRole("button", { name: /Reverse/ })).not.toBeInTheDocument();
  });

  it("requires a reason before the confirm is enabled", async () => {
    renderAction(tx());
    await userEvent.click(screen.getByRole("button", { name: "Reverse" }));

    const confirm = screen.getByRole("button", { name: "Reverse transaction" });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByRole("textbox", { name: /Reason/ }), "   ");
    expect(confirm).toBeDisabled();
    await userEvent.clear(screen.getByRole("textbox", { name: /Reason/ }));
    await userEvent.type(screen.getByRole("textbox", { name: /Reason/ }), "Customer confirmed double charge");
    expect(confirm).toBeEnabled();
  });

  it("submits the reason and hears the authoritative reversal result", async () => {
    vi.mocked(api).mockResolvedValue({
      ...tx({
        kind: "REVERSAL",
        fromIban: "DE99999999999999999999",
        toIban: "DE11111111111111111111",
        reversesTransactionId: "t1",
        reversalReason: "Customer confirmed double charge"
      }),
      id: "r1"
    });
    renderAction(tx());
    await userEvent.click(screen.getByRole("button", { name: "Reverse" }));
    await userEvent.type(
      screen.getByRole("textbox", { name: /Reason/ }),
      "Customer confirmed double charge"
    );
    await userEvent.click(screen.getByRole("button", { name: "Reverse transaction" }));

    const sent = lastReverseCall();
    expect(sent.path).toBe("/v1/admin/transactions/t1/reverse");
    expect(sent.body).toEqual({ reason: "Customer confirmed double charge" });
    // The dialog closes and the outcome is the server's reversal row, not a guess.
    await waitFor(() =>
      expect(screen.queryByText("Reverse this transaction?")).not.toBeInTheDocument()
    );
    expect(await screen.findByText(/Reversed - \$90\.00 moved back from/)).toBeInTheDocument();
  });

  it("shows a definitive refusal inline and keeps the dialog open", async () => {
    vi.mocked(api).mockRejectedValue(
      new ApiError(400, "Rejected", "This transaction has already been reversed")
    );
    renderAction(tx());
    await userEvent.click(screen.getByRole("button", { name: "Reverse" }));
    await userEvent.type(screen.getByRole("textbox", { name: /Reason/ }), "Second try");
    await userEvent.click(screen.getByRole("button", { name: "Reverse transaction" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This transaction has already been reversed"
    );
    expect(screen.getByText("Reverse this transaction?")).toBeInTheDocument();
  });

  it("treats an ambiguous failure as unknown state and points at the list, never a retry", async () => {
    vi.mocked(api).mockRejectedValue(new TypeError("Failed to fetch"));
    renderAction(tx());
    await userEvent.click(screen.getByRole("button", { name: "Reverse" }));
    await userEvent.type(screen.getByRole("textbox", { name: /Reason/ }), "Network dropped");
    await userEvent.click(screen.getByRole("button", { name: "Reverse transaction" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /The server could not confirm whether the reversal was recorded/
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      /a second reversal of the same transaction would be refused/
    );
    // The dialog stays so the operator can see the row still needs a decision.
    expect(screen.getByText("Reverse this transaction?")).toBeInTheDocument();
  });
});
