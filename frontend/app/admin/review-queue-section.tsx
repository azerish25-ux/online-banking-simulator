"use client";

import * as React from "react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Pager } from "../../components/ui/pager";
import { LoadFailed } from "../../components/ui/load-failed";
import { Skeleton } from "../../components/ui/skeleton";
import { ConfirmDialog } from "../../components/ui/confirm-dialog";
import { useResultToast } from "../../components/feedback/use-result-toast";
import { ReversalAction } from "../../components/admin/reverse-transaction";
import { useAdminReviewQueue, useDeclineTransaction, useReviewTransaction } from "../../lib/queries";
import { fmtDate, maskIban, usd } from "../../lib/format";
import type { Tx } from "../../lib/api-types";

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
  const [declineCandidate, setDeclineCandidate] = React.useState<Tx | null>(null);
  // Per-row busy: one pending decision disables THAT row's buttons only - an
  // unrelated row stays actionable (F11). TanStack exposes the in-flight id.
  const reviewBusyId = review.isPending ? review.variables : undefined;
  const declineBusyId = decline.isPending ? decline.variables : undefined;
  const busy = (id: string) => reviewBusyId === id || declineBusyId === id;

  // Clearing the last row of a page steps back so the operator is not left
  // staring at an empty page while older items still await review.
  const rowsAtPageStart = React.useRef(0);
  React.useEffect(() => {
    if (rows.length === 0 && rowsAtPageStart.current > 0 && queuePage > 0) {
      setQueuePage((p) => p - 1);
    }
    rowsAtPageStart.current = rows.length;
  }, [rows.length, queuePage]);

  // Result → toast wiring lives in the shared owner. Success copy derives
  // from the AUTHORITATIVE response (F11): approve and acknowledge settle
  // through the same review mutation, and what actually happened - the row's
  // returned kind/status - decides the words, never which button was clicked.
  // If another operator already settled the case, the response still says the
  // true outcome and the queue refreshes underneath.
  useResultToast(review, {
    success: {
      toast: (settled) => {
        const flaggedDeposit = settled.kind === "DEPOSIT" || !settled.fromIban;
        if (flaggedDeposit) {
          return { message: "Flag acknowledged. The deposit was credited when it arrived." };
        }
        return settled.status === "POSTED"
          ? { message: "Approved. Transfer settled." }
          : { message: "Review recorded. Case is now " + String(settled.status) + "." };
      }
    }
  });
  useResultToast(decline, {
    success: {
      toast: () => ({
        message: "Declined. No money moved."
      }),
      run: () => setDeclineCandidate(null)
    }
  });

  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <CardTitle>Review queue</CardTitle>
        <Badge tone={openCount > 0 ? "danger" : "success"}>{openCount} open</Badge>
      </div>
      {queue.isError && queue.data == null ? (
        <LoadFailed
          title="Couldn't load the review queue"
          description="Nothing changed on the cases. The request failed. Try again."
          onRetry={() => queue.refetch()}
        />
      ) : queue.isLoading && queue.data == null ? (
        <div className="space-y-2"><Skeleton className="h-14" /><Skeleton className="h-14" /></div>
      ) : rows.length === 0 ? (
        <CardDescription>No flagged activity awaiting review.</CardDescription>
      ) : (
        <ul className="space-y-2">
          {rows.map((t) => {
            const isHeld = t.status === "HELD";
            const isDeposit = t.kind === "DEPOSIT" || !t.fromIban;
            return (
              <li key={t.id} className="rounded-md border border-divider p-3">
                <div className="flex items-center justify-between gap-2">
                  <div className="text-sm">
                    <span className="label">
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
                        disabled={busy(t.id)}
                        onClick={() => setDeclineCandidate(t)}
                      >
                        Decline
                      </Button>
                      <Button
                        size="sm"
                        disabled={busy(t.id)}
                        onClick={() => review.mutate(t.id)}
                      >
                        Approve
                      </Button>
                    </div>
                  ) : (
                    <div className="flex shrink-0 gap-2">
                      <Button
                        size="sm"
                        variant="secondary"
                        disabled={busy(t.id)}
                        onClick={() => review.mutate(t.id)}
                      >
                        Acknowledge
                      </Button>
                      <ReversalAction tx={t} />
                    </div>
                  )}
                </div>
                {t.memo ? (
                  <p className="muted mt-1 text-sm">
                    <span className="label">Memo:</span> {t.memo}
                  </p>
                ) : null}
                {isHeld && (
                  <p className="muted mt-1 text-xs">
                    Sender funds are re-checked on approval; declining cancels the intent and
                    nothing ever moves.
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <ConfirmDialog
        open={declineCandidate != null}
        title="Decline this transfer?"
        confirmLabel="Decline transfer"
        busy={decline.isPending}
        body={
          declineCandidate ? (
            <>
              {usd(declineCandidate.amount)} from{" "}
              <span className="mono">{maskIban(declineCandidate.fromIban ?? "")}</span> will be
              cancelled. It never settles and no money moves. The sender is notified of the
              decision.
            </>
          ) : null
        }
        onClose={() => setDeclineCandidate(null)}
        onConfirm={() => {
          if (!declineCandidate) return;
          decline.mutate(declineCandidate.id);
        }}
      />
      {openCount > 0 && (
        <div className="mt-3">
          <Pager page={queuePage} totalPages={totalPages} onChange={setQueuePage} />
        </div>
      )}
    </Card>
  );
}
