import { Badge } from "./badge";

/**
 * Renders a transaction's lifecycle status for customers. A HELD row is an
 * intent awaiting operator review (money has not moved); CANCELLED rows never
 * settled; POSTED rows actually moved money - the flag on a POSTED deposit is
 * an internal-review marker, not a customer-facing state.
 */
export function TxStatusBadge({ status }: { status?: string | null }) {
  switch (status) {
    case "HELD":
      return <Badge tone="warning">HELD · REVIEW</Badge>;
    case "CANCELLED":
      return <Badge tone="danger">CANCELLED</Badge>;
    case "POSTED":
      return <Badge tone="success">POSTED</Badge>;
    default:
      return <Badge tone="neutral">{(status ?? "POSTED").toUpperCase()}</Badge>;
  }
}
