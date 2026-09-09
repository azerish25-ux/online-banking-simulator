"use client";

import { Card, CardDescription, CardTitle } from "../../components/ui/card";
import { LoadFailed } from "../../components/ui/load-failed";
import { Skeleton } from "../../components/ui/skeleton";
import { ReversalAction, ReversalMark } from "../../components/admin/reverse-transaction";
import { useAdminTransactions } from "../../lib/queries";
import { fmtDate, maskIban, usd } from "../../lib/format";

/** First 8 chars are enough for an operator to eyeball a linked pair. */
function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : "";
}

/**
 * The console's posted-flow readout and the reversal workbench: REVERSAL rows
 * are labelled with their parent and the operator's reason, a POSTED row that
 * has already been reversed carries a small mark, and every reversible posted
 * TRANSFER/DEPOSIT offers Reverse here (the reason dialog is shared - see
 * ReversalAction).
 */
export function ActivitySection() {
  const recent = useAdminTransactions();
  const rows = recent.data ?? [];

  return (
    <Card>
      <CardTitle>Posted flow &amp; reversals</CardTitle>
      <CardDescription>
        Settled money moves. Reverse a posted transfer or deposit here. The reason is mandatory.
      </CardDescription>
      {recent.isError && recent.data == null ? (
        <LoadFailed
          title="Couldn't load the transfer feed"
          onRetry={() => recent.refetch()}
        />
      ) : recent.isLoading && recent.data == null ? (
        <div className="mt-3 space-y-2"><Skeleton className="h-14" /><Skeleton className="h-14" /></div>
      ) : rows.length === 0 ? (
        <CardDescription>No transactions yet.</CardDescription>
      ) : (
        <ul className="mt-3 space-y-2">
          {rows.map((t) => {
            const isReversal = t.kind === "REVERSAL";
            // A reversal row already carries the legs in the direction money
            // moves back, so a plain A → B route reads correctly; a deposit
            // reversal has no payee - its money returns to the funding rail.
            const dest =
              isReversal && !t.toIban ? "funding rail" : (maskIban(t.toIban) ?? "-");
            const route = (maskIban(t.fromIban) ?? "DEP") + " → " + dest;
            const reversalLine = isReversal
              ? "Reverses " + shortId(t.reversesTransactionId) + ": “" + (t.reversalReason ?? "") + "”"
              : null;
            return (
              <li key={t.id} className="well p-3">
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 text-sm">
                    <span className="flex flex-wrap items-center gap-2">
                      <ReversalMark tx={t} />
                      <span className="mono">{route}</span>
                    </span>
                    <span className="nums ml-2 font-semibold">{usd(t.amount)}</span>
                    <span className="muted ml-2 text-xs">{fmtDate(t.createdAt)}</span>
                  </div>
                  <div className="flex shrink-0 gap-2">
                    <ReversalAction tx={t} />
                  </div>
                </div>
                {reversalLine ? (
                  <p className="muted mt-1 text-sm">{reversalLine}</p>
                ) : t.memo ? (
                  <p className="muted mt-1 text-sm">
                    <span className="label">Memo:</span> {t.memo}
                  </p>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
