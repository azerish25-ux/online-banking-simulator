import { Badge } from "./badge";

/**
 * Renders a transaction's lifecycle status for customers. A HELD row is an
 * intent awaiting operator review (money has not moved); CANCELLED rows never
 * settled; POSTED rows actually moved money - the flag on a POSTED deposit is
 * an internal-review marker, not a customer-facing state. An UNKNOWN value is
 * an explicit unsupported/unavailable state, never a silently assumed success
 *: a server value this client does not understand must not render as
 * POSTED.
 */
export function TxStatusBadge({ status }: { status?: string | null }) {
  // Sentence-case status text: the label says what
  // the state means, never an ALL-CAPS code. HELD rows are intents awaiting
  // operator review (no money moved); CANCELLED never settled; POSTED moved.
  switch (status) {
    case "HELD":
      return <Badge tone="warning">Awaiting review</Badge>;
    case "CANCELLED":
      return <Badge tone="danger">Cancelled</Badge>;
    case "POSTED":
      return <Badge tone="success">Posted</Badge>;
    default:
      return <Badge tone="neutral">{status ? "Unknown · " + status : "Unknown"}</Badge>;
  }
}
