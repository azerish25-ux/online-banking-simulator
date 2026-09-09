"use client";

import * as React from "react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { Pager } from "../../components/ui/pager";
import { LoadFailed } from "../../components/ui/load-failed";
import { Skeleton } from "../../components/ui/skeleton";
import { ConfirmDialog } from "../../components/ui/confirm-dialog";
import { Field } from "../../components/ui/input";
import { Textarea } from "../../components/ui/textarea";
import { useResultToast } from "../../components/feedback/use-result-toast";
import { ReversalAction } from "../../components/admin/reverse-transaction";
import { useAdminReviewQueue, useDeclineTransaction, useReviewTransaction } from "../../lib/queries";
import { ApiError } from "../../lib/api";
import { fmtDate, maskIban, usd } from "../../lib/format";
import type { Tx } from "../../lib/api-types";

/** What an operator is deciding about one queue case. */
type DecisionAction = "approve" | "decline" | "acknowledge";

/**
 * Operator queue: HELD rows are intents: approving
 * settles the transfer, declining cancels it, so no money moves until an
 * operator decides. A flagged-but-POSTED row (large deposit) already credited,
 * so it only needs acknowledging to leave the queue.
 *
 * Every decision is an exact review: the operator must type a bounded reason
 * and the dialog shows what the decision does. The request carries the case
 * state the operator SAW ({@code expectedStatus}/{@code expectedReviewed}); if
 * another operator already decided, the server answers 409 and this console
 * explains the winning decision and refreshes instead of preserving an
 * optimistic success toast.
 */
export function ReviewQueueSection() {
  const [queuePage, setQueuePage] = React.useState(0);
  const queue = useAdminReviewQueue(queuePage);
  const review = useReviewTransaction();
  const decline = useDeclineTransaction();
  const rows = queue.data?.content ?? [];
  const totalPages = queue.data?.totalPages ?? 1;
  const openCount = queue.data?.totalElements ?? rows.length;
  const [decision, setDecision] = React.useState<{ tx: Tx; action: DecisionAction } | null>(null);
  const [reason, setReason] = React.useState("");
  const [staleConflict, setStaleConflict] = React.useState<string | null>(null);
  // Per-row busy: one pending decision disables THAT row's buttons only: an
  // unrelated row stays actionable.
  const decisionBusyId = review.isPending ? review.variables?.id : decline.isPending ? decline.variables?.id : undefined;
  const busy = (id: string) => decisionBusyId === id;

  // Clearing the last row of a page steps back so the operator is not left
  // staring at an empty page while older items still await review.
  const rowsAtPageStart = React.useRef(0);
  React.useEffect(() => {
    if (rows.length === 0 && rowsAtPageStart.current > 0 && queuePage > 0) {
      setQueuePage((p) => p - 1);
    }
    rowsAtPageStart.current = rows.length;
  }, [rows.length, queuePage]);

  // Success copy derives from the AUTHORITATIVE response: approve and
  // acknowledge settle through the same review mutation, and what actually
  // happened: the row's returned kind/status: decides the words, never which
  // button was clicked.
  useResultToast(review, {
    error: (err) => {
      // A stale 409 is not a generic failure: another operator's decision won.
      // Say so and let the queue refetch show the winning outcome.
      if (err instanceof ApiError && err.status === 409) {
        return {
          message:
            "This case was already decided by another operator. The queue refreshed to show the winning decision."
        };
      }
      return { message: err.message };
    },
    success: {
      toast: (settled) => {
        const flaggedDeposit = settled.kind === "DEPOSIT" || !settled.fromIban;
        if (flaggedDeposit) {
          return { message: "Flag acknowledged. The deposit was credited when it arrived." };
        }
        return settled.status === "POSTED"
          ? { message: "Approved. Transfer settled." }
          : { message: "Review recorded. Case is now " + String(settled.status) + "." };
      },
      run: () => {
        setDecision(null);
        setReason("");
        setStaleConflict(null);
      }
    }
  });
  useResultToast(decline, {
    error: (err) => {
      if (err instanceof ApiError && err.status === 409) {
        return {
          message:
            "This case was already decided by another operator. The queue refreshed to show the winning decision."
        };
      }
      return { message: err.message };
    },
    success: {
      toast: () => ({ message: "Declined. No money moved." }),
      run: () => {
        setDecision(null);
        setReason("");
        setStaleConflict(null);
      }
    }
  });

  // 409 on a decision → refetch so the losing console renders the winner's
  // authoritative state instead of a stale row.
  const queueRefetch = queue.refetch;
  React.useEffect(() => {
    const err = review.error ?? decline.error;
    if (err instanceof ApiError && err.status === 409) {
      void queueRefetch();
    }
  }, [review.error, decline.error, queueRefetch]);

  const openDecision = (tx: Tx, action: DecisionAction) => {
    setStaleConflict(null);
    setReason("");
    setDecision({ tx, action });
  };

  const confirmDecision = () => {
    if (!decision) return;
    const clean = reason.trim();
    if (!clean) return; // confirm stays disabled until a reason is typed
    setStaleConflict(null);
    const body = {
      id: decision.tx.id,
      reason: clean,
      // The state THIS console displayed: the server's stale check.
      expectedStatus: decision.tx.status,
      expectedReviewed: decision.tx.reviewed
    };
    if (decision.action === "decline") {
      decline.mutate(body);
    } else {
      review.mutate(body);
    }
  };

  const ruleText = (t: Tx): string => {
    if (t.status === "HELD") {
      return t.kind === "DEPOSIT"
        ? "Large deposit held for review; nothing credited yet"
        : "Amount at or above the review threshold; funds are not reserved and nothing has moved";
    }
    return t.kind === "DEPOSIT" || !t.fromIban
      ? "Large deposit flagged on arrival; already credited"
      : "Large transfer flagged after settling";
  };

  const decisionCopy: Record<DecisionAction, { title: string; confirmLabel: string; body: (t: Tx) => React.ReactNode }> = {
    approve: {
      title: "Approve and settle this transfer?",
      confirmLabel: "Approve transfer",
      body: (t) => (
        <>
          <p>
            {usd(t.amount)} will move from <span className="mono">{maskIban(t.fromIban ?? "")}</span> to{" "}
            <span className="mono">{maskIban(t.toIban ?? "")}</span>. Sender funds are re-checked at
            approval; the sender and recipient are both notified.
          </p>
          <p className="muted mt-2">
            Nothing has moved while the case was held: approving settles it now. Declining instead
            cancels the intent and no money ever moves.
          </p>
        </>
      )
    },
    decline: {
      title: "Decline this transfer?",
      confirmLabel: "Decline transfer",
      body: (t) => (
        <>
          <p>
            {usd(t.amount)} from <span className="mono">{maskIban(t.fromIban ?? "")}</span> will be
            cancelled. It never settles and no money moves. The sender is notified of the decision.
          </p>
          <p className="muted mt-2">
            Sender funds were never reserved or taken while the case was held.
          </p>
        </>
      )
    },
    acknowledge: {
      title: "Acknowledge this flagged deposit?",
      confirmLabel: "Acknowledge deposit",
      body: (t) => (
        <>
          <p>
            {usd(t.amount)} credited to <span className="mono">{maskIban(t.toIban ?? "")}</span> when
            the deposit arrived. Acknowledging clears the flag: no money moves again.
          </p>
        </>
      )
    }
  };

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
              <li key={t.id} className="well p-3">
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 text-sm">
                    <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                      <Badge tone={isHeld ? "warning" : "neutral"}>
                        {isHeld ? "Held" : isDeposit ? "Flagged deposit" : "Flagged transfer"}
                      </Badge>
                      <span className="nums font-semibold">{usd(t.amount)}</span>
                      <span className="mono muted text-xs">
                        {isDeposit
                          ? maskIban(t.toIban ?? "")
                          : maskIban(t.fromIban ?? "") + " → " + maskIban(t.toIban ?? "")}
                      </span>
                      <span className="muted text-xs">{fmtDate(t.createdAt)}</span>
                    </div>
                    <p className="muted mt-1 text-xs">{ruleText(t)}</p>
                  </div>
                  <div className="flex shrink-0 gap-2">
                    {isHeld ? (
                      <>
                        <Button
                          size="sm"
                          variant="danger"
                          disabled={busy(t.id)}
                          onClick={() => openDecision(t, "decline")}
                        >
                          Decline
                        </Button>
                        <Button
                          size="sm"
                          disabled={busy(t.id)}
                          onClick={() => openDecision(t, "approve")}
                        >
                          Approve
                        </Button>
                      </>
                    ) : (
                      <>
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={busy(t.id)}
                          onClick={() => openDecision(t, "acknowledge")}
                        >
                          Acknowledge
                        </Button>
                        <ReversalAction tx={t} />
                      </>
                    )}
                  </div>
                </div>
                {t.memo ? (
                  <p className="muted mt-1 text-sm">
                    <span className="label">Memo:</span> {t.memo}
                  </p>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}

      <ConfirmDialog
        open={decision != null}
        title={decision ? decisionCopy[decision.action].title : ""}
        confirmLabel={decision ? decisionCopy[decision.action].confirmLabel : ""}
        busy={review.isPending || decline.isPending}
        confirmDisabled={reason.trim().length === 0}
        error={staleConflict}
        body={
          decision ? (
            <div className="space-y-3">
              {decisionCopy[decision.action].body(decision.tx)}
              <Field
                label="Decision reason"
                hint="Required. Preserved in the audit trail; never the full case payload."
              >
                <Textarea
                  value={reason}
                  maxLength={400}
                  placeholder={
                    decision.action === "decline"
                      ? "e.g. Sender could not confirm the instruction"
                      : "e.g. Funds verified; pattern matches customer's history"
                  }
                  onChange={(e) => setReason(e.target.value)}
                />
              </Field>
            </div>
          ) : null
        }
        onClose={() => {
          if (!review.isPending && !decline.isPending) {
            setDecision(null);
            setStaleConflict(null);
          }
        }}
        onConfirm={confirmDecision}
      />
      {openCount > 0 && (
        <div className="mt-3">
          <Pager page={queuePage} totalPages={totalPages} onChange={setQueuePage} />
        </div>
      )}
    </Card>
  );
}
