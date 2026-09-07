"use client";

import * as React from "react";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { ConfirmDialog } from "../ui/confirm-dialog";
import { Field } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { useResultToast } from "../feedback/use-result-toast";
import { classifyReversalFailure } from "../../lib/money-failure";
import { useReverseTransaction } from "../../lib/queries";
import { maskIban, usd } from "../../lib/format";
import type { Tx } from "../../lib/api-types";

/**
 * Server-enforced eligibility mirrored for the console (V29): only a POSTED
 * TRANSFER or DEPOSIT that has not already been reversed can be reversed -
 * HELD/CANCELLED never moved money, a REVERSAL row cannot itself be reversed,
 * and INTEREST corrections happen through the loan workflow, not here.
 */
export function canReverse(tx: Tx): boolean {
  return (tx.kind === "TRANSFER" || tx.kind === "DEPOSIT")
      && tx.status === "POSTED"
      && tx.reversalId == null;
}

/** One reversible row; returns null when the server would refuse. */
export function ReversalAction({ tx }: { tx: Tx }) {
  const reverse = useReverseTransaction();
  const [open, setOpen] = React.useState(false);
  const [reason, setReason] = React.useState("");
  const [failure, setFailure] = React.useState<string | null>(null);

  // Rejection copy renders INLINE in the dialog that stayed open (never a
  // corner toast behind the scrim), classified truthfully: a definitive 4xx
  // is the server's words; anything ambiguous says the state is unknown and
  // the posted list - not a blind retry - is the check, because a second
  // reversal of the same transaction is refused.
  useResultToast(reverse, {
    error: false,
    onFailure: (_message, err) => setFailure(classifyReversalFailure(err).message),
    success: {
      toast: (reversal) =>
        tx.kind === "DEPOSIT"
          ? {
              message:
                "Deposit reversed - " + usd(reversal.amount) + " was returned from "
                + (maskIban(reversal.fromIban) ?? "the account") + " to the funding rail."
            }
          : {
              message:
                "Reversed - " + usd(reversal.amount) + " moved back from "
                + maskIban(reversal.fromIban ?? "") + " to " + maskIban(reversal.toIban ?? "") + "."
            },
      run: () => {
        setOpen(false);
        setReason("");
        setFailure(null);
      }
    }
  });

  if (!canReverse(tx)) return null;

  const route =
    tx.kind === "DEPOSIT"
      ? maskIban(tx.toIban ?? "") + " (funded)"
      : maskIban(tx.fromIban ?? "") + " → " + maskIban(tx.toIban ?? "");
  return (
    <>
      <Button
        size="sm"
        variant="danger"
        disabled={reverse.isPending}
        onClick={() => {
          setFailure(null);
          setOpen(true);
        }}
      >
        {reverse.isPending ? "Reversing..." : "Reverse"}
      </Button>
      <ConfirmDialog
        open={open}
        title="Reverse this transaction?"
        confirmLabel="Reverse transaction"
        busy={reverse.isPending}
        confirmDisabled={reason.trim().length === 0}
        error={failure}
        body={
          <div className="space-y-3">
            <p className="text-content-soft">
              {usd(tx.amount)} on <span className="mono">{route}</span> will move back along
              the original legs. The original stays in history; the reversal is a new linked
              posting. One reversal per transaction is allowed.
            </p>
            <Field
              label="Reason"
              hint="Required - preserved on the reversal row and in the audit trail."
            >
              <Textarea
                value={reason}
                maxLength={255}
                placeholder="e.g. Customer reported an unauthorized transfer"
                onChange={(e) => setReason(e.target.value)}
              />
            </Field>
          </div>
        }
        onClose={() => {
          if (!reverse.isPending) {
            setOpen(false);
            setFailure(null);
          }
        }}
        onConfirm={() => {
          const clean = reason.trim();
          if (!clean) return; // the confirm button is disabled until a reason is typed
          setFailure(null);
          reverse.mutate({ id: tx.id, reason: clean });
        }}
      />
    </>
  );
}

/**
 * Row markers so reversal state is visible where operators act: a REVERSAL row
 * is labelled (its reason and parent live beside it), and a POSTED row whose
 * original is untouched but already reversed carries a small mark - its own
 * row never changes, so only the index can say so.
 */
export function ReversalMark({ tx }: { tx: Tx }) {
  if (tx.kind === "REVERSAL") {
    return <Badge tone="danger">Reversal</Badge>;
  }
  if (tx.reversalId) {
    return <Badge tone="neutral">Reversed</Badge>;
  }
  return null;
}
