import type { Tx } from "../../lib/api-types";
import { fmtDate } from "../../lib/format";

/**
 * The financial date of a row is its POSTING time; the request time is shown
 * only when it differs. A HELD/CANCELLED row has no posting time at all and
 * must never read as having settled: a requested time is
 * not a settlement date, and HELD/CANCELLED rows were never posted.
 */
export function TxWhen({ tx }: { tx: Tx }) {
  if (tx.status === "POSTED") {
    const posted = tx.postedAt ?? tx.createdAt;
    const requested = tx.postedAt && tx.postedAt !== tx.createdAt ? tx.createdAt : null;
    return (
      <>
        <p className="whitespace-nowrap">{fmtDate(posted)}</p>
        {requested ? <p className="muted mt-0.5 text-xs">Requested {fmtDate(requested)}</p> : null}
      </>
    );
  }
  return (
    <>
      <p className="whitespace-nowrap">{fmtDate(tx.createdAt)}</p>
      <p className="muted mt-0.5 text-xs">Not posted</p>
    </>
  );
}
