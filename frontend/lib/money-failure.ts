import { ApiError } from "./api";

/**
 * Truthful copy for a failed deposit/transfer (F06 interrupted-response UX).
 *
 * The danger after an interrupted money operation is a user who does not know
 * whether it went through and acts on a guess. A definitive rejection (4xx
 * except 409/429 - validation, insufficient funds, ...) proves the server
 * recorded NOTHING, so the plain server message is the truth. Everything else
 * - a network drop (no HTTP response at all), 5xx, 429, or a 409 idempotency
 * conflict - means the outcome is genuinely UNKNOWN: the server may have
 * committed. The copy must say so, and must say the attempt is saved so a
 * retry checks the server (same idempotency key, never a double post) instead
 * of inviting the user to guess by looking at their balance.
 */
export type MoneyFailure = {
  message: string;
  /** True when the server may have committed - a retry is the safe check. */
  ambiguous: boolean;
};

export function classifyMoneyFailure(
  kind: "deposit" | "transfer",
  err: unknown
): MoneyFailure {
  const what = kind === "deposit" ? "deposit" : "transfer";
  if (err instanceof ApiError) {
    if (err.status === 409) {
      return {
        ambiguous: true,
        message:
          "This " + what + " was submitted before under the same attempt id - it may have already gone through. "
          + "Submitting it again fetches the original result and can never move money twice."
      };
    }
    if (err.status === 429) {
      return {
        ambiguous: true,
        message:
          "Too many attempts right now - nothing was lost. Wait a moment and retry; "
          + "the retry cannot double-post."
      };
    }
    if (err.status >= 500) {
      return {
        ambiguous: true,
        message:
          "The server could not confirm whether your " + what + " went through. "
          + "The attempt is saved - retry to check; it can only post once."
      };
    }
    // A definitive rejection (validation, insufficient funds, ...): the server
    // recorded nothing, so the server's own words are the whole truth.
    return { ambiguous: false, message: err.message };
  }
  // No HTTP response at all (network drop, timeout, killed tab): unknown.
  return {
    ambiguous: true,
    message:
      "We couldn't reach the server, so we can't confirm whether your " + what + " went through. "
      + "The attempt is saved - retry to check; it can never double-post."
  };
}
