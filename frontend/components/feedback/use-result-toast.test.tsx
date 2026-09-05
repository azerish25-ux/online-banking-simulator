import { renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import { useResultToast, type ResultToastFeedback } from "./use-result-toast";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

vi.mock("./toast", () => ({
  useToast: () => ({ push: pushMock })
}));

type Result = {
  status: "idle" | "pending" | "success" | "error";
  error: Error | null;
  data: unknown;
};

type Props = {
  result: Result;
  feedback?: ResultToastFeedback<unknown, Error>;
};

const idle: Result = { status: "idle", error: null, data: undefined };
const fail = (message: string): Result => ({ status: "error", error: new Error(message), data: undefined });
const ok = (data: unknown): Result => ({ status: "success", error: null, data });

beforeEach(() => {
  pushMock.mockClear();
});

it("surfaces the error message once per failed attempt, never on re-renders", () => {
  const { rerender } = renderHook(({ result }: Props) => useResultToast(result), {
    initialProps: { result: idle }
  });

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: fail("Insufficient funds") });
  expect(pushMock).toHaveBeenCalledTimes(1);
  expect(pushMock).toHaveBeenCalledWith("Insufficient funds", "error");

  // A re-render while still failed must not repeat the toast...
  rerender({ result: fail("Insufficient funds") });
  expect(pushMock).toHaveBeenCalledTimes(1);

  // ...but a second attempt that fails again gets its own toast.
  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: fail("Insufficient funds") });
  expect(pushMock).toHaveBeenCalledTimes(2);
});

it("honors a custom error spec and tone", () => {
  const { rerender } = renderHook(({ result }: Props) => useResultToast(result, {
    error: { message: "Couldn't load your beneficiaries - type the IBAN manually.", tone: "error" }
  }), { initialProps: { result: idle } });

  rerender({ result: fail("boom") });
  expect(pushMock).toHaveBeenCalledTimes(1);
  expect(pushMock).toHaveBeenCalledWith("Couldn't load your beneficiaries - type the IBAN manually.", "error");
});

it("stays silent when a failure is deliberately suppressed", () => {
  const { rerender } = renderHook(({ result }: Props) => useResultToast(result, { error: false }), {
    initialProps: { result: idle }
  });

  rerender({ result: fail("boom") });
  expect(pushMock).not.toHaveBeenCalled();
});

it("fires a success toast once and never re-fires when data identity changes", () => {
  const { rerender } = renderHook(
    ({ result }: Props) => useResultToast(result, {
      success: { toast: { message: "Account opened." } }
    }),
    { initialProps: { result: idle } }
  );

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: ok({ id: "a" }) });
  expect(pushMock).toHaveBeenCalledTimes(1);
  expect(pushMock).toHaveBeenCalledWith("Account opened.", "success");

  // A later refetch handing back fresh data while still successful...
  rerender({ result: ok({ id: "a", fresh: true }) });
  // ...or another component render with the same status, must stay silent.
  rerender({ result: ok({ id: "a", fresh: true }) });
  expect(pushMock).toHaveBeenCalledTimes(1);
});

it("computes the toast from the result and picks the tone per outcome", () => {
  const { rerender } = renderHook(
    ({ result }: Props) => useResultToast(result, {
      success: {
        toast: (data) =>
          data && typeof data === "object" && "held" in data && (data as { held: boolean }).held
            ? { message: "Transfer submitted for review.", tone: "info" }
            : { message: "Transfer posted." }
      }
    }),
    { initialProps: { result: idle } }
  );

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: ok({ held: true }) });
  expect(pushMock).toHaveBeenCalledWith("Transfer submitted for review.", "info");

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: ok({ held: false }) });
  expect(pushMock).toHaveBeenCalledWith("Transfer posted.", "success");
});

it("hears each failure through onFailure without corner-toasting (error: false)", () => {
  const hear = vi.fn();
  const { rerender } = renderHook(
    ({ result }: Props) => useResultToast(result, { error: false, onFailure: hear }),
    { initialProps: { result: idle } }
  );

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: fail("Deposit exceeds the per-transaction limit") });
  expect(pushMock).not.toHaveBeenCalled(); // no corner toast - rendered inline instead
  expect(hear).toHaveBeenCalledTimes(1);
  expect(hear).toHaveBeenCalledWith("Deposit exceeds the per-transaction limit");

  // A re-render while still failed must not repeat it; a second failed
  // attempt gets its own hearing.
  rerender({ result: fail("Deposit exceeds the per-transaction limit") });
  expect(hear).toHaveBeenCalledTimes(1);
  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: fail("Deposit exceeds the per-transaction limit") });
  expect(hear).toHaveBeenCalledTimes(2);
});

it("does not fire onFailure on success, and shares the spec message when one is set", () => {
  const hear = vi.fn();
  const { rerender } = renderHook(
    ({ result }: Props) =>
      useResultToast(result, {
        error: { message: "Couldn't do that." },
        onFailure: hear,
        success: { toast: { message: "Done." } }
      }),
    { initialProps: { result: idle } }
  );

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: ok("payload") });
  expect(hear).not.toHaveBeenCalled();

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: fail("raw boom") });
  expect(pushMock).toHaveBeenCalledWith("Couldn't do that.", "error");
  expect(hear).toHaveBeenCalledWith("Couldn't do that."); // same words as the toast
});

it("runs the latest side-effect exactly once per success, with the result", () => {
  const run = vi.fn();
  const { rerender } = renderHook(
    ({ result }: Props) => useResultToast(result, { success: { run } }),
    { initialProps: { result: idle } }
  );

  rerender({ result: { status: "pending", error: null, data: undefined } });
  rerender({ result: ok("payload") });
  expect(run).toHaveBeenCalledTimes(1);
  expect(run).toHaveBeenCalledWith("payload");

  rerender({ result: ok("payload") });
  expect(run).toHaveBeenCalledTimes(1);
});
