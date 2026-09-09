import { Badge } from "./badge";

/**
 * Renders a transaction's lifecycle status for customers. A HELD row is an
 * intent awaiting operator review (money has not moved); CANCELLED rows never
 * settled; POSTED rows actually moved money - the flag on a POSTED deposit is
 * an internal-review marker, not a customer-facing state. An UNKNOWN value is
 * an explicit unsupported/unavailable state, never a silently assumed success
 *: a server value this client does not understand must not render as
 * POSTED.
 *
 * The settled state (Posted) is the expected outcome, so it renders as plain
 * sentence-case text; only the states needing attention get the bracketed
 * token. A ledger marks exceptions - it does not decorate every row.
 */
export function TxStatusBadge({ status }: { status?: string | null }) {
  switch (status) {
    case "HELD":
      return <Badge tone="warning">Awaiting review</Badge>;
    case "CANCELLED":
      return <Badge tone="danger">Cancelled</Badge>;
    case "POSTED":
      return <span className="text-sm text-content-secondary">Posted</span>;
    default:
      return <Badge tone="neutral">{status ? "Unknown · " + status : "Unknown"}</Badge>;
  }
}
