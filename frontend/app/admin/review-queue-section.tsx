"use client";

import * as React from "react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { useToast } from "../../components/feedback/toast";
import { useAdminReviewQueue, useDeclineTransaction, useReviewTransaction } from "../../lib/queries";
import { fmtDate, usd } from "../../lib/format";

/**
 * Operator queue: HELD rows are intents - approving settles the transfer,
 * declining cancels it, so no money moves until an operator decides. A
 * flagged-but-POSTED row (large deposit) already credited, so it only needs
 * acknowledging to leave the queue.
 */
export function ReviewQueueSection() {
  const { push } = useToast();
  const queue = useAdminReviewQueue();
  const review = useReviewTransaction();
  const decline = useDeclineTransaction();
  const rows = queue.data ?? [];
  const busy = review.isPending || decline.isPending;

  React.useEffect(() => {
    if (review.isError) push(review.error.message, "error");
    if (decline.isError) push(decline.error.message, "error");
  }, [review.isError, review.error, decline.isError, decline.error, push]);

  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <CardTitle>Review queue</CardTitle>
        <Badge tone={rows.length > 0 ? "danger" : "success"}>{rows.length} open</Badge>
      </div>
      {rows.length === 0 ? (
        <CardDescription>No flagged activity awaiting review.</CardDescription>
      ) : (
        <ul className="space-y-2">
          {rows.map((t) => {
            const isHeld = t.status === "HELD";
            const isDeposit = t.kind === "DEPOSIT" || !t.fromIban;
            return (
              <li key={t.id} className="rounded-md border border-line p-3">
                <div className="flex items-center justify-between gap-2">
                  <div className="text-sm">
                    <span className="caps normal-case text-slate-400">
                      {isHeld ? "Transfer held · awaiting approval" : isDeposit ? "Deposit flagged · credited" : "Transfer flagged · settled"}
                    </span>
                    <span className="mono ml-2">
                      {isDeposit
                        ? (t.toIban ? "..." + t.toIban.slice(-6) : "-")
                        : (t.fromIban ? "..." + t.fromIban.slice(-6) : "-") + " → " + (t.toIban ? "..." + t.toIban.slice(-6) : "-")}
                    </span>
                    <span className="ml-2 font-semibold tabular-nums">{usd(t.amount)}</span>
                    <span className="muted ml-2 text-xs">{fmtDate(t.createdAt)}</span>
                  </div>
                  {isHeld ? (
                    <div className="flex shrink-0 gap-2">
                      <Button
                        size="sm"
                        variant="danger"
                        disabled={busy}
                        onClick={() => decline.mutate(t.id, { onSuccess: () => push("Declined - no money moved.", "success") })}
                      >
                        Decline
                      </Button>
                      <Button
                        size="sm"
                        disabled={busy}
                        onClick={() => review.mutate(t.id, { onSuccess: () => push("Approved - transfer settled.", "success") })}
                      >
                        Approve
                      </Button>
                    </div>
                  ) : (
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={busy}
                      onClick={() => review.mutate(t.id, { onSuccess: () => push("Flag acknowledged.", "success") })}
                    >
                      Acknowledge
                    </Button>
                  )}
                </div>
                {isHeld && (
                  <p className="muted mt-1 text-xs">
                    Sender funds are re-checked on approval; declining cancels the intent.
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
