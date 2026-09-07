"use client";

import * as React from "react";
import { useResultToast } from "../feedback/use-result-toast";
import { Button } from "../ui/button";
import { Field, Input } from "../ui/input";
import { Modal } from "../ui/modal";
import { Select } from "../ui/select";
import { useDeposit } from "../../lib/queries";
import { accountLabel, maskIban, usd } from "../../lib/format";
import { classifyMoneyFailure } from "../../lib/money-failure";
import { depositSchema } from "../../lib/validation";
import type { Account } from "../../lib/api-types";

/**
 * The deposit rail dialog - where simulated funding lands. Owns everything
 * about a deposit: the destination picker (defaulting to the first ACTIVE
 * account, frozen ones disabled), the amount with its inline validation, the
 * submit state, and the success/error toasts. The page only says whether it
 * is open and hands over the account list, so every deposit-rule change
 * lives here instead of in the dashboard.
 */
export function DepositDialog({
  open,
  onClose,
  accounts
}: {
  open: boolean;
  onClose: () => void;
  accounts: Account[];
}) {
  const deposit = useDeposit();
  const { resetIdempotencyKey } = deposit;

  const [amount, setAmount] = React.useState("100.00");
  // One inline slot under the amount field for both shapes of rejection:
  // client-schema errors (set at submit) and server rejections (a deposit
  // over the cap fails with the bad amount still in the field - the message
  // belongs beside it, not in a corner toast behind the scrim).
  const [error, setError] = React.useState<string | undefined>(undefined);
  // Where a deposit lands. Defaults to the first ACTIVE account when the list
  // arrives; the picker can change it, so funding a SAVINGS or repaying a
  // LOAN no longer depends on which account happens to be oldest.
  const [targetId, setTargetId] = React.useState("");

  const activeAccounts = accounts.filter((a) => a.status === "ACTIVE");
  const target =
    accounts.find((a) => a.id === targetId && a.status === "ACTIVE") ?? activeAccounts[0];

  React.useEffect(() => {
    if (!targetId && activeAccounts.length > 0) {
      setTargetId(activeAccounts[0].id);
    }
  }, [activeAccounts, targetId]);

  // A rejection from a previous visit must not greet the user on a fresh
  // attempt: clear the inline slot each time the dialog opens.
  React.useEffect(() => {
    if (open) setError(undefined);
  }, [open]);

  // Result → feedback wiring lives in the shared owner. Failures render
  // inline under the amount field (above); success closes the dialog via the
  // latest `onClose`, and its copy reads the amount through a ref (set at
  // submit time) - the owner fires exactly once, so a refetch after the
  // deposit can never replay this toast.
  const lastAmount = React.useRef("0");
  // Editing a deposit that was already attempted is a NEW intent (F06): the
  // outstanding idempotency key made retries of THAT deposit safe - it must
  // not silently carry an edited amount to the server. Track the attempted
  // intent and reset the key only when the user moves away from it, so a
  // reload-recovered key survives the dialog reopening untouched.
  const attempted = React.useRef<{ account: string; amount: string } | null>(null);
  React.useEffect(() => {
    const tried = attempted.current;
    const targetIdNow = target?.id ?? "";
    if (tried && (tried.account !== targetIdNow || tried.amount !== amount)) {
      attempted.current = null;
      resetIdempotencyKey();
    }
  }, [amount, target, resetIdempotencyKey]);
  useResultToast(deposit, {
    error: false,
    // Truthful copy for the inline slot: a definitive rejection keeps the
    // server's words; an interrupted/ambiguous one (network, 5xx, 429, 409)
    // says the deposit is saved and a retry checks the server instead of
    // double-posting.
    onFailure: (message, error) => {
      const classified = classifyMoneyFailure("deposit", error).message;
      setError(classified || message);
    },
    success: {
      // The deposit answer nests the account under the recoverable operation
      // identity (F06 lifecycle), so the toast unwraps it.
      toast: (result) => ({
        message:
          "Deposited " + usd(lastAmount.current) + " to " + result.account.type
          + " " + (maskIban(result.account.iban) ?? "") + "."
      }),
      run: () => {
        onClose();
        setError(undefined);
      }
    }
  });

  function submit() {
    if (!target) return;
    const parsed = depositSchema.safeParse({ amount });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? "Enter a valid amount");
      return;
    }
    setError(undefined);
    lastAmount.current = amount;
    attempted.current = { account: target.id, amount };
    deposit.mutate({ accountId: target.id, amount });
  }

  return (
    <Modal open={open} onClose={onClose} title="Deposit funds">
      <form onSubmit={(e) => { e.preventDefault(); submit(); }} className="space-y-4">
        {activeAccounts.length > 1 && (
          <Field label="Deposit to">
            <Select
              value={target?.id ?? ""}
              onChange={(e) => setTargetId(e.target.value)}
            >
              {accounts.map((a) => (
                <option key={a.id} value={a.id} disabled={a.status !== "ACTIVE"}>
                  {accountLabel(a, a.balance)}{a.status !== "ACTIVE" ? " (frozen)" : ""}
                </option>
              ))}
            </Select>
          </Field>
        )}
        <Field label="Amount (USD)" hint="Simulated funding, credited instantly." error={error}>
          <Input
            value={amount}
            onChange={(e) => {
              setAmount(e.target.value);
              setError(undefined);
            }}
            inputMode="decimal"
          />
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" disabled={deposit.isPending} onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={deposit.isPending || !target}>
            {deposit.isPending ? "Depositing..." : "Deposit"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
