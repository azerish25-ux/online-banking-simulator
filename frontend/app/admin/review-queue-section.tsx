"use client";

import * as React from "react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Pager } from "../../components/ui/pager";
import { useResultToast } from "../../components/feedback/use-result-toast";
import { useAdminReviewQueue, useDeclineTransaction, useReviewTransaction } from "../../lib/queries";
import { fmtDate, maskIban, usd } from "../../lib/format";

/**
 * Operator queue: HELD rows are intents - approving settles the transfer,
 * declining cancels it, so no money moves until an operator decides. A
 * flagged-but-POSTED row (large deposit) already credited, so it only needs
 * acknowledging to leave the queue.
 */
export function ReviewQueueSection() {
  const [queuePage, setQueuePage] = React.useState(0);
  const queue = useAdminReviewQueue(queuePage);
  const review = useReviewTransaction();
  const decline = useDeclineTransaction();
  const rows = queue.data?.content ?? [];
  const totalPages = queue.data?.totalPages ?? 1;
  const openCount = queue.data?.totalElements ?? rows.length;
  const busy = review.isPending || decline.isPending;

  // Clearing the last row of a page steps back so the operator is not left
  // staring at an empty page while older items still await review.
  const rowsAtPageStart = React.useRef(0);
  React.useEffect(() => {
    if (rows.length === 0 && rowsAtPageStart.current > 0 && queuePage > 0) {
      setQueuePage((p) => p - 1);
    }
    rowsAtPageStart.current = rows.length;
  }, [rows.length, queuePage]);

  // Result → toast wiring lives in the shared owner. Approve (HELD row) and
  // acknowledge (already-credited flagged row) settle through the same review
  // mutation, and their success copy differs by the clicked action, not by
  // the response - so the button records what it asked for in a ref (same
  // pattern as the transfer page's last intent), read at toast time.
  const lastReviewAction = React.useRef<"approve" | "acknowledge">("approve");
  useResultToast(review, {
    success: {
      toast: () => ({
        message:
          lastReviewAction.current === "acknowledge" ? "Flag acknowledged." : "Approved - transfer settled."
      })
    }
  });
  useResultToast(decline, {
    success: { toast: { message: "Declined - no money moved." } }
  });

  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <CardTitle>Review queue</CardTitle>
        <Badge tone={openCount > 0 ? "danger" : "success"}>{openCount} open</Badge>
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
                    <span className="label text-content-muted">
                      {isHeld ? "Transfer held · awaiting approval" : isDeposit ? "Deposit flagged · credited" : "Transfer flagged · settled"}
                    </span>
                    <span className="mono ml-2">
                      {isDeposit
                        ? (maskIban(t.toIban) ?? "-")
                        : (maskIban(t.fromIban) ?? "-") + " → " + (maskIban(t.toIban) ?? "-")}
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
                        onClick={() => decline.mutate(t.id)}
                      >
                        Decline
                      </Button>
                      <Button
                        size="sm"
                        disabled={busy}
                        onClick={() => {
                          lastReviewAction.current = "approve";
                          review.mutate(t.id);
                        }}
                      >
                        Approve
                      </Button>
                    </div>
                  ) : (
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={busy}
                      onClick={() => {
                        lastReviewAction.current = "acknowledge";
                        review.mutate(t.id);
                      }}
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
      {openCount > 0 && (
        <div className="mt-3">
          <Pager page={queuePage} totalPages={totalPages} onChange={setQueuePage} />
        </div>
      )}
    </Card>
  );
}
